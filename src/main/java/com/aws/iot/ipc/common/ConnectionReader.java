package com.aws.iot.ipc.common;

import com.aws.iot.ipc.exceptions.ClientClosedConnectionException;
import com.aws.iot.ipc.exceptions.ConnectionIOException;
import com.aws.iot.ipc.handler.EventHandler;

public class ConnectionReader implements Runnable {

    public final Connection connection;
    public final EventHandler eventHandler;
    public final String clientId;

    public ConnectionReader(Connection connection, EventHandler eventHandler, String clientId) {
        this.connection = connection;
        this.eventHandler = eventHandler;
        this.clientId = clientId;
    }

    @Override
    public void run() {

        while (!connection.isShutdown()) {
            try {
                eventHandler.newMessage(connection.read(), clientId);
            } catch (ClientClosedConnectionException ce) {
                // log
                eventHandler.clientClosedConnection(clientId);
            } catch (ConnectionIOException e) {
                if (connection.isShutdown()) {
                    // log
                    eventHandler.connectionError(clientId);
                }
            } catch (Exception e){
                // log
            }
        }

    }
}
