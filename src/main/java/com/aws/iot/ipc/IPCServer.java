package com.aws.iot.ipc;

import com.aws.iot.config.Topics;
import com.aws.iot.dependency.ImplementsService;
import com.aws.iot.gg2k.GGService;
import com.aws.iot.util.Log;

import javax.inject.Inject;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.UUID;

import static com.aws.iot.gg2k.client.common.FrameReader.*;


@ImplementsService(name = "ipcServer", autostart = true)
public class IPCServer extends GGService {

    private ServerSocket ss;
    @Inject
    private Dispatcher dispatcher;
    @Inject
    private ConnectionsManager connManager;
    private boolean isRunning = true;

    public IPCServer(Topics c) {
        super(c);
    }

    @Override
    public void startup() {
        try {
            ss = new ServerSocket();
            //TODO: get host address and port dynamically
            ss.bind(new InetSocketAddress("127.0.0.1", 20020));
            log().log(Log.Level.Note, "Started IPC server");
        } catch (Exception e) {
            e.printStackTrace();
        }
        super.startup();
    }

    @Override
    public void run() {
        log().log(Log.Level.Note, "IPC Server listening");
        while (isRunning) {
            try {
                new GGServiceConnection(ss.accept(), dispatcher, connManager).start();
            } catch (IOException e) {
                if (!isRunning) {
                    e.printStackTrace();
                    shutdown();
                }
                break;
            }
        }
    }

    @Override
    public void shutdown() {
        log().log(Log.Level.Note, "IPC Server shutdown called");
        isRunning = false;
        try {
            dispatcher.shutdown();
            ss.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void postInject() {

    }

    public interface ClientConnection {
        String getClientId();

        void send(MessageFrame f);
    }

    public static class GGServiceConnection implements IPCServer.ClientConnection, Closeable {

        Socket soc;
        Dispatcher dispathcer;
        ConnectionsManager connectionsManager;
        String clientId;

        public GGServiceConnection(Socket soc, Dispatcher dispathcer, ConnectionsManager connectionsManager) throws SocketException {
            this.soc = soc;
            this.dispathcer = dispathcer;
            this.connectionsManager = connectionsManager;
            this.clientId = UUID.randomUUID().toString();
            this.soc.setKeepAlive(true);
        }

        public void start() {
            new Thread(() -> {
                try {
                    DataInputStream dis = new DataInputStream(soc.getInputStream());
                    MessageFrame f;
                    //TODO: do auth, the first frame recieved should be for auth
                    connectionsManager.addConnection(this);

                    while ((f = readFrame(dis)) != null) {
                        dispathcer.dispatch(f, clientId);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                close();

            }).start();
        }

        @Override
        public void close() {
            try {
                connectionsManager.removeConnection(clientId);
                soc.getInputStream().close();
                soc.getOutputStream().close();
                soc.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public String getClientId() {
            return clientId;
        }

        @Override
        public synchronized void send(MessageFrame f) {
            try {
                writeFrame(f, new DataOutputStream(soc.getOutputStream()));
            } catch (Exception e) {
                e.printStackTrace();
                close();
            }
        }
    }
}
