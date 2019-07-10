/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.config;

import com.aws.iot.util.Commitable;
import com.aws.iot.util.CommitableWriter;
import static com.aws.iot.util.Utils.*;
import com.aws.iot.config.Configuration.WhatHappened;
import static com.aws.iot.config.Configuration.WhatHappened.*;
import com.aws.iot.util.*;
import java.io.*;
import java.nio.file.*;
import java.util.logging.*;

public class ConfigurationWriter implements Closeable, Subscriber {
    private final Writer out;
    private final Configuration conf;
    public static void dump(Configuration c, Path file) {
        try (CommitableWriter out = CommitableWriter.of(file);
                ConfigurationWriter cs = new ConfigurationWriter(c, out)) {
            cs.writeAll();
        } catch (IOException ex) {
            Logger.getLogger(ConfigurationWriter.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    @SuppressWarnings("LeakingThisInConstructor")
    ConfigurationWriter(Configuration c, Writer o) {
        out = o;
        conf = c;
        conf.getRoot().listen(this);
    }
    ConfigurationWriter(Configuration c, Path p) throws IOException {
        this(c, CommitableWriter.of(p));
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
    @Override
    public synchronized void published(WhatHappened what, Object newValue, Object oldValue) {
        if (what == childChanged)
            try {
                Topic n = (Topic) newValue;
                appendLong(n.getModtime(), out);
                out.append(',');
                n.appendNameTo(out);
                out.append(',');
                com.aws.iot.util.Coerce.toParseableString(n.getOnce(), out);
                out.append('\n');
            } catch (IOException ex) {
                Logger.getLogger(ConfigurationWriter.class.getName()).log(Level.SEVERE, null, ex);
            }
    }
    public void writeAll() {
        conf.deepForEachTopic(n -> published(childChanged, n, null));
    }
}
