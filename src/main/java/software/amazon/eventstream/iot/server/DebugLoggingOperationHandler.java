package software.amazon.eventstream.iot.server;

import com.google.gson.Gson;
import software.amazon.awssdk.crt.eventstream.ServerConnection;
import software.amazon.awssdk.crt.eventstream.ServerConnectionContinuation;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;

import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Useful to set as a handler for an operation with no implementation yet.
 */
public class DebugLoggingOperationHandler extends OperationContinuationHandler
        <EventStreamableJsonMessage, EventStreamableJsonMessage, EventStreamableJsonMessage, EventStreamableJsonMessage> {
    private static final Logger LOGGER = Logger.getLogger(DebugLoggingOperationHandler.class.getName());
    private final String targetOperation;

    public DebugLoggingOperationHandler(final String operationName, final OperationContinuationHandlerContext context) {
        super(context);
        this.targetOperation = operationName;
    }

    @Override
    protected Class<EventStreamableJsonMessage> getRequestClass() {
        return EventStreamableJsonMessage.class;
    }

    @Override
    protected Class<EventStreamableJsonMessage> getResponseClass() {
        return EventStreamableJsonMessage.class;
    }

    @Override
    protected Class<EventStreamableJsonMessage> getStreamingRequestClass() {
        return EventStreamableJsonMessage.class;
    }

    @Override
    protected Class<EventStreamableJsonMessage> getStreamingResponseClass() {
        return EventStreamableJsonMessage.class;
    }

    /**
     * Called when the underlying continuation is closed. Gives operations a chance to cleanup whatever
     * resources may be on the other end of an open stream. Also invoked when an underlying ServerConnection
     * is closed associated with the stream/continuation
     */
    @Override
    protected void onStreamClosed() {
        LOGGER.info(String.format("%s operation onStreamClosed()", targetOperation));
    }

    /**
     * Returns the operation name implemented by the handler. Generated code should populate this
     *
     * @return
     */
    @Override
    protected String getOperationName() {
        return targetOperation;
    }

    /**
     * Should return  true iff operation has either streaming input or output. If neither, return false and only allows
     * an intial-request -> initial->response before closing the continuation.
     *
     * @return
     */
    @Override
    protected boolean isStreamingOperation() {
        return false;
    }

    @Override
    public EventStreamableJsonMessage handleRequest(EventStreamableJsonMessage request) {
        LOGGER.info(String.format("%s operation handleRequest() ::  %s", targetOperation,
                new String(request.toPayload(GSON), StandardCharsets.UTF_8)));
        return new EventStreamableJsonMessage() {
            @Override
            public byte[] toPayload(Gson gson) {
                return "{}".getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public String getApplicationModelType() {
                return "aws.crt.eventstream#EmptyResponse";
            }
        };
    }

    @Override
    public void handleStreamEvent(EventStreamableJsonMessage streamRequestEvent) {
        LOGGER.info(String.format("%s operation handleStreamEvent() ::  %s", targetOperation,
                new String(streamRequestEvent.toPayload(GSON), StandardCharsets.UTF_8)));
    }
}
