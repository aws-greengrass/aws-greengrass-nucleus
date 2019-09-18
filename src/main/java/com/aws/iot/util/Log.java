/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package com.aws.iot.util;

import com.aws.iot.config.*;
import com.aws.iot.dependency.ImplementsService;
import com.aws.iot.gg2k.*;
import static com.aws.iot.util.Utils.*;
import java.io.*;
import java.time.*;
import java.time.format.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.inject.*;

public interface Log {
    public enum Level {
        Note, Significant, Warn, Error
    }
    public void log(Entry e);
    public void log(Level l, Object... args);
    public void logTo(String dest);
    public default void note(Object... args) {
        log(Level.Note, args);
    }
    public default void significant(Object... args) {
        log(Level.Significant, args);
    }
    public default void warn(Object... args) {
        log(Level.Warn, args);
    }
    public default void error(Object... args) {
        log(Level.Error, args);
    }
    public void addWatcher(Consumer<Entry> logWatcher);

    public static class Entry {
        public Entry(Instant t, Level l, Object... a) {
            time = t;
            level = l;
            args = a == null ? empty : a;
            for (int i = args.length; --i >= 0;)
                if (args[i] instanceof CharSequence)
                    args[i] = args[i].toString(); // make sure entries are immutable
        }
        private static final Object[] empty = new Object[0];
        public final Instant time;
        public final Level level;
        public final Object[] args;
    }
    @ImplementsService(name = "log")
    public static class Default extends GGService implements Log {
        // TODO: be less stupid
        @Inject
        public Default(Topics conf) {
            super(conf);
        }
        Writer out;
        boolean doClose = false;
        Thread handler;
        final ArrayBlockingQueue<Entry> queue = new ArrayBlockingQueue<>(100, false);
        {
            logTo(System.out);
        }
        @Inject Clock clock;
        private int loglevel;
        @Override
        public synchronized void log(Level l, Object... args) {
            log(new Entry(clock.instant(), l, args));
        }
        @Override
        public synchronized void log(Entry en) {
            if(out==null) {
                /* Time to initialize.  This would normally happen earlier, like in
                 * postInject, but the log gets created a little early for that. */
                GG2K gg = context.get(GG2K.class);
                config.lookup("file")
                        .dflt("~root/gg2.log")
                        .subscribe((w, nv, ov)
                                -> logTo(gg.deTilde(Coerce.toString(nv))));
                config.lookup("level")
                        .dflt(0)
                        .validate((nv, ov) -> {
                            int i = Coerce.toInt(nv);
                            int limit = Log.Level.values().length;
                            return i < 0 ? 0 : i >= limit ? limit - 1 : i;
                        })
                        .subscribe((w, nv, ov) -> loglevel = Coerce.toInt(nv));
                if(out==null)
                    logTo(System.out); // backstop
            }
            while (!queue.offer(en))
                queue.poll(); // If the queue would overflow, shed the oldest entries
            if (handler == null || !handler.isAlive()) {
                // The logger's real work is done in a background thread
                handler = new Thread() {
                    {
                        setName("Log Handler");
                        setPriority(MIN_PRIORITY);
                        setDaemon(true);
                    }
                    @Override
                    public void run() {
                        while (true)
                            try {
                                Entry e = queue.take();
                                watchers.forEach(w -> w.accept(e));
                                DateTimeFormatter.ISO_INSTANT.formatTo(e.time, out);
                                switch (e.level) {
                                    case Error:
                                        out.append("; ERROR");
                                        break;
                                    case Warn:
                                        out.append("; warn");
                                        break;
                                    default:
                                        out.append("; ok");
                                        break;
                                }
                                Throwable err = null;
                                if (e.args != null)
                                    for (Object o : e.args) {
                                        out.append("; ");
                                        if (o instanceof Throwable) {
                                            err = getUltimateCause((Throwable) o);
                                            o = getUltimateMessage(err);
                                        }
                                        deepToString(o, out, 80);
                                    }
                                out.append('\n');
                                if (err != null) {
                                    PrintWriter pw = new PrintWriter(out);
                                    err.printStackTrace(pw);
                                    pw.flush();
                                } else
                                    out.flush();
                            } catch (InterruptedException ioe) {
                            } catch (Throwable t) {
                                t.printStackTrace(System.err);
                            }
                    }
                };
                handler.start();
            }
        }
        public void logTo(OutputStream dest) {
            doClose = dest != System.out;
            out = new BufferedWriter(new OutputStreamWriter(dest), 200);
        }
        private final CopyOnWriteArraySet<Consumer<Entry>> watchers = new CopyOnWriteArraySet<>();
        @Override
        public void addWatcher(Consumer<Entry> lw) {
            if (lw != null)
                watchers.add(lw);
        }
        @Override
        public void logTo(String dest) {
            System.out.println("Sending log to " + dest);
            if (doClose)
                Utils.close(out);
            if (isEmpty(dest) || "stdout".equals(dest) || "stdio".equals(dest))
                logTo(System.out);
            else if ("stderr".equals(dest))
                logTo(System.err);
            else
                try {
                    logTo(new FileOutputStream(dest, true));
                } catch (FileNotFoundException ex) {
                    logTo(System.out);
                    error("Couldn't write to log file", ex);
                }
        }
    }
}
