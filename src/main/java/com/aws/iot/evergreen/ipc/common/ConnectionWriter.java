package com.aws.iot.evergreen.ipc.common;

import com.aws.iot.evergreen.ipc.exceptions.ConnectionIOException;
import com.aws.iot.evergreen.ipc.handler.ConnectionManager;

public class ConnectionWriter {

    public final Connection connection;
    public final ConnectionManager connectionManager;
    private final RequestContext context;

    public ConnectionWriter(Connection connection, ConnectionManager connectionManager, RequestContext context) {
        this.connection = connection;
        this.connectionManager = connectionManager;
        this.context = context;
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
            connectionManager.connectionError(context.clientId);
            throw connectionIOException;
        }
    }

    public void close() {
        if(!connection.isShutdown()){
            connection.close();
        }
    }
}
