/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CommitableReader {
    private static final Logger logger = LogManager.getLogger(CommitableReader.class);

    private final Path target;
    private final Path backup;

    private CommitableReader(Path target, Path backup) {
        this.target = target;
        this.backup = backup;
    }

    public static CommitableReader of(Path path) {
        return new CommitableReader(path, CommitableFile.getBackupFile(path));
    }

    /**
     * Read and validate the content of the given CommitableFile.
     *
     * @param validator CrashableFunction to read and validate file content
     * @throws IOException on I/O error
     */
    public void read(CrashableFunction<InputStream, Void, IOException> validator) throws IOException {
        try (InputStream t = Files.newInputStream(target)) {
            validator.apply(t);
        } catch (IOException e1) {
            if (!Files.exists(backup)) {
                throw e1;
            }
            logger.atWarn()
                    .kv("file", target)
                    .kv("backup", backup)
                    .log("Failed to read file. Try with backup next", e1);
            try (InputStream b = Files.newInputStream(backup)) {
                validator.apply(b);
            } catch (IOException e2) {
                e1.addSuppressed(e2);
                throw e1;
            }

            CommitableFile.move(backup, target);
            logger.atDebug().kv("file", target).kv("backup", backup).log("Revert file to backup version");
        }
    }
}
