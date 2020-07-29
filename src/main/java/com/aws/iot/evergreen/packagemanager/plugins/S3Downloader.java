/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager.plugins;

import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.exceptions.InvalidArtifactUriException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageDownloadException;
import com.aws.iot.evergreen.packagemanager.models.ComponentArtifact;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.tes.TokenExchangeService;
import com.aws.iot.evergreen.util.S3SdkClientFactory;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;

/**
 * Downloads component artifacts from S3 bucket URI specified in the component recipe.
 */
public class S3Downloader implements ArtifactDownloader {
    private static final Logger logger = LogManager.getLogger(S3Downloader.class);
    private static final Pattern S3_PATH_REGEX = Pattern.compile("s3:\\/\\/([^\\/]+)\\/(.*)");
    private static final String ARTIFACT_DOWNLOAD_EXCEPTION_PMS_FMT =
            "Failed to download artifact %s for component %s-%s, reason: %s";
    private final S3Client s3Client;
    private final Kernel kernel;

    /**
     * Constructor.
     *
     * @param clientFactory S3 client factory
     * @param kernel kernel
     */
    @Inject
    public S3Downloader(S3SdkClientFactory clientFactory, Kernel kernel) {
        this.s3Client = clientFactory.getS3Client();
        this.kernel = kernel;
    }

    @Override
    public void downloadToPath(PackageIdentifier packageIdentifier, ComponentArtifact artifact, Path saveToPath)
            throws IOException, PackageDownloadException, InvalidArtifactUriException {

        logger.atInfo().setEventType("download-artifact").addKeyValue("packageIdentifier", packageIdentifier)
                .addKeyValue("artifactUri", artifact.getArtifactUri()).log();

        // Parse artifact path
        Matcher s3PathMatcher = getS3PathMatcherForURI(artifact.getArtifactUri(), packageIdentifier);
        String bucket = s3PathMatcher.group(1);
        String key = s3PathMatcher.group(2);

        // Get artifact from S3
        // TODO : Calculating hash for integrity check nees the whole object in memory,
        //  However it could be an issue in the case of large files, need to evaluate if
        //  there's a way to get around this
        byte[] artifactObject = getObject(bucket, key, artifact, packageIdentifier);

        // Perform integrity check
        performIntegrityCheck(artifactObject, artifact, packageIdentifier);

        // Save file to store
        Files.write(saveToPath.resolve(extractFileName(key)), artifactObject, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private byte[] getObject(String bucket, String key, ComponentArtifact artifact,
                             PackageIdentifier packageIdentifier)
            throws PackageDownloadException {
        try {
            TokenExchangeService tokenExchangeService = kernel.getContext().get(TokenExchangeService.class);
            GetObjectRequest getObjectRequest = GetObjectRequest.builder().overrideConfiguration(
                    AwsRequestOverrideConfiguration.builder()
                            .credentialsProvider(tokenExchangeService).build())
                    .bucket(bucket).key(key).build();
            return s3Client.getObjectAsBytes(getObjectRequest).asByteArray();
        } catch (S3Exception e) {
            throw new PackageDownloadException(
                    String.format(ARTIFACT_DOWNLOAD_EXCEPTION_PMS_FMT, packageIdentifier.getName(),
                            packageIdentifier.getVersion().toString(), artifact.getArtifactUri(),
                            "Failed to get artifact object from S3"), e);
        }
    }

    private Matcher getS3PathMatcherForURI(URI artifactURI, PackageIdentifier packageIdentifier)
            throws InvalidArtifactUriException {
        Matcher s3PathMatcher = S3_PATH_REGEX.matcher(artifactURI.toString());
        if (!s3PathMatcher.matches()) {
            // Bad URI
            throw new InvalidArtifactUriException(
                    String.format(ARTIFACT_DOWNLOAD_EXCEPTION_PMS_FMT, packageIdentifier.getName(),
                            packageIdentifier.getVersion().toString(), artifactURI, "Invalid artifact URI"));
        }
        return s3PathMatcher;
    }

    private void performIntegrityCheck(byte[] artifactObject, ComponentArtifact artifact,
                                       PackageIdentifier packageIdentifier) throws PackageDownloadException {
        try {
            String digest = Base64.getEncoder()
                    .encodeToString(MessageDigest.getInstance(artifact.getAlgorithm()).digest(artifactObject));
            if (!digest.equals(artifact.getChecksum())) {
                // Handle failure in integrity check
                throw new PackageDownloadException(
                        String.format(ARTIFACT_DOWNLOAD_EXCEPTION_PMS_FMT, packageIdentifier.getName(),
                                packageIdentifier.getVersion().toString(), artifact.getArtifactUri(),
                                "Integrity check for downloaded artifact failed"));
            }
            logger.atInfo().setEventType("download-artifact").addKeyValue("packageIdentifier", packageIdentifier)
                    .addKeyValue("artifactUri", artifact.getArtifactUri()).log("Passed integrity check");
        } catch (NoSuchAlgorithmException e) {
            throw new PackageDownloadException(
                    String.format(ARTIFACT_DOWNLOAD_EXCEPTION_PMS_FMT, packageIdentifier.getName(),
                            packageIdentifier.getVersion().toString(), artifact.getArtifactUri(),
                            "Algorithm requested for artifact checksum is not supported"), e);
        }
    }

    private String extractFileName(String objectKey) {
        String[] pathStrings = objectKey.split("/");
        return pathStrings[pathStrings.length - 1];
    }
}

