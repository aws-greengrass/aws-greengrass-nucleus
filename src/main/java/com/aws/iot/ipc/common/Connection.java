package com.aws.iot.ipc.common;

import com.aws.iot.ipc.exceptions.ClientClosedConnectionException;
import com.aws.iot.ipc.exceptions.ConnectionIOException;
import com.aws.iot.ipc.exceptions.ConnectionClosedException;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.aws.iot.evergreen.ipc.common.FrameReader.*;

public interface Connection {
    MessageFrame read() throws ClientClosedConnectionException, ConnectionIOException;

    MessageFrame readWithTimeOut(int timeoutInMilliSec);

    void write(MessageFrame f) throws ConnectionIOException, ConnectionClosedException;

    void close();

    boolean isLocal();

    boolean isShutdown();


    /**
     * Represents a long lived connection with an external process.
     * <p>
     * ConnectionImpl does the following:
     * 1. listen for incoming messages and forwards them to event handler.
     * 2. forward connection closed by client/ connection closed events to event handler
     * 3. write outgoing message to the external process
     */
    class DefaultConnectionImpl implements Connection, Closeable {

        private final AtomicBoolean isShutdown = new AtomicBoolean(false);
        private final Socket s;
        private final boolean isLocalAddress;
        private final DataInputStream dataInputStream;
        private final DataOutputStream dataOutputStream;

        public DefaultConnectionImpl(Socket s) throws IOException {
            this.s = s;
            this.s.setKeepAlive(true);
            this.dataInputStream = new DataInputStream(this.s.getInputStream());
            this.dataOutputStream = new DataOutputStream(this.s.getOutputStream());
            this.isLocalAddress = s.getInetAddress().isLoopbackAddress();
        }

        @Override
        public MessageFrame read() throws ClientClosedConnectionException, ConnectionIOException {
            try {
                return readFrame(dataInputStream);
            } catch (EOFException eofException) {
                throw new ClientClosedConnectionException("Client closed connection",eofException);
            } catch (Exception e) {
                throw new ConnectionIOException("Error reading Frame",e);
            }
        }

        @Override
        public MessageFrame readWithTimeOut(int timeoutInMilliSec) {
            return null;
        }

        @Override
        public void close() {
            isShutdown.set(true);
            if (!s.isClosed()) {
                try {
                    s.close();
                } catch (Exception e) {
                    //log and do not throw
                }
            }
        }

        @Override
        public boolean isLocal() {
            return isLocalAddress;
        }

        @Override
        public boolean isShutdown() {
            return isShutdown.get();
        }

        @Override
        public synchronized void write(MessageFrame f) throws ConnectionIOException, ConnectionClosedException {
            if (isShutdown.get()) {
                throw new ConnectionClosedException("Connection shutting down");
            }
            try {
                writeFrame(f, dataOutputStream);
            } catch (IOException e) {
                throw new ConnectionIOException("Error writing frame", e);
            }
        }
    }
}
