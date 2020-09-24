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
import com.aws.greengrass.util.S3SdkClientFactory;
import com.aws.greengrass.util.Utils;
import lombok.AllArgsConstructor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;

/**
 * Downloads component artifacts from S3 bucket URI specified in the component recipe.
 */
public class S3Downloader extends ArtifactDownloader {
    private static final Logger logger = LogManager.getLogger(S3Downloader.class);
    private static final Pattern S3_PATH_REGEX = Pattern.compile("s3:\\/\\/([^\\/]+)\\/(.*)");
    private final S3Client s3Client;
    private final S3SdkClientFactory s3ClientFactory;

    /**
     * Constructor.
     *
     * @param clientFactory S3 client factory
     */
    @Inject
    public S3Downloader(S3SdkClientFactory clientFactory) {
        super();
        this.s3Client = clientFactory.getS3Client();
        this.s3ClientFactory = clientFactory;
    }

    @SuppressWarnings({"PMD.AvoidInstanceofChecksInCatchClause"})
    @Override
    public File downloadToPath(ComponentIdentifier componentIdentifier, ComponentArtifact artifact, Path saveToPath)
            throws IOException, PackageDownloadException, InvalidArtifactUriException {

        logger.atInfo().setEventType("download-artifact").addKeyValue("packageIdentifier", componentIdentifier)
                .addKeyValue("artifactUri", artifact.getArtifactUri()).log();

        // Parse artifact path
        S3ObjectPath s3ObjectPath = getS3PathForURI(artifact.getArtifactUri(), componentIdentifier);
        String bucket = s3ObjectPath.bucket;
        String key = s3ObjectPath.key;

        InputStream artifactObject = null;
        try {
            Path filePath = saveToPath.resolve(extractFileName(key));
            // Skip download if not needed
            if (needsDownload(artifact, filePath)) {
                // Get artifact from S3
                artifactObject = getObject(bucket, key, artifact, componentIdentifier);

                // Perform integrity check and save file to store
                checkIntegrityAndSaveToStore(artifactObject, artifact, componentIdentifier, filePath);

            } else {
                logger.atDebug().addKeyValue("artifact", artifact.getArtifactUri())
                        .log("Artifact already exists, skipping download");
            }

            return filePath.toFile();
        } catch (PackageDownloadException e) {
            if (e instanceof ArtifactChecksumMismatchException || !saveToPath.resolve(extractFileName(key)).toFile()
                    .exists()) {
                throw e;
            }
            logger.atInfo("download-artifact").addKeyValue("packageIdentifier", componentIdentifier)
                    .addKeyValue("artifactUri", artifact.getArtifactUri())
                    .log("Failed to download artifact, but found it locally, using that version", e);
            return saveToPath.resolve(extractFileName(key)).toFile();
        } finally {
            if (artifactObject != null) {
                artifactObject.close();
            }
        }
    }

    /**
     * Get the size of artifact from S3.
     *
     * @param packageIdentifier package info
     * @param artifact artifact info
     * @return ContentLength in bytes
     * @throws InvalidArtifactUriException if provided info results in invalid URI
     */
    @Override
    public long getSize(ComponentIdentifier packageIdentifier, ComponentArtifact artifact)
            throws InvalidArtifactUriException {
        logger.atInfo().setEventType("get-artifact-size-from-s3")
                .addKeyValue("packageIdentifier", packageIdentifier)
                .addKeyValue("artifactUri", artifact.getArtifactUri().toString()).log();
        S3ObjectPath s3ObjectPath = getS3PathForURI(artifact.getArtifactUri(), packageIdentifier);
        HeadObjectRequest headObjectRequest =
                HeadObjectRequest.builder().bucket(s3ObjectPath.bucket).key(s3ObjectPath.key).build();
        HeadObjectResponse headObjectResponse = s3Client.headObject(headObjectRequest);
        return headObjectResponse.contentLength();
    }

    @SuppressWarnings("PMD.CloseResource")
    private InputStream getObject(String bucket, String key, ComponentArtifact artifact,
                                  ComponentIdentifier componentIdentifier) throws PackageDownloadException {
        try {
            GetBucketLocationRequest getBucketLocationRequest =
                    GetBucketLocationRequest.builder().bucket(bucket).build();
            String region = s3Client.getBucketLocation(getBucketLocationRequest).locationConstraintAsString();
            // If the region is empty, it is us-east-1
            S3Client regionClient =
                    s3ClientFactory.getClientForRegion(Utils.isEmpty(region) ? Region.US_EAST_1 : Region.of(region));
            GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket(bucket).key(key).build();

            return regionClient.getObject(getObjectRequest);
        } catch (SdkClientException | S3Exception e) {
            throw new PackageDownloadException(String.format(ARTIFACT_DOWNLOAD_EXCEPTION_FMT, artifact.getArtifactUri(),
                    componentIdentifier.getName(), componentIdentifier.getVersion().toString(),
                    "Failed to get artifact object from S3"), e);
        }
    }

    private S3ObjectPath getS3PathForURI(URI artifactURI, ComponentIdentifier componentIdentifier)
            throws InvalidArtifactUriException {
        Matcher s3PathMatcher = S3_PATH_REGEX.matcher(artifactURI.toString());
        if (!s3PathMatcher.matches()) {
            // Bad URI
            throw new InvalidArtifactUriException(
                    String.format(ARTIFACT_DOWNLOAD_EXCEPTION_FMT, artifactURI, componentIdentifier.getName(),
                            componentIdentifier.getVersion().toString(), "Invalid artifact URI"));
        }

        // Parse artifact path
        String bucket = s3PathMatcher.group(1);
        String key = s3PathMatcher.group(2);
        return new S3ObjectPath(bucket, key);
    }

    private String extractFileName(String objectKey) {
        String[] pathStrings = objectKey.split("/");
        return pathStrings[pathStrings.length - 1];
    }

    @AllArgsConstructor
    private static class S3ObjectPath {
        String bucket;
        String key;
    }
}
