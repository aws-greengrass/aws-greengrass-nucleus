package com.aws.iot.evergreen.ipc.common;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.ipc.common.Connection.SocketConnectionImpl;
import com.aws.iot.evergreen.ipc.exceptions.IPCException;
import com.aws.iot.evergreen.ipc.handler.ConnectionManager;
import com.aws.iot.evergreen.util.Log;

import javax.inject.Inject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Wrapper over server socket, binds to an arbitrary port and local address.
 */
public class Server {
    public final static String KERNEL_URI_ENV_VARIABLE_NAME = "AWS_GG_KERNEL_URI";
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    @Inject
    private Log log;

    @Inject
    private ConnectionManager connectionManager;

    @Inject
    private Configuration config;

    //TODO: handle scenario where server can recover from IOException and bind to the same address:port
    public void startup() throws IPCException {
        try {
            serverSocket = new ServerSocket();
            // specifying port 0 causes  the system to pick an ephemeral port and a valid local address to bind the socket
            serverSocket.bind(new InetSocketAddress(0));
            String serverUri = "tcp://" + serverSocket.getInetAddress().getHostAddress() + ":" + serverSocket.getLocalPort();
            log.log(Log.Level.Note, "IPC service  URI: ", serverUri);
            // adding KERNEL_URI under setenv of the root topic. All subsequent processes will have KERNEL_URI
            // set via environment variables
            config.lookup("setenv", KERNEL_URI_ENV_VARIABLE_NAME).setValue(serverUri);
        } catch (IOException e) {
            throw new IPCException("Server socket failed to bind() ", e);
        }
    }

    /**
     * Blocking call that would listen for new connections, once a connection is accepted,
     * it is forwarded to connection manager.
     *
     * @throws IPCException if an IO Exception occurs while listening for new connections.
     */
    public void run() throws IPCException {
        while (!isShutdown.get()) {
            Socket soc;
            try {
                soc = serverSocket.accept();
            } catch (IOException e) {
                if (!isShutdown.get()) {
                    throw new IPCException("Server socket accept() errored ", e);
                }
                break;
            }
            Connection connection = null;
            try {
                connection = new SocketConnectionImpl(soc);
                connectionManager.newConnection(connection);
            } catch (Exception e) {
                if (connection != null) connection.close();
                log.error("Error handling new connection", e);
            }
        }
    }

    public void shutdown() {
        isShutdown.set(true);
        try {
            serverSocket.close();
        } catch (Exception e) {
            log.log(Log.Level.Error, "Error closing server socket", e);
        }
    }

}
