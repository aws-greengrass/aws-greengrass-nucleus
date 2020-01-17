package com.aws.iot.evergreen.ipc.common;

import com.aws.iot.evergreen.ipc.exceptions.ClientClosedConnectionException;
import com.aws.iot.evergreen.ipc.exceptions.ConnectionIOException;
import com.aws.iot.evergreen.ipc.handler.ConnectionManager;
import com.aws.iot.evergreen.ipc.handler.MessageDispatcher;

/**
 * One ConnectionReader instance will be created per connection to read incoming messages.
 * synchronisation strategy: reading from the connection is synchronized on the input stream, spinning up multiple
 * ConnectionReader per connection will be safe but exhausts resources.
 */
public class ConnectionReader {

    public final Connection connection;
    public final ConnectionManager connectionManager;
    public final MessageDispatcher messageDispatcher;
    private final RequestContext context;

    public ConnectionReader(Connection connection, ConnectionManager connectionManager,
                            MessageDispatcher messageDispatcher, RequestContext context) {
        this.connection = connection;
        this.connectionManager = connectionManager;
        this.messageDispatcher = messageDispatcher;
        this.context = context;
    }

    /**
     * Reads message frames from the connection and forwards them to  message dispatcher
     * Errors while reading message frames are propagated to connection manager. reader stops reading
     * after an IO errors as its is difficult to recover from a partial message read.
     * Connection manager will close the connection and its the clients responsibility to re-connect.
     */
    public void read() {
        try {
            while (!connection.isShutdown()) {
                messageDispatcher.incomingMessage(context, connection.read());
            }
        } catch (ClientClosedConnectionException ce) {
            // log
            connectionManager.clientClosedConnection(context.clientId);
        } catch (ConnectionIOException e) {
            // connection.isShutdown() differentiates between ConnectionIOException thrown
            // when a connection is closed by connectionManager vs ConnectionIOException thrown by an actual error
            if (!connection.isShutdown()) {
                // log
                connectionManager.connectionError(context.clientId);
            }
        } catch (Exception e) {
            // log
            connectionManager.connectionError(context.clientId);
        }
    }
}
