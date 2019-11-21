package com.aws.iot.ipc.common;

import com.aws.iot.evergreen.ipc.common.FrameReader;
import com.aws.iot.ipc.exceptions.ConnectionClosedException;
import com.aws.iot.ipc.exceptions.ConnectionIOException;
import com.aws.iot.ipc.handler.EventHandler;

public class ConnectionWriter {

    public final Connection connection;
    public final EventHandler eventHandler;
    public final String clientId;

    public ConnectionWriter(Connection connection, EventHandler eventHandler, String clientId) {
        this.connection = connection;
        this.eventHandler = eventHandler;
        this.clientId = clientId;
    }

    public void write(FrameReader.MessageFrame f) throws ConnectionClosedException {
        try {
            connection.write(f);
        } catch (ConnectionIOException connectionIOException) {
            eventHandler.connectionError(clientId);
            throw new ConnectionClosedException("Error writing frame ", connectionIOException);
        }
    }

    public void close() {
        connection.close();
    }

}
