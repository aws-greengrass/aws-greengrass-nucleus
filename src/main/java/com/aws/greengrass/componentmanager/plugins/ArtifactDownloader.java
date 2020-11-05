/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins;

import com.aws.greengrass.componentmanager.exceptions.ArtifactChecksumMismatchException;
import com.aws.greengrass.componentmanager.exceptions.InvalidArtifactUriException;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.CrashableSupplier;
import com.aws.greengrass.util.Pair;
import com.aws.greengrass.util.Utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public abstract class ArtifactDownloader {
    private static final int DOWNLOAD_BUFFER_SIZE = 1024;
    private static final int MAX_RETRY_INTERVAL_MILLI = 30_000;
    private static final int INIT_RETRY_INTERVAL_MILLI = 1000;
    static final String ARTIFACT_DOWNLOAD_EXCEPTION_FMT =
            "Failed to download artifact %s for component %s-%s, reason: ";
    public static final String ARTIFACT_URI_LOG_KEY = "artifactUri";
    public static final String COMPONENT_IDENTIFIER_LOG_KEY = "componentIdentifier";
    protected static final String HTTP_RANGE_HEADER_FORMAT = "bytes=%d-%d";
    protected static final String HTTP_RANGE_HEADER_KEY = "Range";

    protected final Logger logger = LogManager.getLogger(this.getClass());
    protected final ComponentIdentifier identifier;
    protected final ComponentArtifact artifact;
    protected final Path artifactDir;

    protected ArtifactDownloader(ComponentIdentifier identifier, ComponentArtifact artifact,
                              Path artifactDir) {
        this.identifier = identifier;
        this.artifact = artifact;
        this.artifactDir = artifactDir;
        this.logger.addDefaultKeyValue(ARTIFACT_URI_LOG_KEY, artifact.getArtifactUri())
                .addDefaultKeyValue(COMPONENT_IDENTIFIER_LOG_KEY, identifier.getName());
    }

    /**
     * Download an artifact from remote.
     *
     * @return file handle of the downloaded file
     * @throws IOException if I/O error occurred in network/disk
     * @throws PackageDownloadException if error occurred in download process
     * @throws InvalidArtifactUriException if given artifact URI has error
     */
    public final File downloadToPath() throws PackageDownloadException, IOException {
        MessageDigest messageDigest;
        try {
            if (artifact.getAlgorithm() == null) {
                throw new ArtifactChecksumMismatchException(
                        getErrorString("Algorithm missing in downloading."));
            }
            messageDigest = MessageDigest.getInstance(artifact.getAlgorithm());
        } catch (NoSuchAlgorithmException e) {
            throw new ArtifactChecksumMismatchException(
                    getErrorString("Algorithm requested for artifact checksum is not supported"), e);
        }

        Path saveToPath = artifactDir.resolve(getLocalFileName());
        long artifactSize = getDownloadSize();
        long offset = 0;

        if (Files.exists(saveToPath)) {
            offset = Files.size(saveToPath);
            if (offset > artifactSize) {
                // shouldn't happen, since corrupted files are deleted every time.
                logger.atWarn().log("existing file corrupted. Removing and retry download.");
                Files.deleteIfExists(saveToPath);
                offset = 0;
            } else {
                // assume the file is from last download and try to continue download from this point.
                try (InputStream existingArtifact = Files.newInputStream(saveToPath)) {
                    byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];
                    int readBytes = existingArtifact.read(buffer);
                    while (readBytes > -1) {
                        messageDigest.update(buffer, 0, readBytes);
                        readBytes = existingArtifact.read(buffer);
                    }
                }

                if (offset == artifactSize) {
                    // shouldn't happen, since ComponentManager already had downloadRequired() check.
                    String digest = Base64.getEncoder().encodeToString(messageDigest.digest());
                    if (digest.equals(artifact.getChecksum())) {
                        logger.atDebug().log("Artifacts already downloaded");
                        return saveToPath.toFile();
                    }

                    // shouldn't happen, since corrupted files are deleted every time.
                    logger.atWarn().log("existing file corrupted. Removing and retry download.");
                    Files.deleteIfExists(saveToPath);
                    offset = 0;
                    messageDigest.reset();
                } else {
                    logger.atInfo().log("Existing partially downloaded file has size {}", offset);
                }
            }
        }

        try (OutputStream artifactFile = Files.newOutputStream(saveToPath,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
            downloadToFile(artifactFile, offset, artifactSize, messageDigest);
        } catch (IOException e) {
            throw new PackageDownloadException(getErrorString("Unable to write to open file stream"), e);
        }

        String digest = Base64.getEncoder().encodeToString(messageDigest.digest());
        if (!digest.equals(artifact.getChecksum())) {
            // Handle failure in integrity check, delete bad file then throw
            Files.deleteIfExists(saveToPath);
            throw new ArtifactChecksumMismatchException(
                    getErrorString("Integrity check for downloaded artifact failed"));
        }
        logger.atDebug().setEventType("download-artifact").log("Passed integrity check");
        return saveToPath.toFile();
    }

    private void downloadToFile(OutputStream artifactFile, long rangeStart, long rangeEnd, MessageDigest messageDigest)
            throws PackageDownloadException {
        InputStream artifactInputStream = null;
        Runnable cleanupRunnable = null;
        long offset = rangeStart;
        int retryInteraval = INIT_RETRY_INTERVAL_MILLI;
        while (true) {
            try {
                Pair<InputStream, Runnable> readInput = readWithRange(offset, rangeEnd - 1);
                artifactInputStream = readInput.getLeft();
                cleanupRunnable = readInput.getRight();

                byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];
                int readBytes = artifactInputStream.read(buffer);
                while (readBytes > -1) {
                    offset += readBytes;
                    // Compute digest as well as write to the file path
                    messageDigest.update(buffer, 0, readBytes);
                    try {
                        artifactFile.write(buffer, 0, readBytes);
                    } catch (IOException e) {
                        throw new PackageDownloadException(
                                getErrorString("Fail to write to file"), e);
                    }
                    // reset retryInterval
                    retryInteraval = INIT_RETRY_INTERVAL_MILLI;

                    readBytes = artifactInputStream.read(buffer);
                }
                if (offset >= rangeEnd) {
                    break;
                }
            } catch (IOException | RetryableException e) {
                logger.atWarn().setCause(e).log("Error in downloading artifact, wait to retry.");
                // backoff sleep retry
                try {
                    Thread.sleep(retryInteraval);
                    if (retryInteraval < MAX_RETRY_INTERVAL_MILLI) {
                        retryInteraval = retryInteraval * 2;
                    } else {
                        retryInteraval = MAX_RETRY_INTERVAL_MILLI;
                    }
                } catch (InterruptedException ie) {
                    logger.atInfo().log("Interrupted while waiting to retry download");
                    return;
                }
                continue;
            } finally {
                if (artifactInputStream != null) {
                    try {
                        artifactInputStream.close();
                    } catch (IOException e) {
                        logger.atWarn().setCause(e).log("Unable to close artifact download stream.");
                    }
                }
                if (cleanupRunnable != null) {
                    cleanupRunnable.run();
                }
            }
        }
    }

    /**
     * Read the partial data given range.
     *
     * @param start                                         Range start index. INCLUSIVE.
     * @param end                                           Range end index. INCLUSIVE.
     * @return {@literal Pair<InputStream, Runnable>}     Runnable is the cleanup task to run
     *                                                      after finishing reading from inputStream.
     * @throws IOException IOException                      ArtifactDownloader will retry on IOException.
     * @throws PackageDownloadException PackageDownloadException
     */
    protected abstract Pair<InputStream, Runnable> readWithRange(long start, long end)
            throws RetryableException, PackageDownloadException;

    /**
     * Checks whether it is necessary to download the artifact or the existing file suffices.
     *
     * @return true if download is necessary
     * @throws InvalidArtifactUriException if given artifact URI has error
     */
    public boolean downloadRequired() {
        try {
            String filename = getLocalFileName();
            return !artifactExistsAndChecksum(artifact, artifactDir.resolve(filename));
        } catch (PackageDownloadException e) {
            logger.atWarn().setCause(e).log();
            return true;
        }
    }

    /**
     * Get the artifact file.
     *
     * @return artifact file that was either downloaded or had been locally present
     * @throws InvalidArtifactUriException if provided info results in invalid URI
     * @throws PackageDownloadException if error encountered
     */
    public File getArtifactFile() throws PackageDownloadException {
        return artifactDir.resolve(getLocalFileName()).toFile();
    }

    /**
     * Get the download size of the artifact file.
     *
     * @return size of the artifact in bytes
     * @throws InvalidArtifactUriException if provided info results in invalid URI
     * @throws PackageDownloadException if error encountered
     */
    public final Long getDownloadSize() throws PackageDownloadException {
        return runRetry("get-download-size", this::getDownloadSizeNoRetry);
    }

    protected abstract Long getDownloadSizeNoRetry() throws PackageDownloadException, RetryableException;

    protected String getLocalFileName() throws PackageDownloadException {
        return runRetry("get-local-file-name", this::getLocalFileNameNoRetry);
    }

    protected abstract String getLocalFileNameNoRetry() throws PackageDownloadException, RetryableException;

    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.AvoidRethrowingException"})
    private <T> T runRetry(String taskDescription, CrashableSupplier<T, Exception> taskToRetry)
            throws PackageDownloadException {
        int retryInterval = INIT_RETRY_INTERVAL_MILLI;
        while (true) {
            try {
                return taskToRetry.apply();
            } catch (RetryableException e) {
                try {
                    Thread.sleep(retryInterval);
                    if (retryInterval < MAX_RETRY_INTERVAL_MILLI) {
                        retryInterval = retryInterval * 2;
                    } else {
                        retryInterval = MAX_RETRY_INTERVAL_MILLI;
                    }
                } catch (InterruptedException ie) {
                    logger.atInfo().log("Interrupted while waiting to retry " + taskDescription);
                    return null;
                }
            } catch (PackageDownloadException e) {
                throw e;
            } catch (Exception e) {
                throw new PackageDownloadException("Unexpected error in " + taskDescription, e);
            }
        }
    }

    protected String getErrorString(String reason) {
        return String.format(ARTIFACT_DOWNLOAD_EXCEPTION_FMT, artifact.getArtifactUri(),
                identifier.getName(), identifier.getVersion().toString()) + reason;
    }

    /**
     * Checks the given artifact file exists at given path and has the right checksum.
     *
     * @param artifact an artifact object
     * @param filePath path to the local artifact file
     * @return true if the file exists and has the right checksum
     * @throws PackageDownloadException if No local artifact found and recipe does not have required digest information
     */
    private static boolean artifactExistsAndChecksum(ComponentArtifact artifact, Path filePath)
            throws PackageDownloadException {
        // Local recipes don't have digest or algorithm and that's expected, in such case, use the
        // locally present artifact. On the other hand, recipes downloaded from cloud will always
        // have digest and algorithm
        if (Files.exists(filePath) && !recipeHasDigest(artifact)) {
            return true;
        } else if (!Files.exists(filePath)) {
            if (recipeHasDigest(artifact)) {
                return false;
            } else {
                throw new PackageDownloadException(
                        "No local artifact found and recipe does not have required digest information");
            }
        }

        // If the file already exists and has the right content, skip download
        try (InputStream existingArtifact = Files.newInputStream(filePath)) {
            MessageDigest messageDigest = MessageDigest.getInstance(artifact.getAlgorithm());
            byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];
            int readBytes = existingArtifact.read(buffer);
            while (readBytes > -1) {
                messageDigest.update(buffer, 0, readBytes);
                readBytes = existingArtifact.read(buffer);
            }
            String digest = Base64.getEncoder().encodeToString(messageDigest.digest());
            return digest.equals(artifact.getChecksum());

        } catch (IOException | NoSuchAlgorithmException e) {
            // If error in checking the existing content, attempt fresh download
            return false;
        }
    }

    private static boolean recipeHasDigest(ComponentArtifact artifact) {
        return !Utils.isEmpty(artifact.getAlgorithm()) && !Utils.isEmpty(artifact.getChecksum());
    }

    protected static class RetryableException extends Exception {
        static final long serialVersionUID = -3387516993124229948L;

        public RetryableException(String message) {
            super(message);
        }

        public RetryableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

