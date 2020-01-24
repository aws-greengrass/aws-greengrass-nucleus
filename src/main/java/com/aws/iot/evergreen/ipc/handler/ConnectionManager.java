package com.aws.iot.evergreen.ipc.handler;

import com.aws.iot.evergreen.ipc.common.Connection;
import com.aws.iot.evergreen.ipc.common.ConnectionReader;
import com.aws.iot.evergreen.ipc.common.ConnectionWriter;
import com.aws.iot.evergreen.ipc.common.FrameReader;
import com.aws.iot.evergreen.ipc.common.RequestContext;
import com.aws.iot.evergreen.ipc.exceptions.ConnectionIOException;
import com.aws.iot.evergreen.ipc.exceptions.IPCClientNotAuthorizedException;
import com.aws.iot.evergreen.util.Log;

import javax.inject.Inject;
import java.util.concurrent.ConcurrentHashMap;

import static com.aws.iot.evergreen.ipc.common.Constants.AUTH_SERVICE;
import static com.aws.iot.evergreen.ipc.common.FrameReader.FrameType;
import static com.aws.iot.evergreen.ipc.common.FrameReader.Message.errorMessage;
import static com.aws.iot.evergreen.ipc.common.FrameReader.MessageFrame;


/**
 * Manages connections from when a connection is created by the server to connection closed
 * Server and connections events mentioned below to connection manager
 * 1. newConnection:
 *    Connection manager authenticates new connection, checks for duplicate client connection
 *    and add the connection to the list of connected clients.
 * 2. clientClosedConnection: close the connection and update the connected clients list
 * 3. connectionError: close the connection and update the connected clients list
 */
public class ConnectionManager {

    private final ConcurrentHashMap<String, ConnectionWriter> clientIdConnectionWriterMap = new ConcurrentHashMap<>();

    @Inject
    Log log;

    @Inject
    AuthHandler authHandler;

    @Inject
    MessageDispatcher messageDispatcher;

    /**
     *
     * Server passes new connections to connection manager for processing. A new thread is spun-off as the
     * connection manager will have to wait for the first frame with the authentication info. The same thread is
     * used for doing blocking read operation from the connection once the connection is authenticated and added to
     * the list of connected clients.
     * @param connection
     */

    public void newConnection(Connection connection) {
        new Thread(() -> processNewConnection(connection)).start();
    }

    public void processNewConnection(Connection connection) {
        RequestContext context;
        try {
            // blocking read operation for auth frame
            MessageFrame authReq;
            try {
                //TODO: read with timeout
                authReq = connection.read();
            } catch (ConnectionIOException e) {
                connection.close();
                return;
            }
            try {
                context = authHandler.doAuth(authReq);
                connection.write(new MessageFrame(authReq.sequenceNumber, AUTH_SERVICE, new FrameReader.Message(new byte[0]), FrameType.RESPONSE));
            } catch (IPCClientNotAuthorizedException e) {
                connection.write(new MessageFrame(authReq.sequenceNumber, AUTH_SERVICE, errorMessage(e.getMessage()), FrameType.RESPONSE));
                connection.close();
                log.note("Unauthorized client");
                return;
            }
            // update the connected clients map and close the existing connection.
            clientIdConnectionWriterMap.compute(context.clientId, (id, existingConnection) -> {
                if (existingConnection != null) {
                    existingConnection.close();
                    log.note("Closed existing connection with client id " + context.clientId);
                }
                return new ConnectionWriter(connection, this, context);
            });
        } catch (Exception e) {
            log.error("Error processing new connection request", e);
            return;
        }
        log.note("Client connected with Id: ", context.clientId);
        //start reading from the new connection
        new ConnectionReader(connection, this, messageDispatcher, context).read();
    }

    /**
     * Called by connection reader when end of stream received for connection input stream
     *
     * @param clientId
     */
    public void clientClosedConnection(String clientId) {
        if (tryCloseConnection(clientId)) {
            log.note("Client closed connection", clientId);
        }
    }

    /**
     * Called when IO error occurred while reading/writing from the connection.
     * connection manager removes the connection from the map before calling close on the connection
     * Its safe to call connectionError on the same clientId multiple times
     *
     * @param clientId
     */
    public void connectionError(String clientId) {
        if (tryCloseConnection(clientId)) {
            log.note("Connection error, closing connection with clientId", clientId);
        }
    }


    public void shutdown() {
        messageDispatcher.shutdown();
        log.note("Connection manager shutdown called");
        clientIdConnectionWriterMap.keySet().forEach((clientId) -> {
            if (tryCloseConnection(clientId)) {
                log.note("Shutting down connection with clientId: ", clientId);
            }
        });
    }

    public ConnectionWriter getConnectionWriter(String clientId) {
        return clientIdConnectionWriterMap.get(clientId);
    }

    /**
     * removes the connection from the map of connected clients and closes the connection
     * There are multiple concurrent paths by which a connection need to be closed, tryCloseConnection
     * relies on ConcurrentHashMap as the synchronization mechanism
     *
     * @param clientId
     * @return
     */
    private boolean tryCloseConnection(String clientId) {
        ConnectionWriter conn = clientIdConnectionWriterMap.remove(clientId);
        if (conn != null) {
            conn.close();
            return true;
        }
        return false;
    }
}
