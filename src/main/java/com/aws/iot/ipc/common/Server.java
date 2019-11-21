package com.aws.iot.ipc.common;

import com.aws.iot.ipc.common.Connection.DefaultConnectionImpl;
import com.aws.iot.ipc.exceptions.IPCGenericException;
import com.aws.iot.ipc.handler.DefaultHandlerImpl;
import com.aws.iot.util.Log;

import javax.inject.Inject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Listens for incoming connection. Incoming connections are forwarded to the event handler
 */
public class Server {
    private int port;
    private ServerSocket serverSocket;
    private AtomicBoolean isShutdown = new AtomicBoolean(false);
    private Map<String, String> serverInfo = new HashMap<>();
    @Inject
    private Log log;

    @Inject
    private DefaultHandlerImpl eventHandler;

    public void startup() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress(0));
            this.port = serverSocket.getLocalPort();
            serverInfo.put("port", Integer.toString(serverSocket.getLocalPort()));
            serverInfo.put("hostname", serverSocket.getInetAddress().getHostName());
        } catch (Exception e) {
            log.log(Log.Level.Error, "Error starting server with port ", port, e);
        }
    }

    public void run() throws IPCGenericException {
        while (!isShutdown.get()) {
            Socket soc;
            try {
                soc = serverSocket.accept();
            } catch (IOException e) {
                if (!isShutdown.get()) {
                    throw new IPCGenericException("Server socket errored listening for new connection", e);
                }
                break;
            }
            synchronized (serverSocket) {
                try {
                    Connection connection = new DefaultConnectionImpl(soc);
                    eventHandler.newConnection(connection);
                } catch (Exception e) {
                    log.error("Error handling new connection", e);
                }
            }
        }
    }

    public void shutdown() {
        isShutdown.set(true);
        synchronized (serverSocket) {
            try {
                serverSocket.close();
            } catch (Exception e) {
                log.log(Log.Level.Error, "Error closing server socket", e);
            }
        }
    }

    public Map<String, String> getServerInfo() {
        return serverInfo;
    }
}
