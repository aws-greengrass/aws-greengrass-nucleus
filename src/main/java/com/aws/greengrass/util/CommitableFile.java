/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

/**
 * Equivalent to FileOutputStream except that it has to be committed in order to be
 * made permanent.  If it is closed or the process exits before the commit, the old
 * version of the file remains.
 */
@SuppressWarnings("PMD.AvoidFileStream")
public final class CommitableFile extends FileOutputStream implements Commitable {
    private static final Logger logger = LogManager.getLogger(CommitableFile.class);
    private final Path newVersion;
    private final Path target;
    private final Path backup;
    private final boolean commitOnClose;
    private boolean closed;

    /**
     * Creates a new instance of SafeFileOutputStream.
     */
    private CommitableFile(Path n, Path b, Path t, boolean commitOnClose) throws IOException {
        super(n.toFile());
        newVersion = n;
        target = t;
        backup = b;
        this.commitOnClose = commitOnClose;
    }

    /**
     * Strangely enough, abandonOnClose is usually the best choice: it interacts
     * well with the implicit close() that happens in a try-with-resources where
     * files are closed if an exception is tossed.
     *
     * @param t Path to write to
     * @throws IOException if writing fails
     */
    public static CommitableFile abandonOnClose(Path t) throws IOException {
        return of(t, false);
    }

    public static CommitableFile commitOnClose(Path t) throws IOException {
        return of(t, true);
    }

    /**
     * Get a CommitableFile for the given path.
     *
     * @param path          path of the new file.
     * @param commitOnClose true if the file should be automatically committed when closed.
     * @return CommitableFile.
     * @throws IOException if unable to create/delete the files.
     */
    public static CommitableFile of(Path path, boolean commitOnClose) throws IOException {
        Path n = path.resolveSibling(path.getFileName() + "+");
        Path b = path.resolveSibling(path.getFileName() + "~");
        Files.deleteIfExists(n);
        try {
            return new CommitableFile(n, b, path, commitOnClose);
        } catch (IOException ioe) {
            Path parent = n.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            return new CommitableFile(n, b, path, commitOnClose);
        }
    }

    @Override
    public void close() {
        if (!closed) {
            if (commitOnClose) {
                commit();
            } else {
                abandon();
            }
        }
    }

    /**
     * Close and discard the file.  The original file remains untouched.
     */
    @Override
    public void abandon() {
        if (!closed) {
            try {
                super.close();
            } catch (IOException ignored) {
            }
            try {
                Files.deleteIfExists(newVersion);
            } catch (IOException ignore) {
            }
            closed = true;
        }
    }

    /**
     * Close the file and commit the new version.  The old version becomes a backup
     */
    @SuppressWarnings("ConvertToTryWithResources")
    @Override
    public void commit() {
        if (!closed) {
            try {
                super.close();
            } catch (IOException ignore) {
            }
            if (Files.exists(newVersion)) {
                move(target, backup);
                move(newVersion, target);
            }
            closed = true;
        }
    }

    private void move(Path from, Path to) {
        try {
            if (Files.exists(from)) {
                Files.move(from, to, ATOMIC_MOVE);
            }
        } catch (IOException ex) {
            logger.atError().log("Error moving {} to {}", from, to, ex);
        }
    }
}
