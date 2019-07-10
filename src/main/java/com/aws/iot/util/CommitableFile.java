/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */


package com.aws.iot.util;

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

    /** Creates a new instance of SafeFileOutputStream */
    private CommitableFile(Path n, Path b, Path t) throws IOException {
        super(n.toFile());
        newVersion = n;
        target = t;
        backup = b;
    }
    public static CommitableFile of(Path t) throws IOException {
        Path base = t.getFileName();
        Path n = t.resolveSibling(t+"+");
        Path b = t.resolveSibling(t+"~");
        Files.deleteIfExists(n);
        try {
            return new CommitableFile(n, b, t);
        } catch(IOException ioe) {
            Files.createDirectories(n.getParent());
            return new CommitableFile(n, b, t);
        }
    }
    /** Close and discard the file.  The original file remains untouched. */
    @Override
    public void close() throws IOException {
        try {
            super.close();
        } catch (IOException ex) { }
        Files.deleteIfExists(newVersion);
    }
    /** Close the file and commit the new version.  The old version becomes a backup */
    @SuppressWarnings("ConvertToTryWithResources")
    public void commit() throws IOException {
        if(newVersion==null) return;
        super.close();
        if(Files.exists(newVersion)) {
//            Files.deleteIfExists(backup);
            move(target,backup);
            move(newVersion,target);
        }
    }
    private void move(Path from, Path to) {
        try {
            if(Files.exists(from))
                Files.move(from,to,ATOMIC_MOVE);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
}
