package software.amazon.eventstream.iot.server;

import java.util.concurrent.CompletableFuture;

public interface ServerStreamEventPublisher<StreamEventType> {
    /**
     * Used so other processes/events going on in the server can push events back into this
     * opened stream/continuation. It's an abstraction so the entire OperationContinuationHandler
     * doesn't have to be passed around
     *
     * @param streamEvent event to publish
     * @return Completable future for having sent the stream event
     */
    public CompletableFuture<Void> sendStreamEvent(final StreamEventType streamEvent);

    /**
     * Closes the stream without sending any message/data. Any data that must be sent to indicate
     * close reason must be a part of the streaming response and sent prior with
     * sendStreamEvent.
     * 
     * @<code>publisher.sendStreamEvent(...).whenComplete((ret, ex) -> publisher.closeStream());</code>
     * @return Completable future for having send the termination message on the stream.
     */
    public CompletableFuture<Void> closeStream();
}
