/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.builtins;

import com.aws.greengrass.componentmanager.exceptions.ArtifactChecksumMismatchException;
import com.aws.greengrass.componentmanager.exceptions.HashingAlgorithmUnavailableException;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.RetryUtils;
import com.aws.greengrass.util.Utils;
import lombok.AccessLevel;
import lombok.Setter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public abstract class ArtifactDownloader {
    public static final String ARTIFACT_URI_LOG_KEY = "artifactUri";
    public static final String COMPONENT_IDENTIFIER_LOG_KEY = "componentIdentifier";
    protected static final String HTTP_RANGE_HEADER_FORMAT = "bytes=%d-%d";
    protected static final String HTTP_RANGE_HEADER_KEY = "Range";
    static final String ARTIFACT_DOWNLOAD_EXCEPTION_FMT =
            "Failed to download artifact name: '%s' for component %s-%s, reason: ";
    private static final int DOWNLOAD_BUFFER_SIZE = 1024;
    protected final Logger logger;
    protected final ComponentIdentifier identifier;
    protected final ComponentArtifact artifact;
    protected final Path artifactDir;

    @Setter(AccessLevel.PACKAGE)
    private RetryUtils.RetryConfig checksumMismatchRetryConfig =
            RetryUtils.RetryConfig.builder().initialRetryInterval(Duration.ofMinutes(1L))
                    .maxRetryInterval(Duration.ofMinutes(1L)).maxAttempt(10)
                    .retryableExceptions(Arrays.asList(ArtifactChecksumMismatchException.class)).build();
    private Path saveToPath;

    protected ArtifactDownloader(ComponentIdentifier identifier, ComponentArtifact artifact, Path artifactDir) {
        this.identifier = identifier;
        this.artifact = artifact;
        this.artifactDir = artifactDir;
        this.logger = LogManager.getLogger(this.getClass()).createChild();
        this.logger.addDefaultKeyValue(ARTIFACT_URI_LOG_KEY, artifact.getArtifactUri())
                .addDefaultKeyValue(COMPONENT_IDENTIFIER_LOG_KEY, identifier.getName());
    }

    private void updateDigestFromFile(Path filePath, MessageDigest digest) throws IOException {
        try (InputStream existingArtifact = Files.newInputStream(filePath)) {
            byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];
            int readBytes = existingArtifact.read(buffer);
            while (readBytes > -1) {
                digest.update(buffer, 0, readBytes);
                readBytes = existingArtifact.read(buffer);
            }
        }
    }

    private boolean recipeHasDigest(ComponentArtifact artifact) {
        return !Utils.isEmpty(artifact.getAlgorithm()) && !Utils.isEmpty(artifact.getChecksum());
    }

    /**
     * Download an artifact from remote. This call can take a long time if the network is intermittent.
     *
     * @return file handle of the downloaded file
     * @throws IOException                          if I/O error occurred in network/disk
     * @throws InterruptedException                 if interrupted in downloading
     * @throws PackageDownloadException             if error occurred in download process
     * @throws HashingAlgorithmUnavailableException if required hash algorithm is not supported
     */
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.AvoidRethrowingException"})
    public File download()
            throws PackageDownloadException, IOException, InterruptedException, HashingAlgorithmUnavailableException {
        MessageDigest messageDigest;
        try {
            if (artifact.getAlgorithm() == null) {
                throw new ArtifactChecksumMismatchException(getErrorString("Algorithm missing from artifact"),
                        DeploymentErrorCode.RECIPE_MISSING_ARTIFACT_HASH_ALGORITHM);
            }
            messageDigest = MessageDigest.getInstance(artifact.getAlgorithm());
        } catch (NoSuchAlgorithmException e) {
            throw new HashingAlgorithmUnavailableException(
                    getErrorString("Algorithm requested for artifact checksum is not supported"), e);
        }

        saveToPath = getArtifactFile().toPath();
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

        try {
            // A checksum mismatch probably means the downloaded artifact is corrupted, Greengrass will retry the
            //download for 10 times before giving up.
            return RetryUtils.runWithRetry(checksumMismatchRetryConfig, () -> {
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
                    throw new ArtifactChecksumMismatchException(
                            "Integrity check for downloaded artifact failed. Probably due to the file changing"
                                    + " after creating the component version",
                            DeploymentErrorCode.ARTIFACT_CHECKSUM_MISMATCH);
                }
                logger.atDebug().setEventType("download-artifact").log("Passed integrity check");
                return saveToPath.toFile();
            }, "download-artifact", logger);
        } catch (InterruptedException | PackageDownloadException e) {
            throw e;
        } catch (Exception e) {
            throw new PackageDownloadException(getErrorString("Failed to download the artifact"), e);
        }
    }

    /**
     * Internal helper method to download from input stream. If IOException is thrown during the process, the method
     * will return actual number of bytes downloaded. Supposed to be invoked in `protected abstract long download(long
     * rangeStart, long rangeEnd)`
     *
     * @param inputStream   inputStream to download from.
     * @param messageDigest messageDigest to update.
     * @return number of bytes downloaded. Might return 0 only when encountering IOException
     * @throws PackageDownloadException Throw PackageDownloadException when fail to write to the disk
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
                    throw new PackageDownloadException(getErrorString("Error writing artifact"), e).withErrorContext(
                            e.getClass().getSimpleName(), DeploymentErrorCode.IO_WRITE_ERROR);
                }

                messageDigest.update(buffer, 0, readBytes);
                totalReadBytes += readBytes;
                readBytes = inputStream.read(buffer);
            }
            return totalReadBytes;
        } catch (IOException e) {
            logger.atWarn().kv("bytes-read", totalReadBytes).setCause(e)
                    .log("Failed to read from input stream and will retry");
            return totalReadBytes;
        }
    }

    /**
     * Internal method invoked in downloadToFile().
     *
     * @param rangeStart    Range start index. INCLUSIVE.
     * @param rangeEnd      Range end index. INCLUSIVE.
     * @param messageDigest messageDigest to update.
     * @return number of bytes downloaded.
     * @throws PackageDownloadException PackageDownloadException
     */
    protected abstract long download(long rangeStart, long rangeEnd, MessageDigest messageDigest)
            throws PackageDownloadException, InterruptedException;

    /**
     * Checks whether it is necessary to download the artifact or the existing file suffices.
     *
     * @return true if download is necessary
     */
    public boolean downloadRequired() {
        String filename = getArtifactFilename();
        if (Files.exists(artifactDir.resolve(filename))) {
            if (recipeHasDigest(artifact)) {
                // If the file already exists and has the right content, skip download
                try {
                    MessageDigest messageDigest = MessageDigest.getInstance(artifact.getAlgorithm());
                    updateDigestFromFile(artifactDir.resolve(filename), messageDigest);
                    String digest = Base64.getEncoder().encodeToString(messageDigest.digest());
                    boolean mismatches = !digest.equals(artifact.getChecksum());
                    if (mismatches) {
                        logger.atWarn().log("Artifact appears to exist on disk, "
                                + "but the digest on disk does not match the digest in the recipe. Will attempt to "
                                + "download it again.");
                    }
                    return mismatches;
                } catch (IOException | NoSuchAlgorithmException e) {
                    logger.atWarn().setCause(e).log("Fail to verify the checksum");
                    return true;
                }
            } else {
                // Local recipes don't have digest or algorithm and that's expected, in such case, use the
                // locally present artifact. On the other hand, recipes downloaded from cloud will always
                // have digest and algorithm
                return false;
            }
        } else {
            return true;
        }
    }

    /**
     * Get the artifact file.
     *
     * @return artifact file that was either downloaded or had been locally present
     */
    public File getArtifactFile() {
        return artifactDir.resolve(getArtifactFilename()).toFile();
    }

    /**
     * Check whether the downloader has proper configs and is ready to download files.
     * @return Optional.empty if no errors and ready to download. Otherwise returns the error message string
     */
    public abstract Optional<String> checkDownloadable();

    /**
     * Get the download size of the artifact file.
     *
     * @return size of the artifact in bytes
     * @throws InterruptedException if interrupted in downloading
     * @throws PackageDownloadException if error encountered
     */
    public abstract Long getDownloadSize() throws PackageDownloadException, InterruptedException;

    protected abstract String getArtifactFilename();

    protected String getErrorString(String reason) {
        return String.format(ARTIFACT_DOWNLOAD_EXCEPTION_FMT, artifact.getArtifactUri(),
                identifier.getName(), identifier.getVersion().toString()) + reason;
    }

    /**
     * Check if an instance of implemented class supports checking component store size depending on
     * if the artifact is located in greengrass artifact store or third party.
     *
     * @return evaluation result
     */
    public boolean checkComponentStoreSize() {
        return true;
    }

    /**
     * Check if an instance of implemented class supports setting file permissions depending on
     * if the artifact is located in greengrass artifact store or third party.
     *
     * @return evaluation result
     */
    public boolean canSetFilePermissions() {
        return true;
    }

    /**
     * Check if an instance of implemented class supports unarchiving the artifact depending on
     * if the artifact is located in greengrass artifact store or third party.
     *
     * @return evaluation result
     */
    public boolean canUnarchiveArtifact() {
        return true;
    }
}

