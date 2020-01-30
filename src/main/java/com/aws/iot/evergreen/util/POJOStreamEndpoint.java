/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

/**
 * POJOStreamEndpoint implements both ends of a symmetric asynchronous
 * bidirectional message stream.  The messages are POJOs (primitives, arrays
 * and Maps) that are serialized via ION.  Each end of the channel has an
 * instance of POJOStreamEndpoint.  While the channels each way are completely
 * asynchronous, there is a callback mechanism for messages that expect a
 * response.  This could be done with SQS+Lambda instead (and sometimes should be)
 * but they sometimes have unfortunate issues in IoT (eg. SQS uses polling)
 * <p>
 * Client and server endpoints are identical except that client ends are
 * initiated with connect(), server ends are initiated with accept().
 */

package com.aws.iot.evergreen.util;

import software.amazon.ion.IonReader;
import software.amazon.ion.IonWriter;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;

import static java.util.concurrent.TimeUnit.SECONDS;

public class POJOStreamEndpoint implements Closeable, Flushable {
    public static final String hostname = computeHostname();
    static final int JsonServerPort = 2381;
    private static final ExecutorService exec = new ThreadPoolExecutor(2, 15, 30, SECONDS, new LinkedBlockingQueue<>());
    public static boolean verbose = true;
    private static int epSlot = 0;

    static {
        /* The world is happier if TCP timeouts are tighter.  Only works on Linux
         * where the user can 'sudo'.  Yes:  It's a super grotesque hack.
         */
        if (!Exec.isWindows) {
            new Thread() {
                {
                    setPriority(MIN_PRIORITY);
                    setName("invoke sysctl");
                }

                @Override
                public void run() {
                    System.out.println(Exec.cmd("sudo", "sysctl", "-w", "net.ipv4.tcp_keepalive_time=60",
                            "net.ipv4" + ".tcp_keepalive_intvl=10"));
                }
            }.start();
        }
    }

    private final ConcurrentHashMap<Object, Callback> callbacks = new ConcurrentHashMap<>();
    IOException closedBy;
    private String name;
    private Closeable cl;
    private InputStream in0;
    private IonWriter out;
    private Dispatcher dispatcher;
    private Thread reader;
    private Object sideCar;
    private int sequence;

    public POJOStreamEndpoint(InputStream in, OutputStream out, Closeable cl, Dispatcher dispatcher) {
        in0 = in;  // don't construct reader until the last moment
        this.out = POJOUtil.writerBuilder.build(out);
        this.cl = cl;
        this.dispatcher = dispatcher;
    }

    public POJOStreamEndpoint(Socket s, Dispatcher dispatcher) throws IOException {
        this(s, dispatcher, () -> Utils.close(s));
        s.setKeepAlive(true);
    }

    @SuppressWarnings("LeakingThisInConstructor")
    public POJOStreamEndpoint(Socket s, Dispatcher dispatcher, Closeable cl) throws IOException {
        this(s.getInputStream(), s.getOutputStream(), () -> {
            Utils.close(s);
            Utils.close(cl);
        }, dispatcher);
        dispatcher.dispatch(this, POJOUtil.map("op", "connected", "socket", s));
    }

    /**
     * Start the server side.  It just waits for connections, and creates
     * Endpoints from them.
     *
     * Has no security on the connection. Only use in demos or via VPN.
     * Someday SSLServerSocket should be supported.
     */
    public static Server startServer(Dispatcher dispatcher) throws IOException {
        return startServer(JsonServerPort, dispatcher);
    }

    public static Server startServer(int port, Dispatcher dispatcher) throws IOException {
        return new Server(port, dispatcher).start();
    }

    /**
     * Starts the client end: connects to the remote server and creates an
     * Endpoint object to wrap it.
     */
    public static POJOStreamEndpoint startConnection(String host, int port, Dispatcher dispatcher, Closeable cl) throws IOException {
        Socket s = new Socket(host, port);
        if (verbose) {
            System.out.println("Connecting to socket " + s);
        }
        return new POJOStreamEndpoint(s, dispatcher, cl).start(null);
    }

    public static POJOStreamEndpoint startConnection(String host, Dispatcher dispatcher, Closeable cl) throws IOException {
        return startConnection(host, JsonServerPort, dispatcher, cl).start(null);
    }

    public static POJOStreamEndpoint startConnection(String host, Dispatcher dispatcher) throws IOException {
        return startConnection(host, JsonServerPort, dispatcher, null).start(null);
    }

    private static Object doReply(POJOStreamEndpoint e, Map o) {
        Object id = POJOUtil.jsget(o, "forid");
        System.out.println("Handling reply to " + e.getName() + " " + id);
        if (id == null) {
            if (POJOUtil.isError(o)) {
                try {
                    e.dispatcher.uncaughtError.doIt(e, o);
                } catch (Throwable ex) {
                    ex.printStackTrace(System.out);
                }
            } else {
                System.out.println("Missing forid in " + e.getName() + " " + Utils.deepToString(o));
            }
        } else {
            Callback c = e.callbacks.remove(id);
            System.out.println("Starting callback " + e.getName() + " " + c);
            if (c == null) {
                System.out.println("Missing callback for " + e.getName() + " " + Utils.deepToString(o));
            } else {
                try {
                    c.callback(POJOUtil.isError(o) ? o : POJOUtil.jsget(o, "value"));
                } catch (Throwable t) {
                    t.printStackTrace(System.out);
                }
            }
        }
        return null;
    }

    private static String computeHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            return "Unknown[" + ex + "]";
        }
    }

    public void addCloseHandler(final Closeable cl2) {
        final Closeable cl1 = cl;
        cl = cl1 == null ? cl2 : () -> {
            Utils.close(cl1);
            Utils.close(cl2);
        };

    }

    public Dispatcher myDispatcher() {
        return dispatcher.parent == null ? pushDispatcher() : dispatcher;
    }

    public Dispatcher pushDispatcher() {
        return dispatcher = new Dispatcher(dispatcher);
    }

    public void remove(String name) {
        dispatcher = dispatcher.remove(name);
    }

    public synchronized <T> T getSideCar(Class<T> claz) {
        if (claz.isInstance(sideCar)) {
            return (T) sideCar;
        }
        if (sideCar == null) {
            try {
                T r = claz.newInstance();
                sideCar = r;
                return r;
            } catch (Throwable ex) {
                throw new IllegalArgumentException("creating sideCar " + claz, ex);
            }
        }
        throw new IllegalArgumentException("Only one sideCar allowed " + claz);
    }

    public POJOStreamEndpoint start(ConcurrentHashMap<POJOStreamEndpoint, Boolean> actives) {
        if ((reader == null || !reader.isAlive()) && dispatcher != null) {
            reader = new Thread() {
                @Override
                public void run() {
                    IonReader reader = POJOUtil.readerBuilder.build(in0);
                    while (in0 != null && reader.next() != null) {
                        try {
                            Object o = POJOUtil.readPOJO(reader);
                            if (verbose) {
                                System.out.println("Read " + getName() + " " + Utils.deepToString(o, 300));
                            }
                            if (o == null) {
                                break;
                            }
                            dispatcher.dispatch(POJOStreamEndpoint.this, o);
                        } catch (Throwable t) {
                            System.out.println("Endpoint read error: " + getName() + " " + t);
                            break;

                        }
                    }
                    Utils.close(in0);
                    in0 = null;
                    Utils.close(reader);
                    dispatcher.dispatch(POJOStreamEndpoint.this, POJOUtil.map("op", "disconnected"));
                    if (actives != null) {
                        actives.remove(POJOStreamEndpoint.this);
                    }
                    close();
                }
            };
            reader.setDaemon(true);
            reader.setName("JsonEndpoint Reader");
            reader.start();
        }
        return this;
    }

    @Override
    public void flush() {
        if (out != null) {
            try {
                out.flush();
            } catch (Throwable t) {
            }
        }
    }

    @Override
    public void close() {
        close(new IOException("Already closed"));
    }

    public void close(IOException cb) {
        Closeable lout = out;
        Closeable lcl = cl;
        cl = null;
        in0 = null;
        out = null;
        Utils.close(lout);
        Utils.close(lcl);
        closedBy = cb;
        cb.fillInStackTrace();
    }

    public boolean live() {
        return in0 != null && reader != null && reader.isAlive();
    }

    public synchronized void sendObject(Map j) throws IOException {
        sendObject(null, j);
    }

    public synchronized void sendObject(Callback callback, Map map) throws IOException {
        Integer key = sequence++;
        if (closedBy == null) {
            try {
                if (callback != null) {
                    callbacks.put(key, callback);
                    map.put("syncid", key);
                    // TODO: implement timeouts
                }
                if (verbose) {
                    System.out.println("Wrote " + getName() + " " + Utils.deepToString(map));
                }
                if (out != null) {
                    POJOUtil.writeFinishPOJO(out, map);
                }
                return;
            } catch (IOException ioe) {
                // almost certainly means that this connection is broken
                close(ioe);
            } catch (Throwable t) {
                // almost certainly means that this connection is broken
                close(new IOException("sendObject: " + t, t));
                t.printStackTrace(System.out);
            }
        }
        if (callback != null) {
            callback.callback(POJOUtil.map("error", closedBy));
        }
        throw closedBy;
    }

    public void sendMap(Object... map) throws IOException {
        sendObject(POJOUtil.map(map));
    }

    public void sendMap(Callback callback, Object... map) throws IOException {
        sendObject(callback, POJOUtil.map(map));
    }

    public String getName() {
        if (name == null) {
            name = "EP" + ++epSlot;
        }
        return name;
    }

    public POJOStreamEndpoint setName(String n) {
        name = n;
        return this;
    }

    @Override
    public String toString() {
        return getName();
    }

    public interface Operator {
        public Object doIt(POJOStreamEndpoint e, Map m) throws IOException;
    }

    public interface Callback {
        public void callback(Object v);
    }

    public static class Server {
        private final int port;
        private final Dispatcher dispatcher;
        private ServerSocket ss;

        @SuppressWarnings("Sta")
        private Server(int port, Dispatcher dispatcher) throws IOException {
            this.port = port;
            this.dispatcher = dispatcher;
        }

        public void stop() {
            Utils.close(ss);
            ss = null;
            if (verbose) {
                System.out.println("JsonEndpoint server stopping");
            }
        }

        /** TODO: rewrite to use netty */
        private Server start() throws IOException {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(port));
            System.out.println("ServerSocket " + ss + "\n\t" + ss.getInetAddress());
            new Thread() {
                {
                    setName("JsonEndpoint server");
                    setDaemon(true);
                }

                @Override
                public void run() {
                    if (verbose) {
                        System.out.println("JsonEndpoint server starting");
                    }
                    final ConcurrentHashMap<POJOStreamEndpoint, Boolean> actives = new ConcurrentHashMap<>();
                    int errs = 0;
                    while (ss != null) {
                        try {
                            new POJOStreamEndpoint(ss.accept(), dispatcher).start(actives);
                            errs = 0;
                        } catch (IOException ex) {
                            System.out.println("JsonEndpoint " + getName() + ex);
                            if (++errs > 10) {
                                break;
                            }
                        }
                    }
                    actives.keySet().forEach(ep -> ep.close());
                }
            }.start();
            return this;
        }
    }

    public static class Dispatcher {
        private final ConcurrentHashMap<String, Operator> ops = new ConcurrentHashMap<>();
        private Dispatcher parent;
        private Operator uncaughtError = (e, o) -> {
            System.err.println("Uncaught Error: " + POJOUtil.jsget(o, "error"));
            return null;
        };

        {
            // default ops
            add("ping", (e, o) -> "Hello from " + hostname);
            add("reply", (e, o) -> doReply(e, o));
            add("connected", (e, o) -> {
                System.out.println("Connected to " + e.getName() + " " + POJOUtil.jsget(o, "socket"));
                return null;
            });
            add("disconnected", (e, o) -> {
                System.out.println("Disconnected " + e.getName());
                return null;
            });
            add("error", (e, o) -> doReply(e, o));
        }

        public Dispatcher(Dispatcher p) {
            parent = p;
        }

        public Dispatcher() {
            this(null);
        }

        public Dispatcher add(String name, Operator c) {
            ops.put(name, c);
            return this;
        }

        public Dispatcher add(Operator c) {
            Named on = c.getClass().getAnnotation(Named.class);
            if (on == null) {
                throw new IllegalArgumentException(c.getClass().getSimpleName()
                        + "must be annotated with OperatorNamed");
            }
            return add(on.value(), c);
        }

        public Dispatcher remove(String name) {
            if (parent != null) {
                parent = parent.remove(name);
            }
            ops.remove(name);
            return parent != null && ops.isEmpty() ? parent : this;
        }

        public Dispatcher synonym(String name, String oname) {
            ops.put(name, ops.get(oname));
            return this;
        }

        public void dispatch(POJOStreamEndpoint e, Object o) {
            Object doAsync = POJOUtil.jsget(o, "bkg");
            if (doAsync == null) {
                doAsync = Boolean.TRUE;
            }
            if (doAsync == Boolean.FALSE) {
                dispatch0(e, o);
            } else {
                exec.submit(() -> dispatch0(e, o));
            }
        }

        public void dispatch0(POJOStreamEndpoint e, Object o) {
            Object syncid = POJOUtil.jsget(o, "syncid");
            try {
                Object opo = POJOUtil.jsget(o, "op");
                if (opo == null) {
                    throw new NullPointerException("missing op parameter");
                }
                Operator f = ops.get(opo.toString());
                if (f == null) {
                    if (parent == null) {
                        throw new IllegalArgumentException("Undefinded op '" + opo + "'");
                    } else {
                        dispatch0(e, parent);
                    }
                } else {
                    Object ret = f.doIt(e, (Map) o);
                    if (ret instanceof Throwable) {
                        throw (Throwable) ret;
                    }
                    if (syncid != null) {
                        e.sendMap("op", "reply", "forid", syncid, "value", Utils.deepToString(ret));
                    }
                }
            } catch (Throwable ex) {
                System.out.println("ERROR " + e.getName() + " " + ex.toString() + " " + Utils.deepToString(o));
                try {
                    if (syncid == null) {
                        e.sendMap("op", "error", "error", ex.toString(), "json", o);
                    } else {
                        e.sendMap("op", "reply", "forid", syncid, "json", o, "error", ex.toString());
                    }
                } catch (IOException ex1) {
                    System.out.println("Error while reporting error: " + e.getName() + " " + ex.toString() + "\n\t" + Utils.deepToString(o));
                    ex.printStackTrace(System.out); // error while reporting error
                }
            }
        }

        public Dispatcher setUncaughtErrorHandler(Operator o) {
            uncaughtError = o;
            return this;
        }
    }

    public abstract class NamedOperator implements Operator {
        abstract String getOperatorName();
    }
}
