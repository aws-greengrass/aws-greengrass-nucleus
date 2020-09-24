package software.amazon.eventstream.iot.server;

public class EventStreamClosedException extends RuntimeException {
    public EventStreamClosedException(long continauationId) {
        super(String.format("EventStream [%s] is already closed!", Long.toHexString(continauationId)));
    }
}
