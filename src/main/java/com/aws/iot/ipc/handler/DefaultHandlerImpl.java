package com.aws.iot.ipc.handler;

import com.aws.iot.evergreen.ipc.common.Constants;
import com.aws.iot.evergreen.ipc.common.FrameReader;
import com.aws.iot.ipc.common.Connection;
import com.aws.iot.ipc.common.ConnectionReader;
import com.aws.iot.ipc.common.ConnectionWriter;
import com.aws.iot.ipc.exceptions.ConnectionClosedException;
import com.aws.iot.ipc.exceptions.IPCGenericException;
import com.aws.iot.util.Log;

import javax.inject.Inject;
import java.util.concurrent.*;
import java.util.function.Function;

import static com.aws.iot.evergreen.ipc.common.Constants.*;
import static com.aws.iot.evergreen.ipc.common.FrameReader.*;
import static com.aws.iot.evergreen.ipc.common.FrameReader.Message;
import static com.aws.iot.evergreen.ipc.common.FrameReader.Message.emptyResponse;
import static com.aws.iot.evergreen.ipc.common.FrameReader.Message.errorMessage;
import static com.aws.iot.evergreen.ipc.common.FrameReader.MessageFrame;

public class DefaultHandlerImpl implements EventHandler, MessageHandler {
    //TODO: reevaluate creating a threadpool or using the one shared using context
    public static final int DEFAULT_EXECUTOR_POOL_SIZE = Runtime.getRuntime().availableProcessors() * 2;
    private final ThreadPoolExecutor threadPoolExecutor;
    private final ConcurrentHashMap<Integer, Function<Message, Message>> ops = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CompletableFuture<Message>> responseMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConnectionWriter> connectedClients = new ConcurrentHashMap<>();

    @Inject
    Log log;

    @Inject
    AuthHandler authHandler;

    public DefaultHandlerImpl(){
        this(new ThreadPoolExecutor(DEFAULT_EXECUTOR_POOL_SIZE, DEFAULT_EXECUTOR_POOL_SIZE, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>()));
    }

    public DefaultHandlerImpl(ThreadPoolExecutor threadPoolExecutor){
        this.threadPoolExecutor = threadPoolExecutor;
    }

    @Override
    public void newMessage(MessageFrame req, String clientId) {

        if(FrameType.RESPONSE == req.type){
            CompletableFuture<Message> future = responseMap.remove(req.sequenceNumber);
            if(future == null || !future.complete(req.message)){
                log.error("Unable to process request with ");
            }
        } else {
            try {
                threadPoolExecutor.submit(() -> dispatch(req,clientId));
            } catch (RejectedExecutionException e) {
                handleDispatchError(clientId, req, e);
            }
        }
    }

    private void dispatch(MessageFrame req, String clientId) {
        Message response;
        final Message msg = req.message;
        try {
            Function<Message, Message> handler = ops.get(msg.getOpCode());
            response = handler == null ? errorMessage("Invalid opcode " + msg.getOpCode()) : handler.apply(msg);
        } catch (Exception e) {
            //TODO: do we surface the actual message to client vs a generic server failed to process request exception
            response = errorMessage(e.getMessage());
            log.error("Error processing request with opcode" + msg.getOpCode(), e);
        }

        ConnectionWriter connection = connectedClients.get(clientId);
        if (connection == null) {
            log.error("Client id :" +clientId+ " not found, dropping message for request :" + req.sequenceNumber );
            return;
        }
        try {
            connection.write(new MessageFrame(req.sequenceNumber, response, FrameType.RESPONSE));
        } catch (Exception e) {

        }
    }

    @Override
    public void registerOpCodeCallBack(Integer opCode, Function<Message, Message> handler) throws IPCGenericException {
        log.log(Log.Level.Note,"registering handler for opcode ", opCode);
        Function<Message, Message> existingFunction = ops.putIfAbsent(opCode, handler);
        if(existingFunction != null){
            throw new IPCGenericException("handler for opcode already registered");
        }
    }

    @Override
    public CompletableFuture<Message> send(Message msg, String clientId) throws IPCGenericException {

        MessageFrame f = new MessageFrame(msg,FrameType.REQUEST);
        ConnectionWriter connection = connectedClients.get(clientId);
        if (connection == null) {
            throw new IPCGenericException("Invalid Client Id");
        }
        CompletableFuture<Message> future = new CompletableFuture<>();

        threadPoolExecutor.submit(() -> {
            try {
                connection.write(f);
            } catch (ConnectionClosedException connectionIOException) {
                future.completeExceptionally(new IPCGenericException("Unable to send Message", connectionIOException));
            }
        });
        responseMap.put(f.sequenceNumber, future);
        return future;
    }

    protected void handleDispatchError(String clientId, MessageFrame req, Exception error) {
        log.error(String.format("Failed to handle newMessage request %s with opcode %d from client %s", req.sequenceNumber, req.message.getOpCode(), clientId), error);
        try {
            ConnectionWriter conn = connectedClients.get(clientId);
            if (conn != null) {
                conn.write(new MessageFrame(req.sequenceNumber, errorMessage(error.getMessage()), FrameType.RESPONSE));
            }
        } catch (Exception e) {
            log.error("Failed to handle newMessage error" , e);
        }
    }

    @Override
    public void newConnection(Connection connection) {
        threadPoolExecutor.submit(() -> {
            try {
                //TODO: use read with timeout
                MessageFrame authReq = connection.read();
                AuthHandler.AuthResponse authResponse = authHandler.doAuth(authReq, connection);

                String errorMsg = authResponse.errorMsg;
                if(authResponse.isAuthorized){
                    if(connectedClients.putIfAbsent(authResponse.clientId,new ConnectionWriter(connection,this,authResponse.clientId))==null){
                        new Thread(new ConnectionReader(connection,this,authResponse.clientId)).start();
                        connection.write(new MessageFrame(authReq.sequenceNumber, emptyResponse(AUTH_OP_CODE) , FrameType.RESPONSE));
                    }else{
                        errorMsg = "Duplicate clientId";
                    }
                }
                if(errorMsg!=null){
                    Message resp = new Message(ERROR_OP_CODE, authResponse.errorMsg.getBytes());
                    connection.write(new MessageFrame(authReq.sequenceNumber, resp, FrameType.RESPONSE));
                    connection.close();
                }
            } catch (Exception e) {
                log.error("Error processing new connection request", e);
                connection.close();
            }
        });
    }

    @Override
    public void clientClosedConnection(String clientId) {
        //TODO: notify someone who is interested
        connectionError(clientId);
    }

    @Override
    public void connectionError(String clientId) {
        ConnectionWriter conn = connectedClients.remove(clientId);
        conn.close();
    }

    @Override
    public void shutdown() {
        log.note("Event handler shutting down");
        try {
            threadPoolExecutor.shutdown();
            if (threadPoolExecutor.awaitTermination(1, TimeUnit.MINUTES)) {
                threadPoolExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
           log.error("Failed to shutdown thread pool ", e);
        }

        connectedClients.keySet().forEach( (clientId) -> {
            ConnectionWriter conn = connectedClients.remove(clientId);
            conn.close();
        });
    }
}
