package software.amazon.eventstream.iot.client;

public class EventStreamClosedException extends RuntimeException {
    public EventStreamClosedException(long continuationId) {
        super(String.format("EventStream [%s] is already closed!", Long.toHexString(continuationId)));
    }
}
