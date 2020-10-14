package software.amazon.eventstream.iot.client;

import java.util.concurrent.CompletableFuture;

public interface StreamResponse<ResponseType, StreamRequestType> {

    /**
     * Allows waiting
     *
     * @return
     */
    CompletableFuture<Void> getRequestFlushFuture();

    /**
     * Get the response completable future to wait on the initial response
     * if there is one.
     *
     * @return
     */
    CompletableFuture<ResponseType> getResponse();

    /**
     * Publish stream events on an open operation's event stream.
     *
     * @param streamEvent event to publish
     */
    CompletableFuture<Void> sendStreamEvent(final StreamRequestType streamEvent);

    /**
     * Initiate a close on the event stream from the client side.
     *
     * @return
     */
    CompletableFuture<Void> closeEventStream();

    /**
     * Tests if the stream is closed
     * @return
     */
    boolean isClosed();
}
