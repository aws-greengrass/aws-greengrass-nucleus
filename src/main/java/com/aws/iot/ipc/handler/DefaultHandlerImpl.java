package com.aws.iot.ipc.handler;

import com.aws.iot.evergreen.ipc.common.Constants;

import com.aws.iot.util.Log;

import javax.inject.Inject;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.function.Function;

import static com.aws.iot.evergreen.ipc.common.FrameReader.*;
import static com.aws.iot.evergreen.ipc.common.FrameReader.Message.errorMessage;
import static com.aws.iot.ipc.common.Server.*;

public class DefaultHandlerImpl implements EventHandler, MessageHandler {
    //TODO: reevaluate creating a threadpool or using the one shared using context
    public static final int DEFAULT_EXECUTOR_POOL_SIZE = Runtime.getRuntime().availableProcessors() * 2;
    public static final String THREAD_POOL_NAME = "MessageHandler";
    private static final int RESPONSE_QUEUE_SIZE = 1;
    private final ThreadPoolExecutor threadPoolExecutor;
    private final ConcurrentHashMap<Integer, Function<Message, Message>> ops = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BlockingQueue<Message>> responseMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Connection> connectedClients = new ConcurrentHashMap<>();

    @Inject
    Log log;

    public DefaultHandlerImpl(){
        this(new ThreadPoolExecutor(DEFAULT_EXECUTOR_POOL_SIZE, DEFAULT_EXECUTOR_POOL_SIZE, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), new ThreadFactory() {
            ThreadFactory f = Executors.defaultThreadFactory();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread t = f.newThread(runnable);
                t.setName(THREAD_POOL_NAME + "-" + t.getName());
                return t;
            }
        }));
    }

    public DefaultHandlerImpl(ThreadPoolExecutor threadPoolExecutor){
        this.threadPoolExecutor = threadPoolExecutor;
    }

    @Override
    public void newMessage(MessageFrame req, String clientId) {
        Message m = req.message;
        BlockingQueue<Message> queue;
        if ((queue = responseMap.get(req.uuid.toString())) != null) {
            queue.offer(m);
        } else {
            try {
                threadPoolExecutor.submit(() -> {
                    try {
                        Message response = dispatch(m);
                        connectedClients.get(clientId).send(new MessageFrame(req.uuid, response));
                    } catch (Exception e) {
                        handleDispatchError(clientId, req, e);
                    }
                });
            } catch (Exception e) {
                handleDispatchError(clientId, req, e);
            }
        }
    }

    private Message dispatch(Message req){
        Function<Message, Message> handler = ops.getOrDefault(req.getOpCode(), (msg) -> {
            throw new UnsupportedOperationException("Invalid opcode " + req.getOpCode());
        });
        return handler.apply(req);
    }

    @Override
    public boolean registerOpCodeHandler(Integer opCode, Function<Message, Message> handler) {
        log.log(Log.Level.Note,"registering handler for opcode ", opCode);
        Function<Message, Message> existingFunction = ops.putIfAbsent(opCode, handler);
        return existingFunction == null;
    }

    @Override
    public String send(Message msg, String clientId) {
        MessageFrame f = new MessageFrame(msg);
        connectedClients.get(clientId).send(f);
        responseMap.put(f.uuid.toString(), new ArrayBlockingQueue<>(RESPONSE_QUEUE_SIZE));
        return f.uuid.toString();
    }

    @Override
    public Message getResponse(String requestId, long timeout, TimeUnit timeUnit) throws InterruptedException {
        BlockingQueue<Message> queue = responseMap.get(requestId);
        if (queue == null) {
            throw new IllegalArgumentException("requestId not found");
        }
        Message response = queue.poll(timeout, timeUnit);
        responseMap.remove(requestId);
        return response;
    }


    protected void handleDispatchError(String clientId, MessageFrame req, Exception error) {
        log.error(String.format("Failed to handle newMessage request %s with opcode %d from client %s", req.uuid.toString(), req.message.getOpCode(), clientId), error);
        try {
            Connection conn = connectedClients.get(clientId);
            if (conn != null) {
                conn.send(new MessageFrame(req.uuid, errorMessage(error.getMessage())));
            }
        } catch (Exception e) {
            log.error("Failed to handle newMessage error" , e);
        }
    }

    @Override
    public void newConnection(Socket soc) {
        threadPoolExecutor.submit(() -> {
            try {
                DataInputStream dis = new DataInputStream(soc.getInputStream());
                DataOutputStream dos = new DataOutputStream(soc.getOutputStream());
                String errorMsg = null;
                //TODO: use readframe with timeout here
                MessageFrame authReq = readFrame(dis);
                // First frame should be the auth request
                if (authReq.message.getOpCode() != Constants.AUTH_OP_CODE) {
                    errorMsg = "Invalid Auth request";
                }
                Message authResponse = null;
                if (errorMsg == null) {
                    authResponse = dispatch(authReq.message);
                    if (authResponse == null || Constants.ERROR_OP_CODE == authResponse.getOpCode()) {
                        errorMsg = "Not Authorized";
                    }
                }
                if (errorMsg == null) {
                    //TODO: transmit token in a more backward compatible manner
                    String clientId = new String(authResponse.getPayload());
                    ConnectionImpl connectionImpl = new ConnectionImpl(soc, this, dis, dos, clientId);
                    if (connectedClients.putIfAbsent(clientId, connectionImpl) != null) {
                        errorMsg = "Duplicate client connection";
                    } else {
                        connectionImpl.listen();
                        connectionImpl.send(new MessageFrame(authReq.uuid, authResponse));
                    }
                }
                if (errorMsg != null) {
                    writeFrame(new MessageFrame(authReq.uuid, errorMessage(errorMsg)), dos);
                    soc.close();
                }

            } catch (Exception e) {
                log.error("Error processing new connection request", e);
            }
        });
    }

    @Override
    public void clientClosedConnection(String clientId) {
        //todo: notify someone who is interested
        connectionError(clientId);
    }

    @Override
    public void connectionError(String clientId) {
        Connection conn = connectedClients.remove(clientId);
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
            Connection conn = connectedClients.remove(clientId);
            conn.close();
        });
    }
}
