package com.aws.iot.evergreen.ipc.common;

import com.aws.iot.evergreen.ipc.exceptions.ClientClosedConnectionException;
import com.aws.iot.evergreen.ipc.exceptions.ConnectionIOException;
import com.aws.iot.evergreen.ipc.handler.ConnectionManager;
import com.aws.iot.evergreen.ipc.handler.MessageDispatcher;

/**
 * One ConnectionReader instance will be created per connection to read incoming messages.
 * synchronisation strategy: connections.read method is synchronized, spinning up multiple
 * ConnectionReader per connection will be safe but exhausts resources.
 */
public class ConnectionReader {

    public final Connection connection;
    public final ConnectionManager connectionManager;
    public final String clientId;
    public final MessageDispatcher messageDispatcher;

    public ConnectionReader(Connection connection, ConnectionManager connectionManager, MessageDispatcher messageDispatcher, String clientId) {
        this.connection = connection;
        this.connectionManager = connectionManager;
        this.clientId = clientId;
        this.messageDispatcher = messageDispatcher;
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
                messageDispatcher.incomingMessage(clientId,connection.read());
            }
        } catch (ClientClosedConnectionException ce) {
            // log
            connectionManager.clientClosedConnection(clientId);
        } catch (ConnectionIOException e) {
            // connection.isShutdown() differentiates between ConnectionIOException thrown
            // when a connection is closed by connectionManager vs ConnectionIOException thrown by an actual error
            if (!connection.isShutdown()) {
                // log
                connectionManager.connectionError(clientId);
            }
        } catch (Exception e) {
            // log
            connectionManager.connectionError(clientId);
        }
    }
}
