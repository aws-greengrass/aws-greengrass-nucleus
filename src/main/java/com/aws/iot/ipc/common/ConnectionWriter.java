package com.aws.iot.ipc.common;

import com.aws.iot.evergreen.ipc.common.FrameReader;
import com.aws.iot.ipc.exceptions.ConnectionIOException;
import com.aws.iot.ipc.handler.ConnectionManager;

public class ConnectionWriter {

    public final Connection connection;
    public final ConnectionManager connectionManager;
    public final String clientId;

    public ConnectionWriter(Connection connection, ConnectionManager connectionManager, String clientId) {
        this.connection = connection;
        this.connectionManager = connectionManager;
        this.clientId = clientId;
    }

    /**
     * writes a message frame to the output stream. IO errors during write is propagated to connection manager
     * IllegalArgumentException thrown by write for an invalid message frame is propagated to callee
     * @param f
     * @throws ConnectionIOException
     */
    public void write(FrameReader.MessageFrame f) throws ConnectionIOException {
        try {
            connection.write(f);
        } catch (ConnectionIOException connectionIOException) {
            connectionManager.connectionError(clientId);
            throw connectionIOException;
        }
    }

    public void close() {
        if(!connection.isShutdown()){
            connection.close();
        }
    }
}
