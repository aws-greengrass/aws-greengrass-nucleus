/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager.plugins;

import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.exceptions.ArtifactChecksumMismatchException;
import com.aws.iot.evergreen.packagemanager.exceptions.InvalidArtifactUriException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageDownloadException;
import com.aws.iot.evergreen.packagemanager.models.ComponentArtifact;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.util.Utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public abstract class ArtifactDownloader {
    static final Logger logger = LogManager.getLogger(ArtifactDownloader.class);
    static final String ARTIFACT_DOWNLOAD_EXCEPTION_FMT =
            "Failed to download artifact %s for component %s-%s, reason: %s";

    static void checkIntegrityAndSaveToStore(InputStream artifactObject, ComponentArtifact artifact,
                                             PackageIdentifier packageIdentifier, Path saveToPath)
            throws PackageDownloadException, IOException {
        try (OutputStream artifactFile = Files.newOutputStream(saveToPath)) {
            MessageDigest messageDigest = MessageDigest.getInstance(artifact.getAlgorithm());
            byte[] buffer = new byte[1024];
            int readBytes = artifactObject.read(buffer);
            while (readBytes > -1) {
                // Compute digest as well as write to the file path
                messageDigest.update(buffer, 0, readBytes);
                artifactFile.write(buffer, 0, readBytes);
                readBytes = artifactObject.read(buffer);
            }
            artifactObject.close();
            String digest = Base64.getEncoder().encodeToString(messageDigest.digest());
            if (!digest.equals(artifact.getChecksum())) {
                // Handle failure in integrity check, delete bad file then throw
                Files.deleteIfExists(saveToPath);
                throw new ArtifactChecksumMismatchException(
                        String.format(ARTIFACT_DOWNLOAD_EXCEPTION_FMT, artifact.getArtifactUri(),
                                packageIdentifier.getName(), packageIdentifier.getVersion().toString(),
                                "Integrity check for downloaded artifact failed"));
            }
            logger.atDebug().setEventType("download-artifact").addKeyValue("packageIdentifier", packageIdentifier)
                    .addKeyValue("artifactUri", artifact.getArtifactUri()).log("Passed integrity check");
        } catch (NoSuchAlgorithmException e) {
            throw new ArtifactChecksumMismatchException(
                    String.format(ARTIFACT_DOWNLOAD_EXCEPTION_FMT, artifact.getArtifactUri(),
                            packageIdentifier.getName(), packageIdentifier.getVersion().toString(),
                            "Algorithm requested for artifact checksum is not supported"), e);
        }
    }

    static boolean needsDownload(ComponentArtifact artifact, Path saveToPath)
            throws PackageDownloadException, IOException {
        // Local recipes don't have digest or algorithm and that's expected, in such case, use the
        // locally present artifact. On the other hand, recipes downloaded from cloud will always
        // have digest and algorithm
        if (Files.exists(saveToPath) && !recipeHasDigest(artifact)) {
            return false;
        } else if (!Files.exists(saveToPath)) {
            if (recipeHasDigest(artifact)) {
                return true;
            } else {
                throw new PackageDownloadException(
                        "No local artifact found and recipe does not have required digest information");
            }
        }

        // If the file already exists and has the right content, skip download
        InputStream existingArtifact = null;
        try {
            existingArtifact = Files.newInputStream(saveToPath);
            MessageDigest messageDigest = MessageDigest.getInstance(artifact.getAlgorithm());
            byte[] buffer = new byte[4096];
            int readBytes = existingArtifact.read(buffer);
            while (readBytes > -1) {
                messageDigest.update(buffer, 0, readBytes);
                readBytes = existingArtifact.read(buffer);
            }
            String digest = Base64.getEncoder().encodeToString(messageDigest.digest());
            return !digest.equals(artifact.getChecksum());

        } catch (IOException | NoSuchAlgorithmException e) {
            // If error in checking the existing content, attempt fresh download
            return false;
        } finally {
            if (existingArtifact != null) {
                existingArtifact.close();
            }
        }
    }

    private static boolean recipeHasDigest(ComponentArtifact artifact) {
        return !Utils.isEmpty(artifact.getAlgorithm()) && !Utils.isEmpty(artifact.getChecksum());
    }

    public abstract File downloadToPath(PackageIdentifier packageIdentifier, ComponentArtifact artifact,
                                        Path saveToPath)
            throws IOException, PackageDownloadException, InvalidArtifactUriException;
}

