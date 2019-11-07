package com.aws.iot.ipc.common;

import com.aws.iot.evergreen.ipc.common.FrameReader;
import com.aws.iot.ipc.handler.DefaultHandlerImpl;
import com.aws.iot.ipc.handler.EventHandler;
import com.aws.iot.util.Log;

import javax.inject.Inject;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.aws.iot.evergreen.ipc.common.FrameReader.readFrame;
import static com.aws.iot.evergreen.ipc.common.FrameReader.writeFrame;


/**
 *  Listens for incoming connection. Incoming connections are forwarded to the event handler
 */
public class Server {
    private int port;
    private ServerSocket ss;
    private AtomicBoolean listening = new AtomicBoolean(false);

    @Inject
    private Log log;

    @Inject
    private DefaultHandlerImpl eventHandler;


    public void startup() {
        try {
            ss = new ServerSocket();
            ss.bind(new InetSocketAddress(0));
            this.port = ss.getLocalPort();
        } catch (Exception e) {
            log.log(Log.Level.Error, "Error starting server with port ", port, e);
        }
    }

    public void run() {
        listening.set(true);
        while (listening.get()) {
            try {
                Socket soc = ss.accept();
                soc.setKeepAlive(true);
                eventHandler.newConnection(soc);
            } catch (IOException e) {
                if (listening.get()) {
                    log.log(Log.Level.Error, "Error listening for connection ", e);
                    listening.set(false);
                }
                break;
            }
        }
    }

    public void shutdown() {
        listening.set(false);
        try {
            ss.close();
        } catch (Exception e) {
            log.log(Log.Level.Error, "Error closing server socket", e);
        }
    }

    public int getPort() {
        return port;
    }

    public interface Connection {
        void send(FrameReader.MessageFrame f);

        void close();
    }

    /**
     * Represents a long lived connection with an external process.
     *
     * ConnectionImpl does the following:
     * 1. listen for incoming messages and forwards them to event handler.
     * 2. forward connection closed by client/ connection closed events to event handler
     * 3. send outgoing message to the external process
     *
     */
    public static class ConnectionImpl implements Connection, Closeable {

        private final AtomicBoolean listening = new AtomicBoolean(false);
        Socket soc;
        EventHandler eventHandler;
        String clientId;
        DataInputStream dis;
        DataOutputStream dos;

        public ConnectionImpl(Socket soc, EventHandler eventHandler, DataInputStream dis, DataOutputStream dos, String clientId) {
            this.soc = soc;
            this.eventHandler = eventHandler;
            this.clientId = clientId;
            this.dis = dis;
            this.dos = dos;
        }

        //TODO: implement logging, cannot inject log as this is not created by kernel
        public void listen() {
            listening.set(true);
            new Thread(() -> {
                try {
                    FrameReader.MessageFrame f;
                    while ((f = readFrame(dis)) != null) {
                        eventHandler.newMessage(f, clientId);
                    }
                } catch (EOFException eofException) {
                    eventHandler.clientClosedConnection(clientId);
                } catch (Exception e) {
                    if (this.listening.get()) {
                        eventHandler.connectionError(clientId);
                        e.printStackTrace();
                    }
                }
            }).start();
        }

        @Override
        public void close() {
            listening.set(false);
            if (!soc.isClosed()) {
                try {
                    dos.flush();
                    soc.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public synchronized void send(FrameReader.MessageFrame f) {
            if(!listening.get()){
                throw new IllegalStateException("Connection is closed");
            }
            try {
                writeFrame(f, dos);
            } catch (Exception e) {
                e.printStackTrace();
                eventHandler.connectionError(clientId);
            }
        }
    }
}
