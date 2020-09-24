package software.amazon.eventstream.iot.server;

import software.amazon.awssdk.crt.eventstream.ServerConnection;
import software.amazon.awssdk.crt.eventstream.ServerConnectionContinuation;
import software.amazon.awssdk.crt.eventstream.ServerConnectionContinuationHandler;

public abstract class GGServerConnectionContinuationHandler extends ServerConnectionContinuationHandler {

    private ServerConnection connection;

    protected GGServerConnectionContinuationHandler(final ServerConnectionContinuation continuation,
                                                  ServerConnection connection) {
        super(continuation);
        this.connection = connection;
    }

    public ServerConnection getServerConnection() {
        return connection;
    }
}
