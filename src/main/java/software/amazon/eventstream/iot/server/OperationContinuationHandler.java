package software.amazon.eventstream.iot.server;

import com.google.gson.Gson;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import software.amazon.awssdk.crt.eventstream.Header;
import software.amazon.awssdk.crt.eventstream.MessageFlags;
import software.amazon.awssdk.crt.eventstream.MessageType;
import software.amazon.awssdk.crt.eventstream.ServerConnectionContinuationHandler;
import software.amazon.eventstream.iot.EventStreamServiceModel;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;
import software.amazon.eventstream.iot.EventStreamOperationError;

public abstract class OperationContinuationHandler
            <RequestType extends EventStreamableJsonMessage, ResponseType extends EventStreamableJsonMessage,
            StreamingRequestType extends EventStreamableJsonMessage, StreamingResponseType extends EventStreamableJsonMessage>
        extends ServerConnectionContinuationHandler
        implements ServerStreamEventPublisher<StreamingResponseType> {
    private static final Logger LOGGER = Logger.getLogger(OperationContinuationHandler.class.getName());
    private static final String CONTENT_TYPE_HEADER = ":content-type";
    private static final String CONTENT_TYPE_APPLICATION_TEXT = "text/plain";
    private static final String CONTENT_TYPE_APPLICATION_JSON = "application/json";
    private static final String SERVICE_MODEL_TYPE_HEADER = "service-model-type";

    protected static final Gson GSON = EventStreamServiceModel.GSON;

    private OperationContinuationHandlerContext context;
    private List<Header> initialRequestHeaders;
    private RequestType initialRequest;

    public OperationContinuationHandler(final OperationContinuationHandlerContext context) {
        super(context.getContinuation());
        this.context = context;
    }

    @Override
    final protected void onContinuationClosed() {
        LOGGER.finer(String.format("%s stream continuation closed.", getOperationName()));
        try {
            onStreamClosed();
        }
        catch(Exception e) {
            LOGGER.severe(String.format("%s threw %s: %s", getOperationName(), e.getClass().getCanonicalName(), e.getMessage()));
        }
    }


    protected abstract Class<RequestType> getRequestClass();
    protected abstract Class<ResponseType> getResponseClass();
    protected abstract Class<StreamingRequestType> getStreamingRequestClass();
    protected abstract Class<StreamingResponseType> getStreamingResponseClass();

    /**
     * Called when the underlying continuation is closed. Gives operations a chance to cleanup whatever
     * resources may be on the other end of an open stream. Also invoked when an underlying ServerConnection
     * is closed associated with the stream/continuation
     */
    protected abstract void onStreamClosed();

    /**
     * Returns the operation name implemented by the handler. Generated code should populate this
     * @return
     */
    protected abstract String getOperationName();

    /**
     * Should return  true iff operation has either streaming input or output. If neither, return false and only allows
     * an intial-request -> initial->response before closing the continuation.
     *
     * @return
     */
    protected abstract boolean isStreamingOperation();

    public abstract ResponseType handleRequest(final RequestType request);

    /**
     * Handle an incoming stream event from the connected client on the operation.
     *
     * If the implementation throws an exception, the framework will respond with the modeled
     * exception to the client, if it is modeled. If it is not modeled, it will respond with
     * an internal error and log appropriately. Either case, throwing an exception will result
     * in closing the stream. To keep the stream open, do not throw.
     *
     * Note: It is still inappropriate to catch Throwable. java.lang.Error is bad enough to let
     *       the stream close.
     *
     * @param streamRequestEvent
     */
    public abstract void handleStreamEvent(final StreamingRequestType streamRequestEvent);

    /**
     * Retrieves the underlying EventStream request headers for inspection. Pulling these headers
     * out shouldn't be necessary as it means operations are aware of the underlying protocol. Any
     * headers needed to be pulled are candidates for what should be in the service model directly
     * @return
     */
    final protected List<Header> getInitialRequestHeaders() {
        return initialRequestHeaders;   //not a defensive copy
    }

    /**
     * Retrieves the initial request object that initiated the stream
     *
     * For use in handler implementations if initial request is wanted to handle further in-out events
     * May be unecessary memory, but also initial request may be used by framework to log errors with
     * 'request-id' like semantics
     *
     * @return
     */
    final protected RequestType getInitialRequest() {
        return initialRequest;
    }

    /**
     * Retrieves the operation handler context. Use for inspecting state outside of the
     * limited scope of this operation handler.
     *
     * @return
     */
    final protected OperationContinuationHandlerContext getContext () {
        return context;
    }

    @Override
    final public void closeStream() {
        continuation.close();
    }

    /**
     * Used so other processes/events going on in the server can push events back into this
     * operation's opened stream/continuation
     *
     * @param streamingResponse
     */
    final public CompletableFuture<Void> sendStreamEvent(final StreamingResponseType streamingResponse) {
        return sendMessage(streamingResponse);
    }

    final protected CompletableFuture<Void> sendMessage(final EventStreamableJsonMessage message) {
        if (continuation.isClosed()) { //is this check necessary?
            return CompletableFuture.supplyAsync(() -> { throw new EventStreamClosedException(continuation.getNativeHandle()); });
        }
        final List<Header> responseHeaders = new ArrayList<>();
        byte[] outputPayload = message.toPayload(GSON);
        responseHeaders.add(Header.createHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_APPLICATION_JSON));
        responseHeaders.add(Header.createHeader(SERVICE_MODEL_TYPE_HEADER, message.getApplicationModelType()));

        return continuation.sendMessage(responseHeaders, outputPayload, MessageType.ApplicationMessage, 0);
    }

    final protected CompletableFuture<Void> sendModeledError(final EventStreamableJsonMessage message) {
        if (continuation.isClosed()) {  //is this check necessary?
            return CompletableFuture.supplyAsync(() -> { throw new EventStreamClosedException(continuation.getNativeHandle()); });
        }
        final List<Header> responseHeaders = new ArrayList<>();
        byte[] outputPayload = message.toPayload(GSON);
        responseHeaders.add(Header.createHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_APPLICATION_JSON));
        responseHeaders.add(Header.createHeader(SERVICE_MODEL_TYPE_HEADER, message.getApplicationModelType()));

        return continuation.sendMessage(responseHeaders, outputPayload,
                MessageType.ApplicationError, MessageFlags.TerminateStream.getByteValue());
    }

    @Override
    final protected void onContinuationMessage(List<Header> list, byte[] bytes, MessageType messageType, int i) {
        LOGGER.fine("Continuation native id: " + continuation.getNativeHandle());

        try {
            if (initialRequest != null) {
                System.out.println("Handling stream following on event: " + new String(bytes, StandardCharsets.UTF_8));
                final StreamingRequestType streamEvent = GSON.fromJson(new StringReader(new String(bytes, StandardCharsets.UTF_8)),
                        getStreamingRequestClass());
                //exceptions occurring during this processing will result in closure of stream
                handleStreamEvent(streamEvent);
            } else { //this is the initial request
                initialRequestHeaders = new ArrayList<>(list);
                initialRequest = GSON.fromJson(new StringReader(new String(bytes, StandardCharsets.UTF_8)),
                        getRequestClass());
                //call into business logic
                final ResponseType result = handleRequest(initialRequest);
                if(result != null && !isStreamingOperation()) {
                    if (!getResponseClass().isInstance(result)) {
                        throw new RuntimeException("Handler for operation [" + getOperationName()
                                + "] did not return expected type. Found: " + result.getClass().getName());
                    }
                    sendMessage(result).whenComplete((res, ex) -> {
                        if (ex != null) {
                            LOGGER.severe(ex.getClass().getName() + " sending response message: " + ex.getMessage());
                            if (!isStreamingOperation()) {
                                continuation.close();
                            }
                        } else {
                            LOGGER.finer("Response successfully sent");
                        }
                    });
                } else if (isStreamingOperation()) {
                    //stream stays open...nothing else to do
                } else {
                    //not streaming, but null response? we have a problem
                    throw new RuntimeException("Operation handler returned null response!");
                }
            }
        } catch (EventStreamOperationError e) {
            //We do not check if the specific exception thrown is a part of the core service?
            sendModeledError(e);
            LOGGER.warning("Modeled error response: " + e.getApplicationModelType());
        } catch (Exception e) {
            final List<Header> responseHeaders = new ArrayList<>(1);
            byte[] outputPayload = "InternalServerError".getBytes(StandardCharsets.UTF_8);
            responseHeaders.add(Header.createHeader(CONTENT_TYPE_HEADER, CONTENT_TYPE_APPLICATION_TEXT));
            // TODO: are there any exceptions we wouldn't want to return a generic server fault?
            // TODO: this is the kind of exception that should be logged with a request ID especially in a server-client context
            LOGGER.severe(String.format("[%s] operation threw unexpected %s: %s", getOperationName(),
                    e.getClass().getCanonicalName(), e.getMessage()));
            e.printStackTrace();

            continuation.sendMessage(responseHeaders, outputPayload, MessageType.ApplicationError, MessageFlags.TerminateStream.getByteValue())
                    .whenComplete((res, ex) -> {
                        if (ex != null) {
                            LOGGER.severe(ex.getClass().getName() + " sending error response message: " + ex.getMessage());
                        }
                        else {
                            LOGGER.finer("Error response successfully sent");
                        }
                        continuation.close();
                    });
        }
    }
}
