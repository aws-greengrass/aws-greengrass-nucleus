package com.aws.iot.ipc.handler;

import com.aws.iot.ipc.common.Connection;
import static com.aws.iot.evergreen.ipc.common.FrameReader.*;


/**
 * Handles incoming events from the server and connected processes
 *
 * Events:
 * newMessage: Incoming message is routed to an handler based on op-code or entered to the response map
 *
 * newConnection: Creates a new connection object to write/receive messages from the external process
 *
 * clientClosedConnection/connectionError: close the connection and update the connected clients map
 *
 * shutdown: closes all active connections
 */
public interface EventHandler {

    void newMessage(MessageFrame req, String clientId);

    void newConnection(Connection socket);

    void clientClosedConnection(String ClientId);

    void connectionError(String ClientId);

    void shutdown();
}
