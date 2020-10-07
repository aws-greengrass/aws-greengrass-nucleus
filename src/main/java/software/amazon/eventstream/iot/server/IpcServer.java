package software.amazon.eventstream.iot.server;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.logging.Logger;

import software.amazon.awssdk.crt.CRT;
import software.amazon.awssdk.crt.eventstream.ServerConnection;
import software.amazon.awssdk.crt.eventstream.ServerConnectionHandler;
import software.amazon.awssdk.crt.eventstream.ServerListener;
import software.amazon.awssdk.crt.eventstream.ServerListenerHandler;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.ServerBootstrap;
import software.amazon.awssdk.crt.io.ServerTlsContext;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.crt.io.TlsContextOptions;

public class IpcServer implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(IpcServer.class.getName());

    private final EventLoopGroup eventLoopGroup;
    private final SocketOptions socketOptions;
    private final TlsContextOptions tlsContextOptions;
    private final String hostname;
    private final int port;
    private final EventStreamRPCServiceHandler eventStreamRPCServiceHandler;

    private ServerBootstrap serverBootstrap;
    private ServerTlsContext tlsContext;
    private ServerListener listener;
    private boolean serverRunning;

    public IpcServer(EventLoopGroup eventLoopGroup, SocketOptions socketOptions, TlsContextOptions tlsContextOptions, String hostname, int port, EventStreamRPCServiceHandler serviceHandler) {
        this.eventLoopGroup = eventLoopGroup;
        this.socketOptions = socketOptions;
        this.tlsContextOptions = tlsContextOptions;
        this.hostname = hostname;
        this.port = port;
        this.eventStreamRPCServiceHandler = serviceHandler;
        this.serverRunning = false;
    }

    /**
     * Constructor supplied EventStreamRPCServiceHandler self validates that all expected operations
     * have been wired (hand written -> dependency injected perhaps) before launching the service.
     *
     */
    public void runServer() {
        if (serverRunning) {
            throw new RuntimeException("Failed to start IpcServer. It is already started or has not completed a prior shutdown!");
        }
        validateServiceHandler();
        serverBootstrap = new ServerBootstrap(eventLoopGroup);
        tlsContext = tlsContextOptions != null ? new ServerTlsContext(tlsContextOptions) : null;
        listener = new ServerListener(hostname, (short) port, socketOptions, tlsContext, serverBootstrap, new ServerListenerHandler() {
                @Override
                public ServerConnectionHandler onNewConnection(ServerConnection serverConnection, int errorCode) {
                    LOGGER.info("New connection code [" + CRT.awsErrorName(errorCode) + "] for " + serverConnection.getResourceLogDescription());
                    final ServiceOperationMappingContinuationHandler operationHandler =
                            new ServiceOperationMappingContinuationHandler(serverConnection, eventStreamRPCServiceHandler);
                    return operationHandler;
                }

                @Override
                public void onConnectionShutdown(ServerConnection serverConnection, int errorCode) {
                    LOGGER.info("Server connection closed code [" + CRT.awsErrorString(errorCode) + "]: " + serverConnection.getResourceLogDescription());
                }
            });
        serverRunning = true;
        LOGGER.info("IpcServer started...");
    }

    /**
     * Stops running server and allows the caller to wait on a CompletableFuture
     */
    public CompletableFuture<Void> stopServer() {
        if (serverRunning) {
            try {
                if (listener != null) {
                    listener.close();
                    return listener.getShutdownCompleteFuture();
                }
                return CompletableFuture.completedFuture(null);
            } finally {
                serverRunning = false;
                listener = null;
                try {
                    if (tlsContext != null) {
                        tlsContext.close();
                    }
                } finally {
                    if(serverBootstrap != null) {
                        serverBootstrap.close();
                    }
                }
                tlsContext = null;
                serverBootstrap = null;
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Ensures a call to stop server is called when it is closed
     */
    @Override
    public void close() throws Exception {
        stopServer();
    }

    private void validateServiceHandler() {
        eventStreamRPCServiceHandler.validateAllOperationsSet();
        if (eventStreamRPCServiceHandler.getAuthenticationHandler() == null) {
            throw new IllegalStateException(String.format("%s authentication handler is not set!",
                    eventStreamRPCServiceHandler.getServiceName()));
        }
        if (eventStreamRPCServiceHandler.getAuthorizationHandler() == null) {
            throw new IllegalStateException(String.format("%s authorization handler is not set!",
                    eventStreamRPCServiceHandler.getServiceName()));
        }
    }

}
