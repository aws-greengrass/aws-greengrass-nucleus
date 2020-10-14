package software.amazon.eventstream.iot.client;

import software.amazon.awssdk.crt.eventstream.ClientConnectionContinuation;

public class OperationResponseHandlerContext {
    final ClientConnectionContinuation continuation;

    public OperationResponseHandlerContext(ClientConnectionContinuation continuation) {
        this.continuation = continuation;
    }

    public ClientConnectionContinuation getContinuation() {
        return continuation;
    }
}
