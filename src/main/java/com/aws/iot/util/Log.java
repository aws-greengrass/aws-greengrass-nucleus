/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.util;

import com.aws.iot.dependency.Context.Dependency;
import static com.aws.iot.util.Utils.*;
import java.io.*;
import java.time.*;
import java.time.format.*;
import java.util.concurrent.*;
import java.util.function.*;

public interface Log {
    public enum Level {
        Note, Warn, Error
    }
    public void log(Entry e);
    public void log(Level l, Object... args);
    public void logTo(String dest);
    public default void note(Object... args) {
        log(Level.Note, args);
    }
    public default void warn(Object... args) {
        log(Level.Note, args);
    }
    public default void error(Object... args) {
        log(Level.Note, args);
    }
    public void addWatcher(Consumer<Entry> logWatcher);
    public static class Entry {
        public Entry(Instant t, Level l, Object... a) {
            time = t; level = l; args = a==null ? empty : a;
            for(int i=args.length; --i>=0; )
                if(args[i] instanceof CharSequence) args[i] = args[i].toString();
        }
        private static final Object[] empty = new Object[0];
        public final Instant time;
        public final Level level;
        public final Object[] args;
    }

    public static class Default implements Log {
        // TODO: be less stupid
        protected Writer out;
        boolean doClose = false;
        { logTo(System.out); }
        @Dependency Clock clock;
        @Override
        public synchronized void log(Level l, Object... args) {
            log(new Entry(clock.instant(), l, args));
        }
        @Override
        public synchronized void log(Entry e) {
            try {
                watchers.forEach(w->w.accept(e));
                out.append(DateTimeFormatter.ISO_INSTANT.format(e.time));
                switch(e.level) {
                    case Error: out.append("; ERROR"); break;
                    case Warn: out.append("; warn"); break;
                    default: out.append("; ok"); break;
                }
                Throwable err = null;
                if(e.args!=null)
                    for(Object o:e.args) {
                        out.append("; ");
                        if(o instanceof Throwable) {
                            err = getUltimateCause((Throwable)o);
                            o = getUltimateMessage(err);
                        }
                        deepToString(o,out,80);
                    }
                out.append('\n');
                if(err!=null) {
                    PrintWriter pw = new PrintWriter(out);
                    err.printStackTrace(pw);
                    pw.flush();
                }
                else out.flush();
            } catch (Throwable t) {
                t.printStackTrace(System.err);
            }
        }
        public void logTo(OutputStream dest) {
            doClose = dest!=System.out;
            out = new BufferedWriter(new OutputStreamWriter(dest),200);
        }
        private final CopyOnWriteArraySet<Consumer<Entry>> watchers = new CopyOnWriteArraySet<>();
        @Override
        public void addWatcher(Consumer<Entry> lw) {
            if(lw!=null) watchers.add(lw);
        }
        @Override
        public void logTo(String dest) {
            System.out.println("Sending log to "+dest);
            if(doClose) close(out);
            if(isEmpty(dest) || "stdout".equals(dest) || "stdio".equals(dest))
                logTo(System.out);
            else if("stderr".equals(dest))
                logTo(System.err);
            else {
                try {
                    logTo(new FileOutputStream(dest,true));
                } catch (FileNotFoundException ex) {
                    logTo(System.out);
                    error("Couldn't write to log file", ex);
                }
            }
        }
    }
}
