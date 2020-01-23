package com.aws.iot.evergreen.ipc.handler;

import com.aws.iot.evergreen.ipc.common.ConnectionWriter;
import com.aws.iot.evergreen.ipc.exceptions.IPCException;
import com.aws.iot.evergreen.util.Log;


import javax.inject.Inject;
import java.util.concurrent.*;
import java.util.function.Function;

import static com.aws.iot.evergreen.ipc.common.FrameReader.*;
import static com.aws.iot.evergreen.ipc.common.FrameReader.Message.errorMessage;

/***
 * Interface exposed to components inside the kernel to interact with external processes
 */

public class MessageDispatcher {

    @Inject
    Log log;

    @Inject
    ConnectionManager connectionManager;

    public static final int DEFAULT_EXECUTOR_POOL_SIZE = Runtime.getRuntime().availableProcessors() * 2;
    private final ConcurrentHashMap<Integer, CompletableFuture<Message>> sequenceNumberFutureMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Function<Message, Message>> destinationCallbackMap = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor threadPoolExecutor;

    public MessageDispatcher() {
        this(new ThreadPoolExecutor(DEFAULT_EXECUTOR_POOL_SIZE, DEFAULT_EXECUTOR_POOL_SIZE, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>()));
    }

    public MessageDispatcher(ThreadPoolExecutor threadPoolExecutor) {
        this.threadPoolExecutor = threadPoolExecutor;
    }
    /**
     * Registers a call back for a destination, Dispatcher will invoke the function for all message with registered destination
     * @param destination
     * @param callBack Function which takes a message and return a message. The function implementation needs to the thread safe
     * @throws IPCException if the callback is already registered for a destination
     */
    public void registerServiceCallback(String destination, Function<Message, Message> callBack) throws IPCException {
        log.log(Log.Level.Note, "registering callBack for destination ", destination);
        Function<Message, Message> existingFunction = destinationCallbackMap.putIfAbsent(destination, callBack);
        if (existingFunction != null) {
            throw new IPCException("callBack for destination already registered");
        }
    }

    /**
     * Allows components inside the kernel to send messages to an external process using clientId
     * Send message is non blocking and returns a future. Each send message is associated with a
     * sequence number which will be used to track the response message.
     *
     * When message with the same sequence number is received, the future will be updated to done.
     * If the message sending errored out, the future will be marked completeExceptionally
     *
     * @param msg  payload
     * @param clientId clientId of process
     * @return future which will be updated when the external process responds with a message
     */
    public CompletableFuture<Message> sendMessage(String clientId, Message msg, String destination) {
        CompletableFuture<Message> future = new CompletableFuture<>();
        MessageFrame msgFrame = new MessageFrame(destination, msg, FrameType.REQUEST);
        //TODO: return CompletableFuture with a timeout
        try {
            threadPoolExecutor.submit(() -> processOutgoingMessage(clientId, msgFrame, future));
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        sequenceNumberFutureMap.put(msgFrame.sequenceNumber, future);
        return future;
    }

    /**
     * Looks up connection associated with the client and writes message to the connection.
     *
     * This method needs to be thread safe, writing to a connection is synchronized at the connection level.
     *
     * @param clientId clientId of the destination process
     * @param msgFrame
     * @param future future used to track the response of the
     */
    public void processOutgoingMessage(String clientId, MessageFrame msgFrame, CompletableFuture<Message> future) {
        ConnectionWriter connection = connectionManager.getConnectionWriter(clientId);
        if (connection == null) {
            future.completeExceptionally(new IPCException("Invalid Client Id"));
        } else {
            try {
                connection.write(msgFrame);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }
    }

    /**
     * Process an incoming message from an external process.
     * If the message is a new request, new task to process the request is added to a queue
     * Messages that represent a response for a previous outgoing request are passed on to the callee
     * by updating the corresponding future.
     *
     * incomingMessage can be invoked concurrently by multiple connections and should be thread safe
     *
     * @param clientId clientId of the process who sent the message
     * @param msgFrame
     */
    public void incomingMessage(String clientId, MessageFrame msgFrame) {
        switch (msgFrame.type) {
            case REQUEST:
                try {
                    threadPoolExecutor.submit(() -> processIncomingMessage(clientId, msgFrame));
                } catch (RejectedExecutionException e) {
                    log.error("Unable to process incoming message from client "
                            + clientId + " seq: " + msgFrame.sequenceNumber, e);
                }
                break;
            case RESPONSE:
                CompletableFuture<Message> future = sequenceNumberFutureMap.remove(msgFrame.sequenceNumber);
                if (future == null || !future.complete(msgFrame.message)) {
                    log.error("Error processing response with sequence number" + msgFrame.sequenceNumber);
                }
                break;
        }
    }

    /**
     * Process a new message that is a new request as follows
     *
     * Looks up the destination callback for the request
     *  returns error message to the process if callback not found
     * invokes the callback with the payload,
     * response from callback/ errors from callback is written to the connection
     *
     * @param msgFrame contains the destination and payload of the request
     * @param clientId clientId of the process who sent the request
     */
    private void processIncomingMessage(String clientId, MessageFrame msgFrame) {
        Message response;
        final Message msg = msgFrame.message;
        try {
            Function<Message, Message> destinationCallback = destinationCallbackMap.get(msgFrame.destination);
            response = (destinationCallback == null) ? errorMessage("Invalid destination " + msgFrame.destination) : destinationCallback.apply(msg);
        } catch (Exception e) {
            //TODO: do we surface the actual message to client vs a generic server failed to process request exception
            response = errorMessage(e.getMessage());
            log.error("Error processing request with destination " + msgFrame.destination, e);
        }

        ConnectionWriter connection = connectionManager.getConnectionWriter(clientId);
        if (connection == null) {
            log.error("Client id :" + clientId + " not found, dropping response for request :" + msgFrame.sequenceNumber);
            return;
        }
        try {
            connection.write(new MessageFrame(msgFrame.sequenceNumber, msgFrame.destination, response, FrameType.RESPONSE));
        } catch (Exception e) {
            log.error("Error writing message, dropping message for request :" + msgFrame.sequenceNumber);
        }
    }

    /**
     * Shutdown the thread pool executor, no new messages will added the the queue
     * once shutdown is called.
     */
    public void shutdown() {
        log.note("Dispatcher shutting down");
        //TODO: look at other synchronisation methods to close the connection after
        // completing all write operation in the thread pool queue
        try {
            threadPoolExecutor.shutdown();
            //TODO: make wait time configurable
            if (!threadPoolExecutor.awaitTermination(1, TimeUnit.MINUTES)) {
                log.error("Unable to drain all task in thread pool");
            }
            log.note("Dispatcher shutdown");
        } catch (InterruptedException e) {
            log.error("Failed to shutdown thread pool cleanly", e);
        }
    }

}
