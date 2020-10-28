/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.awssdk.eventstreamrpc;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import software.amazon.awssdk.crt.CRT;
import software.amazon.awssdk.crt.eventstream.ClientConnection;
import software.amazon.awssdk.crt.eventstream.ClientConnectionHandler;
import software.amazon.awssdk.crt.eventstream.Header;
import software.amazon.awssdk.crt.eventstream.MessageFlags;
import software.amazon.awssdk.crt.eventstream.MessageType;
import software.amazon.awssdk.eventstreamrpc.model.AccessDeniedException;

public class EventStreamRPCConnection implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(EventStreamRPCConnection.class.getName());

    private final EventStreamRPCConnectionConfig config;
    private final AtomicBoolean isConnecting;
    private ClientConnection connection;
    private ClientConnection pendingConnection;

    public EventStreamRPCConnection(final EventStreamRPCConnectionConfig config) {
        this.config = config;
        this.isConnecting = new AtomicBoolean(false);
        this.connection = null;
        this.pendingConnection = null;
    }

    public void connect(final LifecycleHandler lifecycleHandler) {
        if (connection != null || !isConnecting.compareAndSet(false, true)) {
            throw new IllegalStateException("Connection currently being established...");
        }
        if (pendingConnection != null || connection != null) {
            //meaning here is that either a connection attempt is underway or established
            throw new IllegalStateException("Connection already exists");
        }
        ClientConnection.connect(config.getHost(), (short) config.getPort(), config.getSocketOptions(),
                config.getTlsContext(), config.getClientBootstrap(), new ClientConnectionHandler() {
                    @Override
                    protected void onConnectionSetup(ClientConnection clientConnection, int errorCode) {
                        pendingConnection = clientConnection;
                        LOGGER.info(String.format("Socket connection %s:%d to server: [%s]",
                                config.getHost(), config.getPort(), CRT.awsErrorName(errorCode)));
                        if (CRT.AWS_CRT_SUCCESS == errorCode) {
                            MessageAmendInfo messageAmendInfo = config.getConnectMessageAmender().get();
                            //need to send the connect message
                            pendingConnection.sendProtocolMessage(messageAmendInfo.getHeaders(),
                                    messageAmendInfo.getPayload(), MessageType.Connect, 0);
                        } else {
                            connection = null;  //keep this cleared out
                            try {
                                lifecycleHandler.onDisconnect(errorCode);
                            } catch (Exception e) {
                                LOGGER.warning(String.format("Exception thrown onDisconnect for connection failure: %s: %s",
                                        e.getClass().getCanonicalName(), e.getMessage()));
                            }
                        }
                    }

                    @Override
                    protected void onProtocolMessage(List<Header> headers, byte[] payload, MessageType messageType, int messageFlags) {
                        if (MessageType.ConnectAck.equals(messageType)) {
                            try {
                                if (messageFlags == MessageFlags.ConnectionAccepted.getByteValue()) {
                                    try {
                                        connection = pendingConnection;
                                        //now the client is open for business to invoke operations
                                        LOGGER.info("Connection established with event stream RPC server");
                                        lifecycleHandler.onConnect();
                                    } catch (Exception e) {
                                        //this is where we have a choice...if the callers' callback threw exception, do we
                                        //now close the otherwise successfull connection?
                                        LOGGER.warning(String.format("Exception thrown from LifecycleHandler::onConnect() %s: %s",
                                                e.getClass().getCanonicalName(), e.getMessage()));
                                        doOnError(lifecycleHandler, e);
                                    }
                                } else {
                                    //This is access denied, no network issue but the server didn't like the Connect message
                                    LOGGER.warning("AccessDenied to event stream RPC server");
                                    try {
                                        lifecycleHandler.onError(new AccessDeniedException("Connection access denied to event stream RPC server"));
                                    } catch (Exception e) {
                                        //all we should do is ignore exception thrown here since the pending connection is getting closed anyways
                                        LOGGER.warning(String.format("LifecycleHandler threw exception on access denied. %s : %s",
                                                e.getClass().getCanonicalName(), e.getMessage()));
                                    }
                                    pendingConnection.closeConnection(0);
                                }
                            } finally {
                                //unlock for either failure or success
                                pendingConnection = null;
                                isConnecting.compareAndSet(true, false);
                            }
                        } else if (MessageType.PingResponse.equals(messageType)) {
                            LOGGER.finer("Ping response received");
                        } else if (MessageType.Ping.equals(messageType)) {
                            // GG_NEEDS_REVIEW: TODO: be nice and reply with PingResponse and all of the same input data except message type,
                            //      but we don't expect server to send these normally
                            //only respond to ping if it's an established connection
                            if (connection != null) {
                                connection.sendProtocolMessage(headers, payload, MessageType.PingResponse, messageFlags);
                            }
                        } else if (MessageType.Connect.equals(messageType)) {
                            LOGGER.severe("Erroneous connect message type received by client. Closing");
                            if (connection != null) {
                                // GG_NEEDS_REVIEW: TODO: do we have a sensible error code to use here?
                                connection.closeConnection(0);
                            }
                        } else if (MessageType.ProtocolError.equals(messageType) || MessageType.ServerError.equals(messageType)) {
                            LOGGER.severe("Received " + messageType.name() + ": " + CRT.awsErrorName(CRT.awsLastError()));
                            // GG_NEEDS_REVIEW: TODO: if there is a payload, it's likely possible to pull out a message field
                            //      should be a ConnectionError() exception type that throws and contains this
                            //      message.
                            try {
                                lifecycleHandler.onError(new RuntimeException("Received " + messageType.name()));
                            } catch (Exception e) {
                                LOGGER.warning("Connection lifecycle handler threw "
                                        + e.getClass().getName() + " onError(): " +  e.getMessage());
                            }
                            connection.closeConnection(0);
                        } else {    //don't kill entire connection over this
                            LOGGER.severe("Unprocessed message type: " + messageType.name());
                        }
                    }

                    @Override
                    protected void onConnectionClosed(int errorCode) {
                        connection = null;  //null this out so a future attempt can be made
                        LOGGER.finer("Socket connection closed: " + CRT.awsErrorName(errorCode));
                        try {
                            lifecycleHandler.onDisconnect(errorCode);
                        }
                        catch (Exception e) {
                            LOGGER.warning(String.format("Exception thrown onDisconnect: %s: %s",
                                    e.getClass().getCanonicalName(), e.getMessage()));
                        }
                    }
                });
    }

    public void disconnect() {
        if (!isConnecting.get() && connection != null) {
            connection.closeConnection(0);
        }
    }

    private void doOnError(LifecycleHandler lifecycleHandler, Throwable t) {
        try {
            if (lifecycleHandler.onError(t)) {
                LOGGER.fine("Closing connection due to LifecycleHandler::onError() returning true");
                connection.closeConnection(0);
            }
        }
        catch (Exception ex) {
            LOGGER.warning(String.format("Closing connection due to LifecycleHandler::onError() throwing %s : %s",
                    ex.getClass().getCanonicalName(), ex.getMessage()));
            connection.closeConnection(0);
        }
    }

    public ClientConnection getConnection() {
        return connection;
    }

    @Override
    public void close() throws Exception {
        disconnect();
    }

    /**
     * Lifecycle handler is how a client can react and respond to connectivity interruptions. Connectivity
     * interruptions are isolated from operation availability issues.
     */
    public interface LifecycleHandler {
        /**
         * Invoked only if there is a successful connection. Leaves out the error code since it will have
         * already been compared to AWS_OP_SUCCESS
         */
        void onConnect();

        /**
         * Invoked for both connect failures and disconnects from a healthy state
         *
         * @param errorCode
         */
        void onDisconnect(int errorCode);

        /**
         * Used to communicate errors that occur on the connection during any phase of its lifecycle that may
         * not appropriately or easily attach to onConnect() or onDisconnect(). Return value of this indicates
         * whether or not the client should stay connected or terminate the connection. Returning true indicates
         * the connection should terminate as a result of the error, and false indicates that the connection
         * should not. If the handler throws, is the same as returning true.
         *
         * Note: Some conditions when onError() is called will not care about the return value and always
         * result in closing the connection. AccessDeniedException is such an example
         *
         * @param t Exception
         * @returns true if the connection should be terminated as a result of handling the error
         */
        boolean onError(Throwable t);
    }
}
