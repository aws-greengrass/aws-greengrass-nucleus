/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.eventstreamrpc;

import software.amazon.awssdk.crt.eventstream.*;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamOperationError;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Not sure how public we need to make this class
 */
public class EventStreamRPCClient {
    private static final Logger LOGGER = Logger.getLogger(EventStreamRPCClient.class.getName());
    private final EventStreamRPCConnection connection;

    public EventStreamRPCClient(EventStreamRPCConnection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("Cannot create eventstream RPC client with null connection");
        }
        this.connection = connection;
    }

    /**
     * Work horse of all operations, streaming or otherwise.
     *
     * @return
     */
    public <ReqType extends EventStreamJsonMessage,
            RespType extends EventStreamJsonMessage,
            StrReqType extends EventStreamJsonMessage,
            StrRespType extends EventStreamJsonMessage>
    OperationResponse<RespType, StrReqType> doOperationInvoke(
            OperationModelContext<ReqType, RespType, StrReqType, StrRespType> operationModelContext,
            final ReqType request, Optional<StreamResponseHandler<StrRespType>> streamResponseHandler) {
        final AtomicBoolean isContinuationClosed = new AtomicBoolean(true);

        // GG_NEEDS_REVIEW: TODO: verify inputs here for easier debugging further in
        if (operationModelContext.isStreamingOperation() && !streamResponseHandler.isPresent()) {
            //Even if an operation does not have a streaming response (has streaming input), a
            //stream is physically bidirectional, and even if a streaming response isn't allowed
            //the other side may still send an error through the open stream.
            throw new IllegalArgumentException(operationModelContext.getOperationName() + " is a streaming operation. Must have a streaming response handler!");
        }
        final CompletableFuture<RespType> responseFuture = new CompletableFuture<>();

        final ClientConnectionContinuation continuation = connection.getConnection().newStream(new ClientConnectionContinuationHandler() {
            boolean initialResponseRecieved = false;

            @Override
            protected void onContinuationMessage(List<Header> headers, byte[] payload, MessageType messageType, int messageFlags) {
                final Optional<String> applicationModelType = headers.stream()
                        .filter(header -> header.getName().equals(EventStreamRPCServiceModel.SERVICE_MODEL_TYPE_HEADER)
                                && header.getHeaderType().equals(HeaderType.String))
                        .map(header -> header.getValueAsString())
                        .findFirst();

                //first message back must parse into immediate response
                //follow on messages are stream response handler intended
                if (messageType.equals(MessageType.ApplicationMessage)) {
                    handleData(headers, payload, !initialResponseRecieved, responseFuture, streamResponseHandler,
                            operationModelContext, continuation, isContinuationClosed);
                    if (MessageFlags.TerminateStream.getByteValue() == messageFlags) {
                        this.close();
                        if (operationModelContext.isStreamingOperation()) {
                            try {
                                streamResponseHandler.get().onStreamClosed();
                            } catch (Exception e) {
                                LOGGER.warning(String.format("Client handler onStreamClosed() threw %s: %s",
                                        e.getClass().getCanonicalName(), e.getMessage()));
                            }
                        }
                    }
                    initialResponseRecieved = true;
                } else if (messageType.equals(MessageType.ApplicationError)) {
                    final Optional<Class<? extends EventStreamJsonMessage>> errorClass =
                            operationModelContext.getServiceModel().getApplicationModelClass(applicationModelType.orElse(""));
                    if (!errorClass.isPresent()) {
                        LOGGER.severe(String.format("Could not map error from service. Incoming error type: "
                                + applicationModelType.orElse("null")));
                        handleError(new UnmappedDataException(applicationModelType.orElse("null")),
                                !initialResponseRecieved, responseFuture, streamResponseHandler, continuation, isContinuationClosed);
                    } else {
                        try {
                            final EventStreamOperationError error = (EventStreamOperationError) operationModelContext.getServiceModel().fromJson(errorClass.get(), payload);
                            handleError(error, !initialResponseRecieved, responseFuture, streamResponseHandler, continuation, isContinuationClosed);
                        } catch (Exception e) { //shouldn't be possible, but this is an error on top of an error
                        }
                    }

                    // GG_NEEDS_REVIEW: TODO: application errors always have TerminateStream flag set?
                    //first close the stream immediately if the other side hasn't already done so
                    if (messageFlags == MessageFlags.TerminateStream.getByteValue()) {
                        try {
                            sendClose(continuation, isContinuationClosed).whenComplete((res, ex) -> {
                                this.close();   //ClientContinuationHandler.close()
                                if (operationModelContext.isStreamingOperation()) {
                                    try {
                                        streamResponseHandler.get().onStreamClosed();
                                    } catch (Exception e) {
                                        LOGGER.warning(String.format("Client handler onStreamClosed() threw %s: %s",
                                                e.getClass().getCanonicalName(), e.getMessage()));
                                    }
                                    if (ex != null) {
                                        LOGGER.warning(String.format("Client message close send threw %s: %s",
                                                ex.getClass().getCanonicalName(), ex.getMessage()));
                                    }
                                }
                            });
                        } catch (Exception e) {
                            LOGGER.warning(String.format("Exception thrown closing stream on application error received %s: %s",
                                    e.getClass().getName(), e.getMessage()));
                        }
                    }
                } else if (messageType == MessageType.Ping) {
                    //echo back ping messages to be nice to server, can these happen on continuations?
                    continuation.sendMessage(headers, payload, MessageType.PingResponse, messageFlags);
                } else if (messageType == MessageType.PingResponse) {    //do nothing on ping response
                } else if (messageType == MessageType.ServerError) {
                    LOGGER.severe(operationModelContext.getOperationName() + " server error received");
                    this.close();   //underlying connection callbacks should handle things appropriately
                } else if (messageType == MessageType.ProtocolError) {    //do nothing on ping response
                    LOGGER.severe(operationModelContext.getOperationName() + " protocol error received");
                    this.close();   //underlying connection callbacks should handle things appropriately
                } else {
                    //unexpected message type received on stream
                    handleError(new InvalidDataException(messageType), !initialResponseRecieved, responseFuture,
                            streamResponseHandler, continuation, isContinuationClosed);
                    try {
                        sendClose(continuation, isContinuationClosed).whenComplete((res, ex) -> {
                            if (ex != null) {
                                LOGGER.warning(String.format("Sending close on invalid message threw %s: %s",
                                        ex.getClass().getCanonicalName(), ex.getMessage()));
                            }
                        });
                    } catch (Exception e) {
                        LOGGER.warning(String.format("Sending close on invalid message threw %s: %s",
                                e.getClass().getCanonicalName(), e.getMessage()));
                    }
                }
            }
        });

        final List<Header> headers = new LinkedList<>();
        headers.add(Header.createHeader(EventStreamRPCServiceModel.CONTENT_TYPE_HEADER,
                EventStreamRPCServiceModel.CONTENT_TYPE_APPLICATION_JSON));
        headers.add(Header.createHeader(EventStreamRPCServiceModel.SERVICE_MODEL_TYPE_HEADER,
                operationModelContext.getRequestApplicationModelType()));
        final byte[] payload = operationModelContext.getServiceModel().toJson(request);

        final CompletableFuture<Void> messageFlushFuture = continuation.activate(operationModelContext.getOperationName(),
                headers, payload, MessageType.ApplicationMessage, 0);
        final OperationResponse<RespType, StrReqType> response = new OperationResponse(operationModelContext, continuation,
                responseFuture, messageFlushFuture);

        return response;
    }

    /**
     * @param continuation
     * @return
     */
    private CompletableFuture<Void> sendClose(final ClientConnectionContinuation continuation, final AtomicBoolean isClosed) {
        if (isClosed.compareAndSet(false, true)) {
            return continuation.sendMessage(null, null,
                    MessageType.ApplicationMessage, MessageFlags.TerminateStream.getByteValue());
        } else {
            LOGGER.warning("Stream already closed");    //May help debug extra closes? May remove to avoid noise
            return CompletableFuture.completedFuture(null);
        }
    }

    private <RespType extends EventStreamJsonMessage, StrRespType extends EventStreamJsonMessage>
    void handleData(List<Header> headers, byte[] payload, boolean isInitial, CompletableFuture<RespType> responseFuture,
                    final Optional<StreamResponseHandler<StrRespType>> streamResponseHandler,
                    final OperationModelContext<?, RespType, ?, StrRespType> operationModelContext,
                    ClientConnectionContinuation continuation,
                    final AtomicBoolean isClosed) {
        if (!isInitial && !streamResponseHandler.isPresent()) {
            throw new IllegalArgumentException("Cannot process data handling for stream without a stream response handler set!");
        }

        final Optional<String> applicationModelType = headers.stream()
                .filter(header -> header.getName().equals(EventStreamRPCServiceModel.SERVICE_MODEL_TYPE_HEADER)
                        && header.getHeaderType().equals(HeaderType.String))
                .map(header -> header.getValueAsString())
                .findFirst();
        //check if there is a type at all specified in the header, relay what is
        if (!applicationModelType.isPresent()) {
            handleError(new UnmappedDataException(isInitial ? operationModelContext.getResponseApplicationModelType() :
                            operationModelContext.getStreamingResponseApplicationModelType().get()), isInitial,
                    responseFuture, streamResponseHandler, continuation, isClosed);
        } else if (isInitial) {
            //mismatch between type on the wire and type expected by the operation
            if (!applicationModelType.get().equals(operationModelContext.getResponseApplicationModelType())) {
                handleError(new UnmappedDataException(applicationModelType.get(), operationModelContext.getResponseTypeClass()),
                        isInitial, responseFuture, streamResponseHandler, continuation, isClosed);
                return;
            }
            RespType responseObj = null;
            try {
                responseObj = operationModelContext.getServiceModel().fromJson(operationModelContext.getResponseTypeClass(), payload);
            } catch (Exception e) {
                handleError(new DeserializationException(payload, e), isInitial, responseFuture, streamResponseHandler, continuation, isClosed);
                return; //we're done if we can't deserialize
            }
            //complete normally
            responseFuture.complete(responseObj);
        } else {
            //mismatch between type on the wire and type expected by the operation
            if (!applicationModelType.get().equals(operationModelContext.getStreamingResponseApplicationModelType().get())) {
                handleError(new UnmappedDataException(applicationModelType.get(), operationModelContext.getStreamingResponseTypeClass().get()),
                        isInitial, responseFuture, streamResponseHandler, continuation, isClosed);
                return;
            }
            StrRespType strResponseObj = null;
            try {
                strResponseObj = operationModelContext.getServiceModel().fromJson(
                        operationModelContext.getStreamingResponseTypeClass().get(), payload);
            } catch (Exception e) {
                handleError(new DeserializationException(payload, e), isInitial, responseFuture, streamResponseHandler, continuation, isClosed);
                return; //we're done if we can't deserialize
            }

            try {
                streamResponseHandler.get().onStreamEvent(strResponseObj);
            } catch (Exception e) {
                handleError(e, isInitial, responseFuture, streamResponseHandler, continuation, isClosed);
            }
        }
    }

    /**
     * Handle error and based on result may close stream
     */
    private <RespType, StrRespType> void handleError(Throwable t, boolean isInitial, CompletableFuture<RespType> responseFuture,
                                                     Optional<StreamResponseHandler<StrRespType>> streamResponseHandler,
                                                     ClientConnectionContinuation continuation,
                                                     final AtomicBoolean isClosed) {
        if (!isInitial && !streamResponseHandler.isPresent()) {
            throw new IllegalArgumentException("Cannot process error handling for stream without a stream response handler set!");
        }

        if (isInitial) {
            responseFuture.completeExceptionally(t);
            //failure on initial response future always closes stream
        } else {
            try {
                if (streamResponseHandler.get().onStreamError(t)) {
                    sendClose(continuation, isClosed).whenComplete((res, ex) -> {
                        streamResponseHandler.get().onStreamClosed();
                    });
                }
            } catch (Exception e) {
                LOGGER.warning(String.format("Stream response handler threw exception %s: %s",
                        e.getClass().getCanonicalName(), e.getMessage()));
                sendClose(continuation, isClosed).whenComplete((res, ex) -> {
                    streamResponseHandler.get().onStreamClosed();
                });
            }
        }
    }
}
