/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins;

import com.aws.greengrass.componentmanager.exceptions.ArtifactChecksumMismatchException;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.CrashableSupplier;
import com.aws.greengrass.util.Utils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public abstract class ArtifactDownloader {
    @SuppressFBWarnings({"MS_SHOULD_BE_FINAL"})
    protected static int MAX_RETRY = 5;

    protected static final int MAX_RETRY_INTERVAL_MILLI = 30_000;
    protected static final int INIT_RETRY_INTERVAL_MILLI = 1000;
    private static final int DOWNLOAD_BUFFER_SIZE = 1024;
    static final String ARTIFACT_DOWNLOAD_EXCEPTION_FMT =
            "Failed to download artifact name: '%s' for component %s-%s, reason: ";
    public static final String ARTIFACT_URI_LOG_KEY = "artifactUri";
    public static final String COMPONENT_IDENTIFIER_LOG_KEY = "componentIdentifier";
    protected static final String HTTP_RANGE_HEADER_FORMAT = "bytes=%d-%d";
    protected static final String HTTP_RANGE_HEADER_KEY = "Range";

    protected final Logger logger;
    protected final ComponentIdentifier identifier;
    protected final ComponentArtifact artifact;
    protected final Path artifactDir;

    private Path saveToPath;

    protected ArtifactDownloader(ComponentIdentifier identifier, ComponentArtifact artifact,
                              Path artifactDir) {
        this.identifier = identifier;
        this.artifact = artifact;
        this.artifactDir = artifactDir;
        this.logger = LogManager.getLogger(this.getClass()).createChild();
        this.logger.addDefaultKeyValue(ARTIFACT_URI_LOG_KEY, artifact.getArtifactUri())
                .addDefaultKeyValue(COMPONENT_IDENTIFIER_LOG_KEY, identifier.getName());
    }

    /**
     * Download an artifact from remote.
     *
     * @return file handle of the downloaded file
     * @throws IOException if I/O error occurred in network/disk
     * @throws InterruptedException if interrupted in downloading
     * @throws PackageDownloadException if error occurred in download process
     * @throws ArtifactChecksumMismatchException if given artifact checksum algorithm isn't supported.
     */
    public final File downloadToPath() throws PackageDownloadException, IOException, InterruptedException {
        MessageDigest messageDigest;
        try {
            if (artifact.getAlgorithm() == null) {
                throw new ArtifactChecksumMismatchException(
                        getErrorString("Algorithm missing from artifact."));
            }
            messageDigest = MessageDigest.getInstance(artifact.getAlgorithm());
        } catch (NoSuchAlgorithmException e) {
            throw new ArtifactChecksumMismatchException(
                    getErrorString("Algorithm requested for artifact checksum is not supported"), e);
        }

        saveToPath = artifactDir.resolve(getArtifactFilename());
        long artifactSize = getDownloadSize();
        final AtomicLong offset = new AtomicLong(0);

        // If there are partially downloaded artifact existing on device
        if (Files.exists(saveToPath)) {
            if (Files.size(saveToPath) > artifactSize) {
                // Existing file is corrupted, it's larger than defined in artifact.
                // Normally shouldn't happen, corrupted files are deleted every time.
                logger.atError().log("existing file corrupted. Expected size: {}, Actual size: {}."
                        + " Removing and retrying download.", artifactSize, Files.size(saveToPath));
                Files.deleteIfExists(saveToPath);
            } else {
                offset.set(Files.size(saveToPath));
                updateDigestFromFile(saveToPath, messageDigest);
            }
        }

        return runWithRetry("download-artifact", MAX_RETRY,
                Collections.singletonList(ArtifactChecksumMismatchException.class),
                () -> {
                    while (offset.get() < artifactSize) {
                        long downloadedBytes = download(offset.get(), artifactSize - 1, messageDigest);
                        offset.addAndGet(downloadedBytes);
                    }

                    String digest = Base64.getEncoder().encodeToString(messageDigest.digest());
                    if (!digest.equals(artifact.getChecksum())) {
                        // Handle failure in integrity check, delete bad file then throw
                        Files.deleteIfExists(saveToPath);
                        offset.set(0);
                        messageDigest.reset();
                        throw new ArtifactChecksumMismatchException("Integrity check for downloaded artifact failed. "
                                + "Probably due to file corruption.");
                    }
                    logger.atDebug().setEventType("download-artifact").log("Passed integrity check");
                    return saveToPath.toFile();
                });
    }

    /**
     * Internal helper method to download from input stream.
     * If IOException is thrown during the process, the method will return actual number of bytes downloaded.
     * Supposed to be invoked in `protected abstract long download(long rangeStart, long rangeEnd)`
     * @param inputStream inputStream to download from.
     * @param messageDigest messageDigest to update.
     * @return number of bytes downloaded.
     */
    protected long download(InputStream inputStream, MessageDigest messageDigest) throws PackageDownloadException {
        long totalReadBytes = 0;
        try (OutputStream artifactFile = Files.newOutputStream(saveToPath,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];
            int readBytes = inputStream.read(buffer);
            while (readBytes > -1) {
                // Compute digest as well as write to the file path
                try {
                    artifactFile.write(buffer, 0, readBytes);
                } catch (IOException e) {
                    throw new PackageDownloadException(getErrorString("Error writing artifact."), e);
                }

                messageDigest.update(buffer, 0, readBytes);
                totalReadBytes += readBytes;
                readBytes = inputStream.read(buffer);
            }
            return totalReadBytes;
        } catch (IOException e) {
            logger.atWarn().setCause(e).log("Unable to read from input stream. "
                    + "Return the actual number of bytes read.");
            return totalReadBytes;
        }
    }

    /**
     * Internal method invoked in downloadToFile().
     *
     * @param rangeStart    Range start index. INCLUSIVE.
     * @param rangeEnd      Range end index. INCLUSIVE.
     * @param messageDigest messageDigest to update.
     * @return  number of bytes downloaded.
     * @throws PackageDownloadException PackageDownloadException
     */
    protected abstract long download(long rangeStart, long rangeEnd, MessageDigest messageDigest)
            throws PackageDownloadException, InterruptedException;

    /**
     * Checks whether it is necessary to download the artifact or the existing file suffices.
     *
     * @return true if download is necessary
     * @throws InterruptedException if interrupted in downloading
     * @throws PackageDownloadException if error encountered
     */
    public boolean downloadRequired() throws PackageDownloadException, InterruptedException {
        try {
            String filename = getArtifactFilename();
            return !artifactExistsAndChecksum(artifact, artifactDir.resolve(filename));
        } catch (PackageDownloadException e) {
            logger.atWarn().setCause(e).log("Error in getting artifact file name.");
            return true;
        }
    }

    /**
     * Get the artifact file.
     *
     * @return artifact file that was either downloaded or had been locally present
     * @throws InterruptedException if interrupted in downloading
     * @throws PackageDownloadException if error encountered
     */
    public File getArtifactFile() throws PackageDownloadException, InterruptedException {
        return artifactDir.resolve(getArtifactFilename()).toFile();
    }

    /**
     * Get the download size of the artifact file.
     *
     * @return size of the artifact in bytes
     * @throws InterruptedException if interrupted in downloading
     * @throws PackageDownloadException if error encountered
     */
    public abstract Long getDownloadSize() throws PackageDownloadException, InterruptedException;

    protected abstract String getArtifactFilename() throws PackageDownloadException, InterruptedException;

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
    protected static boolean artifactExistsAndChecksum(ComponentArtifact artifact, Path filePath)
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
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(artifact.getAlgorithm());
            updateDigestFromFile(filePath, messageDigest);
            String digest = Base64.getEncoder().encodeToString(messageDigest.digest());
            return digest.equals(artifact.getChecksum());
        } catch (IOException | NoSuchAlgorithmException e) {
            return false;
        }
    }

    protected <T> T runWithRetry(String taskDescription, int maxRetry,
                                 CrashableSupplier<T, Exception> taskToRetry)
            throws PackageDownloadException, InterruptedException {
        return runWithRetry(taskDescription, maxRetry, Collections.singletonList(IOException.class), taskToRetry);
    }

    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.AvoidRethrowingException",
            "PMD.AvoidInstanceofChecksInCatchClause", "PMD.PreserveStackTrace"})
    protected <T> T runWithRetry(String taskDescription, int maxRetry, List<Class> retryableExceptions,
                                 CrashableSupplier<T, Exception> taskToRetry)
            throws PackageDownloadException, InterruptedException {
        int retryInterval = INIT_RETRY_INTERVAL_MILLI;
        int retry = 0;
        Exception lastRetryableException = null;
        while (retry < maxRetry) {
            retry++;
            try {
                return taskToRetry.apply();
            } catch (Exception e) {
                logger.atWarn().kv("exception", e.getMessage()).log("Retry " + taskDescription);
                if (retry >= maxRetry) {
                    break;
                }
                boolean retryable = false;
                for (Class retryableException : retryableExceptions) {
                    if (retryableException.isInstance(e)) {
                        lastRetryableException = e;
                        logger.atWarn().kv("exception", e.getMessage()).log("Retry " + taskDescription);
                        retryable = true;
                    }
                }

                if (!retryable) {
                    if (e instanceof PackageDownloadException) {
                        throw (PackageDownloadException) e;
                    }
                    throw new PackageDownloadException("Unexpected error in " + taskDescription, e);
                }

                Thread.sleep(retryInterval);
                if (retryInterval < MAX_RETRY_INTERVAL_MILLI) {
                    retryInterval = retryInterval * 2;
                } else {
                    retryInterval = MAX_RETRY_INTERVAL_MILLI;
                }
            }
        }
        throw new PackageDownloadException(
                String.format("Fail to execute %s after retrying %d times", taskDescription, maxRetry),
                lastRetryableException);
    }

    private static void updateDigestFromFile(Path filePath, MessageDigest digest) throws IOException {
        try (InputStream existingArtifact = Files.newInputStream(filePath)) {
            byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];
            int readBytes = existingArtifact.read(buffer);
            while (readBytes > -1) {
                digest.update(buffer, 0, readBytes);
                readBytes = existingArtifact.read(buffer);
            }
        }
    }


    private static boolean recipeHasDigest(ComponentArtifact artifact) {
        return !Utils.isEmpty(artifact.getAlgorithm()) && !Utils.isEmpty(artifact.getChecksum());
    }
}

