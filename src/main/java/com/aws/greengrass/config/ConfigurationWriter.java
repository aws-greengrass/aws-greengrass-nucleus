/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.config;

import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Commitable;
import com.aws.greengrass.util.CommitableWriter;
import com.aws.greengrass.util.Utils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Setter;

import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.aws.greengrass.util.Utils.flush;

public class ConfigurationWriter implements Closeable, ChildChanged {
    public static final String TRUNCATE_TLOG_EVENT = "truncate-tlog";
    private Writer out;
    private final Path outPath;
    private final Configuration conf;
    @SuppressFBWarnings(value = "IS2_INCONSISTENT_SYNC", justification = "No need for flush immediately to be sync")
    private boolean flushImmediately;
    private final AtomicBoolean closed = new AtomicBoolean();

    private boolean autoTruncate = false;
    private long count;       // bytes written so far
    private long maxCount;    // max size of log file before truncation
    @Setter
    private Context context;

    private static final Logger logger = LogManager.getLogger(ConfigurationWriter.class);
    private static final long DEFAULT_MAX_TLOG_SIZE = 10_000_000L;

    @SuppressWarnings("LeakingThisInConstructor")
    ConfigurationWriter(Configuration c, Writer o, Path op) {
        out = o;
        outPath = op;
        conf = c;
        conf.getRoot().addWatcher(this);
    }

    ConfigurationWriter(Configuration c, Path p) throws IOException {
        this(c, CommitableWriter.abandonOnClose(p), p);
    }

    /**
     * Dump the configuration into a file given by the path.
     *
     * @param c configuration to write out
     * @param p path to write to
     */
    public static void dump(Configuration c, Path p) {
        try (ConfigurationWriter cs = new ConfigurationWriter(c, p)) {
            cs.writeAll();
            logger.atInfo().setEventType("config-dump").addKeyValue("path", p).log();
        } catch (IOException ex) {
            logger.atError().setEventType("config-dump-error").setCause(ex).addKeyValue("path", p).log();
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
        return new ConfigurationWriter(c, newTlogWriter(p), p);
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
     * Set to enable auto truncate with default max tlog size.
     * @param context a Context to provide access to kernel
     * @return this
     * @throws IOException I/O error querying current log file size
     */
    public synchronized ConfigurationWriter withAutoTruncate(Context context) throws IOException {
        autoTruncate = true;
        setContext(context);
        if (Files.exists(outPath)) {
            count = Files.size(outPath);
        } else {
            count = 0;
        }
        maxCount = DEFAULT_MAX_TLOG_SIZE;
        return this;
    }

    /**
     * Set the max size of log file before truncation.
     *
     * @param bytes max size in bytes
     * @return this
     */
    public synchronized ConfigurationWriter withMaxFileSize(long bytes) {
        maxCount = bytes;
        return this;
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
            count += Coerce.appendParseableString(tlogline, out);
        } catch (IOException ex) {
            logger.atError().setEventType("config-dump-error").addKeyValue("configNode", n.getFullName()).setCause(ex)
                    .log();
        }
        if (flushImmediately) {
            flush(out);
        }
        if (autoTruncate && count > maxCount) {
            truncateTlog();
        }
    }

    public void writeAll() { // GG_NEEDS_REVIEW: TODO double check this
        conf.deepForEachTopic(n -> childChanged(WhatHappened.childChanged, n));
    }

    /**
     * Create a new Writer for writing to a tlog file.
     *
     * @param outputPath path to tlog file
     * @return a new writer
     * @throws IOException if I/O error creating output file or writer
     */
    private static Writer newTlogWriter(Path outputPath) throws IOException {
        return Files.newBufferedWriter(outputPath, StandardOpenOption.WRITE, StandardOpenOption.APPEND,
                StandardOpenOption.DSYNC, StandardOpenOption.CREATE);
    }

    /**
     * Discard current tlog. Start a new tlog with the current kernel configs.
     * Old tlog will be renamed to tlog.old
     * No need to synchronize because only calling from synchronized childChanged
     */
    private synchronized void truncateTlog() {
        logger.atInfo(TRUNCATE_TLOG_EVENT).log("started");
        // TODO: handle errors
        Throwable error = context.runOnPublishQueueAndWait(() -> {
            // close existing writer
            flush(out);
            if (out instanceof Commitable) {
                ((Commitable) out).commit();
            }
            Utils.close(out);
            logger.atDebug(TRUNCATE_TLOG_EVENT).log("existing tlog writer closed");
            // move old tlog
            Path oldTlogPath = outPath.resolveSibling(outPath.getFileName() + ".old");
            Files.move(outPath, oldTlogPath, StandardCopyOption.REPLACE_EXISTING);
            logger.atDebug(TRUNCATE_TLOG_EVENT).log("existing tlog renamed to " + oldTlogPath);
            // write current state to new tlog
            context.get(Kernel.class).writeEffectiveConfigAsTransactionLog(outPath);
            logger.atDebug(TRUNCATE_TLOG_EVENT).log("current effective config written to " + outPath);
            // open writer to new tlog
            out = newTlogWriter(outPath);
            count = Files.size(outPath);
            logger.atInfo(TRUNCATE_TLOG_EVENT).log("complete");
        });
        if (error != null) {
            logger.atError(TRUNCATE_TLOG_EVENT, error).log();
        }
    }
}
