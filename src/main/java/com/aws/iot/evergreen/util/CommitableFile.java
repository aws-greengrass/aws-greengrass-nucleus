/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */


package com.aws.iot.evergreen.util;

import java.io.*;
import java.nio.file.*;
import static java.nio.file.StandardCopyOption.*;

/**
 * Equivalent to FileOutputStream except that it has to be committed in order to be
 * made permanent.  If it is closed or the process exits before the commit, the old
 * version of the file remains.
 */
public class CommitableFile extends FileOutputStream implements Commitable {
    private final Path newVersion;
    private final Path target;
    private final Path backup;
    private final boolean commitOnClose;
    private boolean closed;

    /** Creates a new instance of SafeFileOutputStream */
    private CommitableFile(Path n, Path b, Path t, boolean commitOnClose) throws IOException {
        super(n.toFile());
        newVersion = n;
        target = t;
        backup = b;
        this.commitOnClose = commitOnClose;
    }
    /** Strangely enough, abandonOnClose is usually the best choice: it interacts
     *  well with the implicit close() that happens in a try-with-resources where
     *  files are closed if an exception is tossed */
    public static CommitableFile abandonOnClose(Path t) throws IOException {
        return of(t, false);
    }
    public static CommitableFile commitOnClose(Path t) throws IOException {
        return of(t, true);
    }
    public static CommitableFile of(Path t, boolean commitOnClose) throws IOException {
        Path base = t.getFileName();
        Path n = t.resolveSibling(t+"+");
        Path b = t.resolveSibling(t+"~");
        Files.deleteIfExists(n);
        try {
            return new CommitableFile(n, b, t, commitOnClose);
        } catch(IOException ioe) {
            Files.createDirectories(n.getParent());
            return new CommitableFile(n, b, t, commitOnClose);
        }
    }
    @Override
    public void close() {
        if(!closed)
            if(commitOnClose) commit();
            else abandon();
    }
    /** Close and discard the file.  The original file remains untouched. */
    @Override
    public void abandon() {
        if(!closed) {
            try {
                super.close();
            } catch (IOException ex) { }
            try {
                Files.deleteIfExists(newVersion);
            } catch (IOException ex) { }
            closed = true;
        }
    }
    /** Close the file and commit the new version.  The old version becomes a backup */
    @SuppressWarnings("ConvertToTryWithResources")
    @Override
    public void commit() {
        if(!closed) {
            try {
                super.close();
            } catch (IOException ex) {}
            if(Files.exists(newVersion)) {
                move(target,backup);
                move(newVersion,target);
            }
            closed = true;
        }
    }
    private void move(Path from, Path to) {
        try {
            if(Files.exists(from))
                Files.move(from,to,ATOMIC_MOVE);
        } catch (IOException ex) {
            ex.printStackTrace(System.out);
        }
    }
}
