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
import java.util.concurrent.atomic.AtomicLong;

import static com.aws.greengrass.util.Utils.flush;

public class ConfigurationWriter implements Closeable, ChildChanged {
    private static final String TRUNCATE_TLOG_EVENT = "truncate-tlog";
    private static final long DEFAULT_MAX_TLOG_ENTRIES = 15_000;

    private Writer out;
    private final Path tlogOutputPath;
    private final Configuration conf;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong count = new AtomicLong(0);  // entries written so far
    @SuppressFBWarnings(value = "IS2_INCONSISTENT_SYNC", justification = "No need for flush immediately to be sync")
    private boolean flushImmediately;
    @SuppressFBWarnings(value = "IS2_INCONSISTENT_SYNC", justification = "No need to sync config variable")
    private boolean autoTruncate = false;
    @SuppressFBWarnings(value = "IS2_INCONSISTENT_SYNC", justification = "No need to sync config variable")
    private long maxCount = DEFAULT_MAX_TLOG_ENTRIES;  // max before truncation
    private long retryCount = 0;  // retry truncate at this count after error occurred
    @Setter
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
     */
    public static void dump(Configuration c, Path p) {
        try (ConfigurationWriter cs = new ConfigurationWriter(c, p)) {
            cs.writeAll();
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
     * Set to enable auto truncate.
     *
     * @param context a Context to provide access to kernel
     * @return this
     */
    public ConfigurationWriter withAutoTruncate(Context context) {
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
        long currCount = count.incrementAndGet();
        if (autoTruncate && currCount > maxCount && currCount > retryCount) {
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
     */
    private synchronized void truncateTlog() {
        Path oldTlogPath = tlogOutputPath.resolveSibling(tlogOutputPath.getFileName() + ".old");
        Throwable error = context.runOnPublishQueueAndWait(() -> {
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
                out = newTlogWriter(tlogOutputPath);
                setTruncateRetryCount();
                logger.atWarn(TRUNCATE_TLOG_EVENT, e).log("recovered and will retry later");
                return;
            }
            logger.atDebug(TRUNCATE_TLOG_EVENT).log("existing tlog renamed to " + oldTlogPath);
            // write current state to new tlog
            try {
                context.get(Kernel.class).writeEffectiveConfigAsTransactionLog(tlogOutputPath);
            } catch (IOException e) {
                logger.atError(TRUNCATE_TLOG_EVENT, e).log("failed to persist kernel config");
                // recover: undo renaming and keep using old tlog
                Files.move(oldTlogPath, tlogOutputPath, StandardCopyOption.REPLACE_EXISTING);
                out = newTlogWriter(tlogOutputPath);
                setTruncateRetryCount();
                logger.atWarn(TRUNCATE_TLOG_EVENT, e).log("recovered and will retry later");
                return;
            }
            logger.atDebug(TRUNCATE_TLOG_EVENT).log("current effective config written to " + tlogOutputPath);
            // open writer to new tlog
            out = newTlogWriter(tlogOutputPath);
            logger.atDebug(TRUNCATE_TLOG_EVENT).log("writer rotated");
        });
        if (error != null) {
            logger.atError(TRUNCATE_TLOG_EVENT, error).log("non-recoverable error occurred. truncate tlog failed");
            return;
        }
        count.set(0);
        retryCount = 0;
        try {
            Files.deleteIfExists(oldTlogPath);
        } catch (IOException e) {
            logger.atError(TRUNCATE_TLOG_EVENT).setCause(e).log("failed to delete old tlog");
        }
        logger.atInfo(TRUNCATE_TLOG_EVENT).log("complete");
    }

    private synchronized void setTruncateRetryCount() {
        retryCount = count.get() + maxCount / 2;
    }
}
