/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.config;

import com.aws.iot.evergreen.util.*;

import static com.aws.iot.evergreen.util.Utils.*;
import java.io.*;
import java.nio.file.*;

public class ConfigurationWriter implements Closeable, Subscriber {
    private final Writer out;
    private final Configuration conf;
    private  boolean flushImmediately;
    public static void dump(Configuration c, Path file) {
        try (ConfigurationWriter cs = new ConfigurationWriter(c, CommitableWriter.abandonOnClose(file))) {
            cs.writeAll();
            cs.close();
        } catch (IOException ex) {
            c.root.context.getLog().error("ConfigurationWriter.dump",ex);
        }
    }
    @SuppressWarnings("LeakingThisInConstructor")
    ConfigurationWriter(Configuration c, Writer o) {
        out = o;
        conf = c;
        conf.getRoot().listen(this);
    }
    ConfigurationWriter(Configuration c, Path p) throws IOException {
        this(c, CommitableWriter.abandonOnClose(p));
    }
    public static ConfigurationWriter logTransactionsTo(Configuration c, Path p) throws IOException {
        return new ConfigurationWriter(c,
                Files.newBufferedWriter(p,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND,
                        StandardOpenOption.DSYNC,
                        StandardOpenOption.CREATE));
    }
    @Override
    public void close() throws IOException {
        try {
            conf.getRoot().remove(this);
            if (out instanceof Commitable)
                ((Commitable) out).commit();
        } catch (Throwable ioe) {
        }
        Utils.close(out);
    }
    public ConfigurationWriter flushImmediately(boolean fl) {
        flushImmediately = fl;
        if(fl) flush(out);
        return this;
    }
    @Override
    public synchronized void published(WhatHappened what, Topic n) {
        if (what == WhatHappened.childChanged)
            try {
                if(n.name.startsWith("_")) return;  // Don't log entries whose name starts in '_'
                appendLong(n.getModtime(), out);
                out.append(',');
                n.appendNameTo(out);
                out.append(',');
                Coerce.toParseableString(n.getOnce(), out);
                out.append('\n');
            } catch (IOException ex) {
                n.context.getLog().error("ConfigurationWriter.published",n.getFullName(),ex);
            }
        if(flushImmediately) flush(out);
    }
    public void writeAll() { //TODO double check this
        conf.deepForEachTopic(n -> published(WhatHappened.childChanged, n));
    }
}
