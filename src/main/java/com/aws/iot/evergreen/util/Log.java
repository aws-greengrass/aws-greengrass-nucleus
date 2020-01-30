/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package com.aws.iot.evergreen.util;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

import static com.aws.iot.evergreen.util.Utils.deepToString;
import static com.aws.iot.evergreen.util.Utils.getUltimateCause;
import static com.aws.iot.evergreen.util.Utils.getUltimateMessage;
import static com.aws.iot.evergreen.util.Utils.isEmpty;

public class Log implements Closeable {
    private static final Entry closeMarker = new Entry(Instant.MIN, Level.Note);
    final ArrayBlockingQueue<Entry> queue = new ArrayBlockingQueue<>(100, false);
    private final CopyOnWriteArraySet<Consumer<Entry>> watchers = new CopyOnWriteArraySet<>();
    //    Writer out;
    //    boolean doClose = false;
    private volatile Drainer drainer;
    private Thread handler;
    private Level loglevel = Level.Note;

    {   // There is always at least one log watcher: it queue's the entry for background writing
        addWatcher(e -> {
            while (!queue.offer(e)) {
                queue.poll(); // If the queue would overflow, shed the oldest entries
            }
        });
    }

    public void note(Object... args) {
        log(Level.Note, args);
    }

    public void significant(Object... args) {
        log(Level.Significant, args);
    }

    public void warn(Object... args) {
        log(Level.Warn, args);
    }

    public void error(Object... args) {
        log(Level.Error, args);
    }

    public void log(Level l, Object... args) {
        /* TODO: eventually, this (and everything else that deals with time)
         * needs to be make more flexible to work with a simulation-time clock */
        if (l.ordinal() >= loglevel.ordinal()) {
            log(new Entry(Clock.systemUTC().instant(), l, args));
        }
    }

    @SuppressWarnings("SleepWhileInLoop")
    @Override
    public void close() {
        if (handler != null) {
            queue.add(closeMarker);
        }
    }

    public void setLogLevel(Level l) {
        loglevel = l;
    }

    /**
     * The log method is quite lightweight: the real work is done by a drainer
     * method running in a background thread, which is set by setDrainer. Until
     * a drainer is established, the messages just wait in a queue. In this way,
     * messages can be logged very early in the boot process, and the IO that
     * happens when a log entry is written doesn't slow down the source of the
     * log message.
     */
    public void log(Entry error) {
        watchers.forEach(w -> w.accept(error));
    }

    public void setDrainer(Drainer d) {
        drainer = d;
        if (d != null && (handler == null || !handler.isAlive())) {
            // The logger's real work is done in a background thread
            handler = new Thread() {
                {
                    setName("Log Drainer");
                    setPriority(MIN_PRIORITY);
                    setDaemon(true);
                }

                @Override
                public void run() {
                    Drainer d;
                    while ((d = drainer) != null) {
                        try {
                            Entry e = queue.take();
                            if (e == closeMarker) {
                                Utils.close(d);
                                drainer = null;
                                break;
                            }
                            d.drain(e);
                        } catch (InterruptedException ioe) {
                        } catch (Throwable t) {
                            t.printStackTrace(System.err);
                        }
                    }
                    handler = null;
                }
            };
            handler.start();
        }
    }

    public boolean isDraining() {
        return drainer != null && handler != null && handler.isAlive();
    }

    public void logTo(OutputStream dest) {
        logTo(new BufferedWriter(new OutputStreamWriter(dest, Charset.forName("UTF-8")), 200), dest != System.out);
    }

    public void logTo(Path dest) throws IOException {
        logTo((Appendable) Files.newBufferedWriter(dest, StandardOpenOption.CREATE), true);
    }

    public void logTo(Appendable out, boolean doClose) {
        setDrainer(new Drainer() {
            @Override
            public void drain(Entry e) {
                try {
                    e.appendTo(out);
                    Utils.flush(out);
                } catch (IOException ex) {
                }
            }

            @Override
            public void close() throws IOException {
                if (doClose) {
                    Utils.close(out);
                }
            }
        });
    }

    public void addWatcher(Consumer<Entry> lw) {
        if (lw != null) {
            watchers.add(lw);
        }
    }

    public void logTo(String dest) {
        System.out.println("Sending log to " + dest);
        if (isEmpty(dest) || "stdout".equals(dest) || "stdio".equals(dest)) {
            logTo(System.out);
        } else if ("stderr".equals(dest)) {
            logTo(System.err);
        } else {
            try {
                logTo(new FileWriter(dest), true);
            } catch (IOException ex) {
                logTo(System.out);
                error("Couldn't write to log file", ex);
            }
        }
    }

    public enum Level {
        Note, Significant, Warn, Error
    }

    public static class Entry {
        private static final Object[] empty = new Object[0];
        public final Instant time;
        public final Level level;
        public final Object[] args;
        public Entry(Instant t, Level l, Object... a) {
            time = t;
            level = l;
            args = a == null ? empty : a;
            for (int i = args.length; --i >= 0; ) {
                if (args[i] instanceof CharSequence) {
                    args[i] = args[i].toString(); // make sure entries are immutable
                }
            }
        }

        public void appendTo(Appendable out) throws IOException {
            DateTimeFormatter.ISO_INSTANT.formatTo(time, out);
            switch (level) {
                case Error:
                    out.append("; \u2718");
                    break;
                case Warn:
                    out.append("; ?");
                    break;
                default:
                    out.append("; \u2713");
                    break;
            }
            Throwable err = null;
            if (args != null) {
                for (Object o : args) {
                    out.append("; ");
                    if (o instanceof Throwable) {
                        err = getUltimateCause((Throwable) o);
                        o = getUltimateMessage(err);
                    }
                    deepToString(o, out, 80);
                }
            }
            out.append('\n');
            if (err != null) {
                PrintWriter pw = new PrintWriter(new AppendableWriter(out), false);
                err.printStackTrace(pw);
                pw.flush();
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            try {
                appendTo(sb);
            } catch (IOException ex) {
                sb.append(getUltimateMessage(ex));
            }
            return sb.toString();
        }
    }

    public abstract class Drainer implements Closeable {
        public abstract void drain(Entry e);
    }
}
