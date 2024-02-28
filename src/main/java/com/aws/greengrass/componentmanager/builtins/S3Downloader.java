/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.builtins;

import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.exceptions.InvalidArtifactUriException;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.exceptions.RetryableServerErrorException;
import com.aws.greengrass.util.RetryUtils;
import com.aws.greengrass.util.S3SdkClientFactory;
import com.aws.greengrass.util.Utils;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.HttpStatusCode;
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
    // S3 throws "The provided token has expired" with status code 400 instead of 403. We need to retry on this error
    // by getting new credentials and then trying again.
    private static final String TOKEN_HAS_EXPIRED = "token has expired";
    // S3 throws the following error with code 403. This may happen due to eventual consistency between various
    // AWS services. We should retry on this error as the credentials that we have should be valid and may
    // work the next time that we query.
    private static final String TOKEN_NOT_EXIST = "The AWS Access Key Id you provided does not exist in our records";
    private final S3SdkClientFactory s3ClientFactory;
    private final S3ObjectPath s3ObjectPath;

    @Getter(AccessLevel.PACKAGE)
    // Setter for unit test
    @Setter(AccessLevel.PACKAGE)
    private RetryUtils.RetryConfig s3ClientExceptionRetryConfig =
            RetryUtils.RetryConfig.builder().initialRetryInterval(Duration.ofMinutes(1L))
                    .maxRetryInterval(Duration.ofMinutes(1L)).maxAttempt(Integer.MAX_VALUE)
                    .retryableExceptions(Arrays.asList(SdkClientException.class,
                            IOException.class, RetryableServerErrorException.class)).build();

    /**
     * Constructor.
     *
     * @param clientFactory S3 client factory
     */
    protected S3Downloader(S3SdkClientFactory clientFactory, ComponentIdentifier identifier, ComponentArtifact artifact,
                           Path artifactDir, ComponentStore componentStore) throws InvalidArtifactUriException {
        super(identifier, artifact, artifactDir, componentStore);
        this.s3ClientFactory = clientFactory;
        this.s3ObjectPath = getS3PathForURI(artifact.getArtifactUri());
    }

    @Override
    protected String getArtifactFilename() {
        String objectKey = s3ObjectPath.key;
        String[] pathStrings = objectKey.split("/");
        return pathStrings[pathStrings.length - 1];
    }

    @Override
    public void cleanup() throws IOException {

    }

    @SuppressWarnings(
            {"PMD.CloseResource", "PMD.AvoidCatchingGenericException", "PMD.AvoidRethrowingException"})
    @Override
    protected long download(long rangeStart, long rangeEnd, MessageDigest messageDigest)
            throws InterruptedException, PackageDownloadException {
        String bucket = s3ObjectPath.bucket;
        String key = s3ObjectPath.key;

        GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket(bucket).key(key)
                .range(String.format(HTTP_RANGE_HEADER_FORMAT, rangeStart, rangeEnd)).build();
        logger.atDebug().kv("bucket", getObjectRequest.bucket()).kv("s3-key", getObjectRequest.key())
                .kv("range", getObjectRequest.range()).log("Getting s3 object request");

        try {
            return RetryUtils.runWithRetry(s3ClientExceptionRetryConfig, () -> {
                long downloaded = 0;
                S3Client regionClient = getRegionClientForBucket(bucket);
                try (InputStream inputStream = regionClient.getObject(getObjectRequest)) {
                    downloaded = download(inputStream, messageDigest);
                    if (downloaded == 0) {
                        // If 0 byte is read, it's fairly certain that the inputStream is closed.
                        // Therefore throw IOException to trigger the retry logic.
                        throw new IOException("Failed to read any byte from the inputStream");
                    } else {
                        return downloaded;
                    }
                } catch (S3Exception e) {
                    throwRetryableOrNonRetryable(e, "GetObject");
                    throw e; // Call above is guaranteed to throw. This line will not execute
                }
            }, "download-S3-artifact", logger);
        } catch (InterruptedException e) {
            throw e;
        } catch (S3Exception e) {
            if (e.statusCode() == HttpStatusCode.FORBIDDEN) {
                throw new PackageDownloadException(getErrorString("S3 GetObject returns 403 Access Denied. "
                        + "Ensure the IAM role associated with the core device has a policy granting s3:GetObject"),
                        e).withErrorContext(e, DeploymentErrorCode.S3_GET_OBJECT_ACCESS_DENIED);
            }
            if (e.statusCode() == HttpStatusCode.NOT_FOUND) {
                throw new PackageDownloadException(getErrorString("S3 GetObject returns 404 Resource Not Found."
                        + "Ensure the IAM role associated with the core device has a policy granting s3:GetObject "
                        + "and the artifact object uri is correct"),
                        e).withErrorContext(e, DeploymentErrorCode.S3_GET_OBJECT_RESOURCE_NOT_FOUND);
            }
            throw new PackageDownloadException(getErrorString("Failed to download object from S3"), e);
        } catch (Exception e) {
            throw new PackageDownloadException(getErrorString("Failed to download object from S3"), e);
        }
    }

    private static void throwRetryableOrNonRetryable(S3Exception e, String method)
            throws RetryableServerErrorException {
        if (RetryUtils.retryErrorCodes(e.statusCode())) {
            throw new RetryableServerErrorException(method + " returned: " + e.statusCode(), e);
        } else if (e.getMessage() != null
                && (e.getMessage().contains(TOKEN_HAS_EXPIRED) || e.getMessage().contains(TOKEN_NOT_EXIST))) {
            throw new RetryableServerErrorException(
                    method + " returned: " + e.getMessage() + " status code " + e.statusCode(), e);
        }
        throw e;
    }

    @Override
    public Optional<String> checkDownloadable() {
        return Optional.ofNullable(s3ClientFactory.getConfigValidationError());
    }

    @SuppressWarnings({"PMD.CloseResource", "PMD.AvoidCatchingGenericException", "PMD.AvoidRethrowingException"})
    @Override
    public Long getDownloadSize() throws InterruptedException, PackageDownloadException {
        logger.atDebug().setEventType("get-download-size-from-s3").log();
        // Parse artifact path
        String key = s3ObjectPath.key;
        String bucket = s3ObjectPath.bucket;
        try {
            HeadObjectRequest headObjectRequest = HeadObjectRequest.builder().bucket(bucket).key(key).build();
            return RetryUtils.runWithRetry(s3ClientExceptionRetryConfig, () -> {
                try {
                    S3Client regionClient = getRegionClientForBucket(bucket);
                    HeadObjectResponse headObjectResponse = regionClient.headObject(headObjectRequest);
                    return headObjectResponse.contentLength();
                } catch (S3Exception e) {
                    throwRetryableOrNonRetryable(e, "HeadObject");
                    throw e; // Call above is guaranteed to throw. This line will not execute
                }
            }, "get-download-size-from-s3", logger);
        } catch (InterruptedException e) {
            throw e;
        } catch (S3Exception e) {
            if (e.statusCode() == HttpStatusCode.FORBIDDEN) {
                throw new PackageDownloadException(getErrorString("S3 HeadObject returns 403 Access Denied. Ensure "
                        + "the IAM role associated with the core device has a policy granting s3:GetObject"),
                        e).withErrorContext(e, DeploymentErrorCode.S3_HEAD_OBJECT_ACCESS_DENIED);
            }
            if (e.statusCode() == HttpStatusCode.NOT_FOUND) {
                throw new PackageDownloadException(getErrorString("S3 HeadObject returns 404 Resource Not Found."
                        + "Ensure the IAM role associated with the core device has a policy granting s3:GetObject "
                        + "and the artifact object uri is correct"),
                        e).withErrorContext(e, DeploymentErrorCode.S3_HEAD_OBJECT_RESOURCE_NOT_FOUND);
            }
            throw new PackageDownloadException(getErrorString("Failed to head artifact object from S3"), e);
        } catch (Exception e) {
            throw new PackageDownloadException(getErrorString("Failed to head artifact object from S3"), e);
        }
    }

    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.AvoidRethrowingException"})
    private S3Client getRegionClientForBucket(String bucket) throws InterruptedException, PackageDownloadException {
        GetBucketLocationRequest getBucketLocationRequest = GetBucketLocationRequest.builder().bucket(bucket).build();
        String region = null;
        try {
            region = RetryUtils.runWithRetry(s3ClientExceptionRetryConfig, () -> {
                try {
                    return s3ClientFactory.getS3Client().getBucketLocation(getBucketLocationRequest)
                            .locationConstraintAsString();
                } catch (S3Exception e) {
                    throwRetryableOrNonRetryable(e, "GetBucketLocation");
                    throw e; // Call above is guaranteed to throw. This line will not execute
                }
            },"get-bucket-location", logger);
        } catch (InterruptedException e) {
            throw e;
        } catch (S3Exception e) {
            String message = e.getMessage();
            if (message.contains(REGION_EXPECTING_STRING)) {
                message =
                        message.substring(message.indexOf(REGION_EXPECTING_STRING) + REGION_EXPECTING_STRING.length());
                region = message.substring(0, message.indexOf('\''));
            } else {
                if (e.statusCode() == HttpStatusCode.FORBIDDEN) {
                    throw new PackageDownloadException(getErrorString("S3 GetBucketLocation returns 403 Access Denied."
                            + " Ensure the IAM role associated with the core device has a policy granting"
                            + " s3:GetBucketLocation"), e)
                            .withErrorContext(e, DeploymentErrorCode.S3_GET_BUCKET_LOCATION_ACCESS_DENIED);
                }
                if (e.statusCode() == HttpStatusCode.NOT_FOUND) {
                    throw new PackageDownloadException(getErrorString("S3 GetBucketLocation returns 404 Resource Not"
                            + " Found"), e)
                            .withErrorContext(e, DeploymentErrorCode.S3_GET_BUCKET_LOCATION_RESOURCE_NOT_FOUND);
                }
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
            throw new InvalidArtifactUriException(getErrorString("Invalid artifact URI " + artifactURI),
                    DeploymentErrorCode.S3_ARTIFACT_URI_NOT_VALID);
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
