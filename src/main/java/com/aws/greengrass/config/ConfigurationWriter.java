/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.config;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Commitable;
import com.aws.greengrass.util.CommitableWriter;
import com.aws.greengrass.util.Utils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.aws.greengrass.util.Utils.flush;

public class ConfigurationWriter implements Closeable, ChildChanged {
    private final Writer out;
    private final Configuration conf;
    @SuppressFBWarnings(value = "IS2_INCONSISTENT_SYNC", justification = "No need for flush immediately to be sync")
    private boolean flushImmediately;
    private final AtomicBoolean closed = new AtomicBoolean();

    private static final Logger logger = LogManager.getLogger(ConfigurationWriter.class);

    @SuppressWarnings("LeakingThisInConstructor")
    ConfigurationWriter(Configuration c, Writer o) {
        out = o;
        conf = c;
        conf.getRoot().addWatcher(this);
    }

    ConfigurationWriter(Configuration c, Path p) throws IOException {
        this(c, CommitableWriter.abandonOnClose(p));
    }

    /**
     * Dump the configuration into a file given by the path.
     *
     * @param c    configuration to write out
     * @param file path to write to
     */
    public static void dump(Configuration c, Path file) {
        try (ConfigurationWriter cs = new ConfigurationWriter(c, CommitableWriter.abandonOnClose(file))) {
            cs.writeAll();
            logger.atInfo().setEventType("config-dump").addKeyValue("path", file).log();
        } catch (IOException ex) {
            logger.atError().setEventType("config-dump-error").setCause(ex).addKeyValue("path", file).log();
        }
    }

    /**
     * Create a ConfigurationWriter from a given configuration and file path.
     *
     * @param c initial configuration
     * @param p path to save the configuration
     * @return ConfigurationWriter
     * @throws IOException if creating the configuration file fails
     */
    public static ConfigurationWriter logTransactionsTo(Configuration c, Path p) throws IOException {
        return new ConfigurationWriter(c,
                Files.newBufferedWriter(p, StandardOpenOption.WRITE, StandardOpenOption.APPEND,
                        StandardOpenOption.DSYNC, StandardOpenOption.CREATE));
    }

    @Override
    public synchronized void close() {
        closed.set(true);
        conf.getRoot().remove(this);
        if (out instanceof Commitable) {
            ((Commitable) out).commit();
        }
        Utils.close(out);
    }

    /**
     * Set ConfigurationWriter to flush immediately.
     *
     * @param fl true if the writer should flush immediately
     * @return this
     */
    public ConfigurationWriter flushImmediately(boolean fl) {
        flushImmediately = fl;
        if (fl) {
            flush(out);
        }
        return this;
    }

    @Override
    public synchronized void childChanged(WhatHappened what, Node n) {
        if (closed.get()) {
            return;
        }
        if (n == null) {
            return;
        }
        for (int i = 0; i < n.path().length; i++) {
            if (n.path()[i].startsWith("_")) {
                return; // Don't log entries whose name starts in '_'
            }
        }

        Tlogline tlogline;
        if (what == WhatHappened.childChanged && n instanceof Topic) {
            Topic t = (Topic) n;

            tlogline = new Tlogline(t.getModtime(), t.path(), WhatHappened.changed, t.getOnce());
        } else if (what == WhatHappened.childRemoved) {
            tlogline = new Tlogline(n.getModtime(), n.path(), WhatHappened.removed, null);
        } else if (what == WhatHappened.timestampUpdated) {
            tlogline = new Tlogline(n.getModtime(), n.path(), WhatHappened.timestampUpdated, null);
        } else {
            return;
        }

        try {
            Coerce.appendParseableString(tlogline, out);
        } catch (IOException ex) {
            logger.atError().setEventType("config-dump-error").addKeyValue("configNode", n.getFullName()).setCause(ex)
                    .log();
        }
        if (flushImmediately) {
            flush(out);
        }
    }

    public void writeAll() { // GG_NEEDS_REVIEW: TODO double check this
        conf.deepForEachTopic(n -> childChanged(WhatHappened.childChanged, n));
    }
}
