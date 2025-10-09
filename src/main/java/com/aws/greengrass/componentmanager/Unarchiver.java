/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.amazon.aws.iot.greengrass.component.common.Unarchive;
import com.aws.greengrass.util.Utils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Unarchiver {
    /**
     * Unarchive a given file into a given path.
     *
     * @param method type of archive to undo
     * @param toUnarchive the file to be unarchived
     * @param unarchiveInto the path to unarchive the file into
     * @throws IOException if unarchiving fails
     */
    public void unarchive(Unarchive method, File toUnarchive, Path unarchiveInto) throws IOException {
        if (method == Unarchive.ZIP) {
            unzip(toUnarchive, unarchiveInto.toFile());
        }
    }

    static void unzip(File zipFile, File destDir) throws IOException {
        try (ZipFile zf = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            // IOUtils uses a 4K buffer by default. Using 64K will make things go faster.
            byte[] buffer = new byte[1024 * 64];

            while (entries.hasMoreElements()) {
                ZipEntry zipEntry = entries.nextElement();
                File newFile = safeNewZipFile(destDir, zipEntry);
                if (zipEntry.isDirectory()) {
                    Utils.createPaths(newFile.toPath());
                } else {
                    Utils.createPaths(newFile.getParentFile().toPath());
                    // Only unarchive when the destination file doesn't exist or the file sizes don't match
                    if (!newFile.exists() || zipEntry.getSize() != newFile.length()) {
                        try (FileChannel fc = FileChannel.open(newFile.toPath(), StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                                InputStream is = zf.getInputStream(zipEntry);
                                OutputStream fos = Channels.newOutputStream(fc)) {
                            IOUtils.copyLarge(is, fos, buffer);
                            // calls sync() to force the file to disk to the best of our abilities
                            fc.force(true);
                        }
                    }
                }
            }
        } catch (IOException e) {
            // If anything fails, then clean up the files which we did extract (if any)
            Utils.deleteFileRecursively(destDir);
            throw e;
        }
    }

    private static File safeNewZipFile(File destinationDir, ZipEntry zipEntry) throws IOException {
        File destFile = new File(destinationDir, zipEntry.getName());

        String destDirPath = destinationDir.getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();

        if (!destFilePath.startsWith(destDirPath + File.separator)) {
            throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
        }

        return destFile;
    }
}
