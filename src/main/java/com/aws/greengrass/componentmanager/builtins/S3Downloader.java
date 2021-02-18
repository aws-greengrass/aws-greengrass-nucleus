/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.builtins;

import com.aws.greengrass.componentmanager.exceptions.InvalidArtifactUriException;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.util.RetryUtils;
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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads component artifacts from S3 bucket URI specified in the component recipe.
 */
public class S3Downloader extends ArtifactDownloader {
    protected static final String REGION_EXPECTING_STRING = "expecting '";
    private static final Pattern S3_PATH_REGEX = Pattern.compile("s3:\\/\\/([^\\/]+)\\/(.*)");
    private final S3SdkClientFactory s3ClientFactory;
    private final S3ObjectPath s3ObjectPath;
    private final RetryUtils.RetryConfig s3ClientExceptionRetryConfig =
            RetryUtils.RetryConfig.builder().initialRetryInterval(Duration.ofMinutes(1L))
                    .maxRetryInterval(Duration.ofMinutes(1L)).maxAttempt(Integer.MAX_VALUE)
                    .retryableExceptions(Arrays.asList(SdkClientException.class, IOException.class)).build();

    /**
     * Constructor.
     *
     * @param clientFactory S3 client factory
     */
    protected S3Downloader(S3SdkClientFactory clientFactory, ComponentIdentifier identifier, ComponentArtifact artifact,
                           Path artifactDir) throws InvalidArtifactUriException {
        super(identifier, artifact, artifactDir);
        this.s3ClientFactory = clientFactory;
        this.s3ObjectPath = getS3PathForURI(artifact.getArtifactUri());
    }

    @Override
    protected String getArtifactFilename() {
        String objectKey = s3ObjectPath.key;
        String[] pathStrings = objectKey.split("/");
        return pathStrings[pathStrings.length - 1];
    }

    @SuppressWarnings(
            {"PMD.CloseResource", "PMD.AvoidCatchingGenericException", "PMD.AvoidRethrowingException"})
    @Override
    protected long download(long rangeStart, long rangeEnd, MessageDigest messageDigest)
            throws InterruptedException, PackageDownloadException {
        String bucket = s3ObjectPath.bucket;
        String key = s3ObjectPath.key;

        S3Client regionClient = getRegionClientForBucket(bucket);
        GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket(bucket).key(key)
                .range(String.format(HTTP_RANGE_HEADER_FORMAT, rangeStart, rangeEnd)).build();
        logger.atDebug().kv("bucket", getObjectRequest.bucket()).kv("s3-key", getObjectRequest.key())
                .kv("range", getObjectRequest.range()).log("Getting s3 object request");

        try {
            return RetryUtils.runWithRetry(s3ClientExceptionRetryConfig, () -> {
                try (InputStream inputStream = regionClient.getObject(getObjectRequest)) {
                    long downloaded = download(inputStream, messageDigest);
                    if (downloaded == 0) {
                        // If 0 byte is read, it's fairly certain that the inputStream is closed.
                        // Therefore throw IOException to trigger the retry logic.
                        throw new IOException("Failed to read any byte from the inputStream");
                    } else {
                        return downloaded;
                    }
                }
            }, "download-S3-artifact", logger);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new PackageDownloadException(getErrorString("Failed to download object from S3"), e);
        }
    }

    @Override
    public Optional<String> checkDownloadable() {
        return Optional.ofNullable(s3ClientFactory.getConfigValidationError());
    }

    @SuppressWarnings({"PMD.CloseResource", "PMD.AvoidCatchingGenericException", "PMD.AvoidRethrowingException"})
    @Override
    public Long getDownloadSize() throws InterruptedException, PackageDownloadException {
        logger.atInfo().setEventType("get-download-size-from-s3").log();
        // Parse artifact path
        String key = s3ObjectPath.key;
        String bucket = s3ObjectPath.bucket;
        try {
            S3Client regionClient = getRegionClientForBucket(bucket);
            HeadObjectRequest headObjectRequest = HeadObjectRequest.builder().bucket(bucket).key(key).build();
            return RetryUtils.runWithRetry(s3ClientExceptionRetryConfig, () -> {
                HeadObjectResponse headObjectResponse = regionClient.headObject(headObjectRequest);
                return headObjectResponse.contentLength();
            }, "get-download-size-from-s3", logger);
        } catch (PackageDownloadException | InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new PackageDownloadException(getErrorString("Failed to head artifact object from S3"), e);
        }
    }

    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.AvoidRethrowingException"})
    private S3Client getRegionClientForBucket(String bucket) throws InterruptedException, PackageDownloadException {
        GetBucketLocationRequest getBucketLocationRequest = GetBucketLocationRequest.builder().bucket(bucket).build();
        String region = null;
        try {
            region = RetryUtils.runWithRetry(s3ClientExceptionRetryConfig,
                    () -> s3ClientFactory.getS3Client().getBucketLocation(getBucketLocationRequest)
                            .locationConstraintAsString(), "get-bucket-location", logger);
        } catch (InterruptedException e) {
            throw e;
        } catch (S3Exception e) {
            String message = e.getMessage();
            if (message.contains(REGION_EXPECTING_STRING)) {
                message =
                        message.substring(message.indexOf(REGION_EXPECTING_STRING) + REGION_EXPECTING_STRING.length());
                region = message.substring(0, message.indexOf('\''));
            } else {
                throw new PackageDownloadException(getErrorString("Failed to determine S3 bucket location"), e);
            }
        } catch (Exception e) {
            throw new PackageDownloadException(getErrorString("Failed to determine S3 bucket location"), e);
        }
        // If the region is empty, it is us-east-1
        return s3ClientFactory.getClientForRegion(Utils.isEmpty(region) ? Region.US_EAST_1 : Region.of(region));
    }

    private S3ObjectPath getS3PathForURI(URI artifactURI)
            throws InvalidArtifactUriException {
        Matcher s3PathMatcher = S3_PATH_REGEX.matcher(artifactURI.toString());
        if (!s3PathMatcher.matches()) {
            // Bad URI
            throw new InvalidArtifactUriException(
                    getErrorString("Invalid artifact URI " + artifactURI.toString()));
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
