package software.amazon.eventstream.iot.server;

import software.amazon.awssdk.crt.eventstream.ServerConnection;
import software.amazon.awssdk.crt.eventstream.ServerConnectionContinuation;

/**
 * When the server picks up a new incoming stream for an operation, and it has context that must
 * be exposed to an operation handler, that access should be granted here.
 *
 * initialRequest + initialHeaders are candidates to move into this object as well
 *
 * Note:
 */
public class OperationContinuationHandlerContext {
    private final ServerConnection serverConnection;
    private final ServerConnectionContinuation continuation;
    private final AuthenticationData authenticationData;

    public OperationContinuationHandlerContext(final ServerConnection connection,
           final ServerConnectionContinuation continuation,
           final AuthenticationData authenticationData) {
        this.serverConnection = connection;
        this.continuation = continuation;
        this.authenticationData = authenticationData;
    }

    public ServerConnection getServerConnection() {
        return serverConnection;
    }

    public ServerConnectionContinuation getContinuation() {
        return continuation;
    }

    public AuthenticationData getAuthenticationData() {
        return authenticationData;
    }
}
