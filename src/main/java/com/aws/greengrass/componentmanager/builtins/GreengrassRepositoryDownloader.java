/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.builtins;

import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.GreengrassComponentServiceClientFactory;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.util.RetryUtils;
import lombok.AccessLevel;
import lombok.Setter;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.greengrassv2.model.GetComponentVersionArtifactRequest;
import software.amazon.awssdk.services.greengrassv2.model.GetComponentVersionArtifactResponse;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

public class GreengrassRepositoryDownloader extends ArtifactDownloader {
    private final ComponentStore componentStore;
    private final GreengrassComponentServiceClientFactory clientFactory;
    private Long artifactSize = null;
    // Setter for unit test
    @Setter(AccessLevel.PACKAGE)
    private RetryUtils.RetryConfig clientExceptionRetryConfig =
            RetryUtils.RetryConfig.builder().initialRetryInterval(Duration.ofMinutes(1L))
                    .maxRetryInterval(Duration.ofMinutes(1L)).maxAttempt(Integer.MAX_VALUE)
                    .retryableExceptions(Arrays.asList(SdkClientException.class, IOException.class)).build();

    protected GreengrassRepositoryDownloader(GreengrassComponentServiceClientFactory clientFactory,
                                             ComponentIdentifier identifier, ComponentArtifact artifact,
                                             Path artifactDir, ComponentStore componentStore) {
        super(identifier, artifact, artifactDir);
        this.clientFactory = clientFactory;
        this.componentStore = componentStore;
    }

    protected static String getArtifactFilename(ComponentArtifact artifact) {
        String ssp = artifact.getArtifactUri().getSchemeSpecificPart();
        return Objects.toString(Paths.get(ssp).getFileName());
    }

    @Override
    protected String getArtifactFilename() {
        return getArtifactFilename(artifact);
    }

    @Override
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.AvoidRethrowingException"})
    public Long getDownloadSize() throws PackageDownloadException, InterruptedException {
        if (artifactSize != null) {
            return artifactSize;
        }
        try {
            artifactSize = RetryUtils
                    .runWithRetry(clientExceptionRetryConfig, () -> getDownloadSizeWithoutRetry(), "get-artifact-size",
                            logger);
            return artifactSize;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new PackageDownloadException(getErrorString("Failed to get download size"), e);
        }
    }

    private Long getDownloadSizeWithoutRetry() throws InterruptedException, PackageDownloadException, IOException {
        URL url = getArtifactDownloadURL(identifier, artifact.getArtifactUri().getSchemeSpecificPart());

        HttpURLConnection httpConn = null;
        try {
            httpConn = connect(url);
            int responseCode = httpConn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                long length = httpConn.getContentLengthLong();
                if (length == -1) {
                    throw new PackageDownloadException(getErrorString("Failed to get download size"));
                }
                return length;
            } else {
                throw new PackageDownloadException(
                        getErrorString("Failed to get download size. HTTP response: " + responseCode));
            }
        } finally {
            if (httpConn != null) {
                httpConn.disconnect();
            }
        }
    }

    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.AvoidRethrowingException"})
    @Override
    protected long download(long rangeStart, long rangeEnd, MessageDigest messageDigest)
            throws PackageDownloadException, InterruptedException {
        URL url = getArtifactDownloadURL(identifier, artifact.getArtifactUri().getSchemeSpecificPart());

        try {
            return RetryUtils.runWithRetry(clientExceptionRetryConfig, () -> {
                HttpURLConnection httpConn = null;
                try {
                    // establish http connection
                    httpConn = connect(url);
                    httpConn.setRequestProperty(HTTP_RANGE_HEADER_KEY,
                            String.format(HTTP_RANGE_HEADER_FORMAT, rangeStart, rangeEnd));
                    int responseCode = httpConn.getResponseCode();

                    // check response code
                    if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                        long downloaded = download(httpConn.getInputStream(), messageDigest);
                        if (downloaded == 0) {
                            // If 0 byte is read, it's fairly certain that the input stream is closed.
                            // Therefore throw IOException to trigger the retry logic.
                            throw new IOException(getErrorString("Failed to read any byte from the stream"));
                        } else {
                            return downloaded;
                        }
                    } else if (responseCode == HttpURLConnection.HTTP_OK) {
                        if (httpConn.getContentLengthLong() < rangeEnd) {
                            String errMsg = String.format(
                                    "Artifact size mismatch. Expected artifact size %d. HTTP contentLength %d",
                                    rangeEnd, httpConn.getContentLengthLong());
                            throw new PackageDownloadException(getErrorString(errMsg));
                        }
                        // 200 means server doesn't recognize the Range header and returns all contents.
                        // try to discard the offset number of bytes.
                        InputStream inputStream = httpConn.getInputStream();
                        long byteSkipped = inputStream.skip(rangeStart);
                        // If number of bytes skipped is less than declared, throw error.
                        if (byteSkipped != rangeStart) {
                            throw new PackageDownloadException(getErrorString("Reach the end of the stream"));
                        }
                        long downloaded = download(inputStream, messageDigest);
                        if (downloaded == 0) {
                            // If 0 byte is read, it's fairly certain that the inputStream is closed.
                            // Therefore throw IOException to trigger the retry logic.
                            throw new IOException("Failed to read any byte from the inputStream");
                        } else {
                            return downloaded;
                        }
                    } else {
                        throw new PackageDownloadException(getErrorString(
                                "Unable to download Greengrass artifact. HTTP Error: " + responseCode));
                    }
                } finally {
                    if (httpConn != null) {
                        httpConn.disconnect();
                    }
                }
            }, "download-artifact", logger);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new PackageDownloadException(getErrorString("Failed to download the artifact"), e);
        }
    }

    @Override
    public Optional<String> checkDownloadable() {
        return Optional.ofNullable(clientFactory.getConfigValidationError());
    }

    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.AvoidRethrowingException"})
    private URL getArtifactDownloadURL(ComponentIdentifier componentIdentifier, String artifactName)
            throws InterruptedException, PackageDownloadException {
        String arn;
        try {
            arn = componentStore.getRecipeMetadata(componentIdentifier).getComponentVersionArn();
        } catch (PackageLoadingException e) {
            throw new PackageDownloadException(
                    getErrorString("Failed to get component version arn from component store"), e);
        }

        try {
            return RetryUtils.runWithRetry(clientExceptionRetryConfig, () -> {
                GetComponentVersionArtifactRequest getComponentArtifactRequest =
                        GetComponentVersionArtifactRequest.builder().artifactName(artifactName).arn(arn).build();
                GetComponentVersionArtifactResponse getComponentArtifactResult =
                        clientFactory.getCmsClient().getComponentVersionArtifact(getComponentArtifactRequest);
                return new URL(getComponentArtifactResult.preSignedUrl());
            }, "get-artifact-size", logger);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new PackageDownloadException(getErrorString("Failed to get download size"), e);
        }
    }

    HttpURLConnection connect(URL url) throws IOException {
        return (HttpURLConnection) url.openConnection();
    }
}
