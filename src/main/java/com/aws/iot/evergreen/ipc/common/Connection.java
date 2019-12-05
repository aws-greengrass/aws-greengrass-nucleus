package com.aws.iot.evergreen.ipc.common;

import com.aws.iot.evergreen.ipc.exceptions.ClientClosedConnectionException;
import com.aws.iot.evergreen.ipc.exceptions.ConnectionIOException;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.aws.iot.evergreen.ipc.common.FrameReader.*;

/**
 * Represents a long lived connection with an external process.
 */

public interface Connection {

    MessageFrame read() throws ConnectionIOException;

    void write(MessageFrame f) throws ConnectionIOException;

    void close();

    boolean isLocal();

    boolean isShutdown();

    class SocketConnectionImpl implements Connection, Closeable {

        private final AtomicBoolean isShutdown = new AtomicBoolean(false);
        private final Socket s;
        private final boolean isLocalAddress;
        private final DataInputStream dataInputStream;
        private final DataOutputStream dataOutputStream;

        public SocketConnectionImpl(Socket s) throws IOException {
            this.s = s;
            this.s.setKeepAlive(true);
            this.dataInputStream = new DataInputStream(this.s.getInputStream());
            this.dataOutputStream = new DataOutputStream(this.s.getOutputStream());
            this.isLocalAddress = s.getInetAddress().isLoopbackAddress();
        }

        /**
         * Blocking call which will return when one MessageFrame is read from the connection input stream.
         * readFrame is synchronized to prevent parallel reads from a single input stream. All read error are
         * propagated upstream
         */
        @Override
        public MessageFrame read() throws ConnectionIOException {
            try {
                return readFrame(dataInputStream);
            } catch (EOFException eofException) {
                throw new ClientClosedConnectionException("Client closed connection",eofException);
            } catch (Exception e) {
                throw new ConnectionIOException("Error reading Frame",e);
            }
        }

        /**
         * Close the socket, this will also close the input/output stream. Synchronization between
         * close and write is deferred to the callee. Closing an already closed connection will have no effect.
         */
        @Override
        public void close() {
            isShutdown.set(true);
            try {
                s.close();
            } catch (Exception e) {
                //log
            }
        }

        /**
         * Converts the message frame into bits and write them to output stream. writeFrame synchronizes multiple
         * concurrent writes to the same output stream.
         *
         * @param f MessageFrame that will be written to the output stream
         * @throws ConnectionIOException if any IO Error occurs while writing bits to the stream
         */
        @Override
        public void write(MessageFrame f) throws ConnectionIOException {
            try {
                writeFrame(f, dataOutputStream);
            } catch (IOException e) {
                throw new ConnectionIOException("Error writing frame", e);
            }
        }

        @Override
        public boolean isLocal() {
            return isLocalAddress;
        }

        /**
         * @return true if the connection close() method was invoked.
         */
        @Override
        public boolean isShutdown() {
            return isShutdown.get();
        }
    }
}
