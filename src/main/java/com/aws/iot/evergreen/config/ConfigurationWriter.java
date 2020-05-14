/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.config;

import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.Commitable;
import com.aws.iot.evergreen.util.CommitableWriter;
import com.aws.iot.evergreen.util.Utils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static com.aws.iot.evergreen.util.Utils.appendLong;
import static com.aws.iot.evergreen.util.Utils.flush;

public class ConfigurationWriter implements Closeable, ChildChanged {
    private final Writer out;
    private final Configuration conf;
    @SuppressFBWarnings(value = "IS2_INCONSISTENT_SYNC", justification = "No need for flush immediately to be sync")
    private boolean flushImmediately;

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
    public void close() {
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
        if (what == WhatHappened.childChanged && n instanceof Topic) {
            Topic t = (Topic) n;
            try {
                if (n.getName().startsWith("_")) {
                    return;  // Don't log entries whose name starts in '_'
                }
                appendLong(t.getModtime(), out);
                out.append(',');
                n.appendNameTo(out);
                out.append(',');
                Coerce.appendParseableString(t.getOnce(), out);
                out.append('\n');
            } catch (IOException ex) {
                logger.atError().setEventType("config-dump-error").addKeyValue("configNode", n.getFullName())
                        .setCause(ex).log();
            }
        }
        if (flushImmediately) {
            flush(out);
        }
    }

    public void writeAll() { //TODO double check this
        conf.deepForEachTopic(n -> childChanged(WhatHappened.childChanged, n));
    }
}
