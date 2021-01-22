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

import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.aws.greengrass.util.Utils.flush;

public class ConfigurationWriter implements Closeable, ChildChanged {
    private static final String TRUNCATE_TLOG_EVENT = "truncate-tlog";
    private static final long DEFAULT_MAX_TLOG_ENTRIES = 15_000;

    private Writer out;
    private final Path tlogOutputPath;
    private final Configuration conf;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean truncateQueued = new AtomicBoolean();
    private final AtomicLong count = new AtomicLong(0);  // entries written so far
    @SuppressFBWarnings(value = "IS2_INCONSISTENT_SYNC", justification = "No need for flush immediately to be sync")
    private boolean flushImmediately;
    @SuppressFBWarnings(value = "IS2_INCONSISTENT_SYNC", justification = "No need to sync config variable")
    private boolean autoTruncate = false;
    @SuppressFBWarnings(value = "IS2_INCONSISTENT_SYNC", justification = "No need to sync config variable")
    private long maxCount = DEFAULT_MAX_TLOG_ENTRIES;  // max before truncation
    private long retryCount = 0;  // retry truncate at this count after error occurred
    private Context context;

    private static final Logger logger = LogManager.getLogger(ConfigurationWriter.class);

    @SuppressWarnings("LeakingThisInConstructor")
    ConfigurationWriter(Configuration c, Writer o, Path p) {
        out = o;
        tlogOutputPath = p;
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
     * @throws IOException if writing fails
     */
    public static void dump(Configuration c, Path p) throws IOException {
        try (ConfigurationWriter cs = new ConfigurationWriter(c, p)) {
            cs.writeAll();
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
     * Set to enable auto truncate.
     *
     * @param context a Context to provide access to kernel
     * @return this
     */
    public synchronized ConfigurationWriter withAutoTruncate(Context context) {
        this.context = context;
        autoTruncate = true;
        return this;
    }

    /**
     * Set max new entries of tlog written before truncation.
     *
     * @param numEntries max number of entries
     * @return this
     */
    public ConfigurationWriter withMaxEntries(long numEntries) {
        maxCount = numEntries;
        return this;
    }

    /**
     * Set ConfigurationWriter to flush immediately.
     *
     * @param fl true if the writer should flush immediately
     * @return this
     */
    public synchronized ConfigurationWriter flushImmediately(boolean fl) {
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
        } else if (what == WhatHappened.interiorAdded) {
            tlogline = new Tlogline(n.getModtime(), n.path(), WhatHappened.interiorAdded, null);
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
        long currCount = count.incrementAndGet();
        if (autoTruncate && currCount > maxCount && currCount > retryCount
                && truncateQueued.compareAndSet(false, true)) {
            // childChanged runs on publish thread already. can only queue a task without blocking
            context.runOnPublishQueue(this::truncateTlog);
            logger.atDebug(TRUNCATE_TLOG_EVENT).log("queued");
        }
    }

    public void writeAll() {
        conf.deepForEachTopic(n -> childChanged(WhatHappened.childChanged, n));
        conf.forEachChildlessTopics(t -> childChanged(WhatHappened.interiorAdded, t));
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
     */
    private synchronized void truncateTlog() {
        logger.atDebug(TRUNCATE_TLOG_EVENT).log("started");
        truncateQueued.set(false);
        Path oldTlogPath = tlogOutputPath.resolveSibling(tlogOutputPath.getFileName() + ".old");
        // close existing writer
        flush(out);
        if (out instanceof Commitable) {
            ((Commitable) out).commit();
        }
        logger.atDebug(TRUNCATE_TLOG_EVENT).log("existing tlog writer closed");
        // move old tlog
        try {
            Files.move(tlogOutputPath, oldTlogPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.atError(TRUNCATE_TLOG_EVENT, e).log("failed to rename existing tlog");
            // recover: reopen writer to old tlog
            try {
                out = newTlogWriter(tlogOutputPath);
            } catch (IOException innerException) {
                logger.atError(TRUNCATE_TLOG_EVENT, innerException).log("failed to recover");
                return;
            }
            setTruncateRetryCount();
            logger.atWarn(TRUNCATE_TLOG_EVENT).log("recovered and will retry later");
            return;
        }
        logger.atDebug(TRUNCATE_TLOG_EVENT).log("existing tlog renamed to " + oldTlogPath);
        // write current state to new tlog
        try {
            context.get(Kernel.class).writeEffectiveConfigAsTransactionLog(tlogOutputPath);
        } catch (IOException e) {
            logger.atError(TRUNCATE_TLOG_EVENT, e).log("failed to persist Nucleus config");
            // recover: undo renaming and keep using old tlog
            try {
                Files.move(oldTlogPath, tlogOutputPath, StandardCopyOption.REPLACE_EXISTING);
                out = newTlogWriter(tlogOutputPath);
            } catch (IOException innerException) {
                logger.atError(TRUNCATE_TLOG_EVENT, innerException).log("failed to recover");
                return;
            }
            setTruncateRetryCount();
            logger.atWarn(TRUNCATE_TLOG_EVENT).log("recovered and will retry later");
            return;
        }
        logger.atDebug(TRUNCATE_TLOG_EVENT).log("current effective config written to " + tlogOutputPath);
        // open writer to new tlog
        try {
            out = newTlogWriter(tlogOutputPath);
        } catch (IOException e) {
            logger.atError(TRUNCATE_TLOG_EVENT, e).log("failed to open writer");
            return;
        }
        logger.atDebug(TRUNCATE_TLOG_EVENT).log("writer rotated");
        count.set(0);
        retryCount = 0;
        try {
            Files.deleteIfExists(oldTlogPath);
        } catch (IOException e) {
            logger.atError(TRUNCATE_TLOG_EVENT).setCause(e).log("failed to delete old tlog");
        }
        logger.atInfo(TRUNCATE_TLOG_EVENT).log("completed successfully");
    }

    private synchronized void setTruncateRetryCount() {
        retryCount = count.get() + maxCount / 2;
    }

    /**
     * Immediately truncate the tlog.
     */
    public synchronized void truncateNow() {
        if (truncateQueued.compareAndSet(false, true)) {
            logger.atInfo(TRUNCATE_TLOG_EVENT).log("queued immediate truncation");
            context.runOnPublishQueue(this::truncateTlog);
        }
    }
}
