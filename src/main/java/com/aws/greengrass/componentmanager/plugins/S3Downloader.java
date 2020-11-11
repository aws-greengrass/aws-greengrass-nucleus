/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins;

import com.aws.greengrass.componentmanager.exceptions.InvalidArtifactUriException;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.util.Pair;
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

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads component artifacts from S3 bucket URI specified in the component recipe.
 */
public class S3Downloader extends ArtifactDownloader {
    private static final Pattern S3_PATH_REGEX = Pattern.compile("s3:\\/\\/([^\\/]+)\\/(.*)");
    protected static final String REGION_EXPECTING_STRING = "expecting '";
    private final S3Client s3Client;
    private final S3SdkClientFactory s3ClientFactory;
    private final S3ObjectPath s3ObjectPath;

    /**
     * Constructor.
     *
     * @param clientFactory S3 client factory
     */
    protected S3Downloader(S3SdkClientFactory clientFactory, ComponentIdentifier identifier, ComponentArtifact artifact,
                        Path artifactDir)
            throws InvalidArtifactUriException {
        super(identifier, artifact, artifactDir);
        this.s3ClientFactory = clientFactory;
        this.s3Client = clientFactory.getS3Client();
        this.s3ObjectPath = getS3PathForURI(artifact.getArtifactUri(), identifier);
    }

    @Override
    protected String getArtifactFilenameNoRetry() {
        String objectKey = s3ObjectPath.key;
        String[] pathStrings = objectKey.split("/");
        return pathStrings[pathStrings.length - 1];
    }

    @SuppressWarnings("PMD.CloseResource")
    @Override
    protected Pair<InputStream, Runnable> readWithRange(long start, long end)
            throws PackageDownloadException, RetryableException {
        String bucket = s3ObjectPath.bucket;
        String key = s3ObjectPath.key;
        try {
            S3Client regionClient = getRegionClientForBucket(bucket);
            GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket(bucket).key(key)
                    .range(String.format(HTTP_RANGE_HEADER_FORMAT, start, end)).build();
            logger.debug("Getting s3 object request: {}", getObjectRequest.toString());
            return new Pair<>(regionClient.getObject(getObjectRequest), () -> {});
        } catch (SdkClientException | S3Exception e) {
            String errorMsg = getErrorString("Failed to get artifact object from S3");
            if (e.retryable()) {
                throw new RetryableException(errorMsg, e);
            }
            throw new PackageDownloadException(errorMsg, e);
        }
    }

    @SuppressWarnings("PMD.CloseResource")
    @Override
    protected Long getDownloadSizeNoRetry() throws RetryableException, PackageDownloadException {
        logger.atInfo().setEventType("get-download-size-from-s3").log();
        // Parse artifact path
        String key = s3ObjectPath.key;
        String bucket = s3ObjectPath.bucket;
        try {
            S3Client regionClient = getRegionClientForBucket(bucket);
            HeadObjectRequest headObjectRequest = HeadObjectRequest.builder().bucket(bucket).key(key).build();
            HeadObjectResponse headObjectResponse = regionClient.headObject(headObjectRequest);
            return headObjectResponse.contentLength();
        } catch (SdkClientException | S3Exception e) {
            String errMsg = String.format(ARTIFACT_DOWNLOAD_EXCEPTION_FMT, artifact.getArtifactUri(),
                    identifier.getName(), identifier.getVersion().toString(),
                    "Failed to head artifact object from S3");
            if (e.retryable()) {
                throw new RetryableException(errMsg, e);
            }
            throw new PackageDownloadException(errMsg, e);
        }
    }

    private S3Client getRegionClientForBucket(String bucket) {
        GetBucketLocationRequest getBucketLocationRequest = GetBucketLocationRequest.builder().bucket(bucket).build();
        String region = null;
        try {
            region = s3Client.getBucketLocation(getBucketLocationRequest).locationConstraintAsString();
        } catch (S3Exception e) {
            String message = e.getMessage();
            if (message.contains(REGION_EXPECTING_STRING)) {
                message =
                        message.substring(message.indexOf(REGION_EXPECTING_STRING) + REGION_EXPECTING_STRING.length());
                region = message.substring(0, message.indexOf('\''));
            }
        }
        // If the region is empty, it is us-east-1
        return s3ClientFactory.getClientForRegion(Utils.isEmpty(region) ? Region.US_EAST_1 : Region.of(region));
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

    @AllArgsConstructor
    private static class S3ObjectPath {
        String bucket;
        String key;
    }
}
