/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.builtins;

import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.util.GreengrassServiceClientFactory;
import com.aws.greengrass.util.ProxyUtils;
import com.aws.greengrass.util.RetryUtils;
import lombok.AccessLevel;
import lombok.Setter;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.HttpStatusCode;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.greengrassv2data.model.GetComponentVersionArtifactRequest;
import software.amazon.awssdk.services.greengrassv2data.model.GetComponentVersionArtifactResponse;
import software.amazon.awssdk.services.greengrassv2data.model.GreengrassV2DataException;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.DOWNLOAD_GREENGRASS_ARTIFACT_ERROR;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.GET_COMPONENT_VERSION_ARTIFACT_ACCESS_DENIED;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.GET_GREENGRASS_ARTIFACT_SIZE_ERROR;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.GREENGRASS_ARTIFACT_SIZE_NOT_FOUND;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.HTTP_REQUEST_ERROR;

public class GreengrassRepositoryDownloader extends ArtifactDownloader {
    static final String CONTENT_LENGTH_HEADER = "content-length";
    private final ComponentStore componentStore;
    private final GreengrassServiceClientFactory clientFactory;
    private Long artifactSize = null;
    // Setter for unit test
    @Setter(AccessLevel.PACKAGE)
    private RetryUtils.RetryConfig clientExceptionRetryConfig =
            RetryUtils.RetryConfig.builder().initialRetryInterval(Duration.ofMinutes(1L))
                    .maxRetryInterval(Duration.ofMinutes(1L)).maxAttempt(Integer.MAX_VALUE)
                    .retryableExceptions(Arrays.asList(SdkClientException.class, IOException.class,
                            DeviceConfigurationException.class)).build();

    protected GreengrassRepositoryDownloader(GreengrassServiceClientFactory clientFactory,
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
        String url = getArtifactDownloadURL(identifier, artifact.getArtifactUri().getSchemeSpecificPart());

        try (SdkHttpClient client = getSdkHttpClient()) {
            HttpExecuteRequest executeRequest = HttpExecuteRequest.builder()
                    .request(SdkHttpFullRequest.builder().uri(URI.create(url)).method(SdkHttpMethod.GET).build())
                    .build();
            HttpExecuteResponse executeResponse = client.prepareRequest(executeRequest).call();

            int responseCode = executeResponse.httpResponse().statusCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                long length = getContentLengthLong(executeResponse.httpResponse());

                if (length == -1) {
                    throw new PackageDownloadException(getErrorString("Failed to get download size"),
                            GREENGRASS_ARTIFACT_SIZE_NOT_FOUND);
                }
                return length;
            } else {
                throw new PackageDownloadException(
                        getErrorString("Failed to get download size. HTTP response: " + responseCode),
                        Arrays.asList(GET_GREENGRASS_ARTIFACT_SIZE_ERROR, HTTP_REQUEST_ERROR));
            }
        }
    }

    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.AvoidRethrowingException"})
    @Override
    protected long download(long rangeStart, long rangeEnd, MessageDigest messageDigest)
            throws PackageDownloadException, InterruptedException {
        String url = getArtifactDownloadURL(identifier, artifact.getArtifactUri().getSchemeSpecificPart());

        try {
            return RetryUtils.runWithRetry(clientExceptionRetryConfig, () -> {
                try (SdkHttpClient client = getSdkHttpClient()) {
                    HttpExecuteRequest executeRequest = HttpExecuteRequest.builder().request(
                            SdkHttpFullRequest.builder().uri(URI.create(url)).method(SdkHttpMethod.GET)
                                    .putHeader(HTTP_RANGE_HEADER_KEY,
                                            String.format(HTTP_RANGE_HEADER_FORMAT, rangeStart, rangeEnd)).build())
                            .build();
                    HttpExecuteResponse executeResponse = client.prepareRequest(executeRequest).call();

                    int responseCode = executeResponse.httpResponse().statusCode();

                    // check response code
                    if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                        try (InputStream inputStream = executeResponse.responseBody().get()) {
                            long downloaded = download(inputStream, messageDigest);
                            if (downloaded == 0) {
                                // If 0 byte is read, it's fairly certain that the input stream is closed.
                                // Therefore throw IOException to trigger the retry logic.
                                throw new IOException(getErrorString("Failed to read any byte from the stream"));
                            } else {
                                return downloaded;
                            }
                        }
                    } else if (responseCode == HttpURLConnection.HTTP_OK) {
                        long length = getContentLengthLong(executeResponse.httpResponse());
                        if (length < rangeEnd) {
                            String errMsg = String.format(
                                    "Artifact size mismatch. Expected artifact size %d. HTTP contentLength %d",
                                    rangeEnd, length);
                            throw new PackageDownloadException(getErrorString(errMsg),
                                    Arrays.asList(DOWNLOAD_GREENGRASS_ARTIFACT_ERROR, HTTP_REQUEST_ERROR));
                        }
                        // 200 means server doesn't recognize the Range header and returns all contents.
                        // try to discard the offset number of bytes.
                        try (InputStream inputStream = executeResponse.responseBody().get()) {
                            long byteSkipped = inputStream.skip(rangeStart);
                            // If number of bytes skipped is less than declared, throw error.
                            if (byteSkipped != rangeStart) {
                                throw new PackageDownloadException(getErrorString("Reach the end of the stream"),
                                        Arrays.asList(DOWNLOAD_GREENGRASS_ARTIFACT_ERROR, HTTP_REQUEST_ERROR));
                            }
                            long downloaded = download(inputStream, messageDigest);
                            if (downloaded == 0) {
                                // If 0 byte is read, it's fairly certain that the inputStream is closed.
                                // Therefore throw IOException to trigger the retry logic.
                                throw new IOException("Failed to read any byte from the inputStream");
                            } else {
                                return downloaded;
                            }
                        }
                    } else {
                        throw new PackageDownloadException(
                                getErrorString("Unable to download Greengrass artifact. HTTP Error: " + responseCode),
                                Arrays.asList(DOWNLOAD_GREENGRASS_ARTIFACT_ERROR, HTTP_REQUEST_ERROR));
                    }
                }
            }, "download-artifact", logger);
        } catch (InterruptedException | PackageDownloadException e) {
            throw e;
        } catch (Exception e) {
            throw new PackageDownloadException(getErrorString("Failed to download Greengrass artifact"), e);
        }
    }

    @Override
    public Optional<String> checkDownloadable() {
        return Optional.ofNullable(clientFactory.getConfigValidationError());
    }

    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.AvoidRethrowingException"})
    private String getArtifactDownloadURL(ComponentIdentifier componentIdentifier, String artifactName)
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
                        clientFactory.fetchGreengrassV2DataClient()
                                .getComponentVersionArtifact(getComponentArtifactRequest);
                return getComponentArtifactResult.preSignedUrl();
            }, "get-artifact-size", logger);
        } catch (InterruptedException e) {
            throw e;
        } catch (GreengrassV2DataException e) {
            if (e.statusCode() == HttpStatusCode.FORBIDDEN) {
                throw new PackageDownloadException(getErrorString("Failed to get the pre-signed url of a public or a "
                        + "Lambda component artifact. GetComponentVersionArtifact returns 403 Access Denied."),
                        e).withErrorContext(GreengrassV2DataException.class,
                        GET_COMPONENT_VERSION_ARTIFACT_ACCESS_DENIED);
            }
            throw new PackageDownloadException(getErrorString("Failed to get the pre-signed url of a public or a "
                    + "Lambda component artifact. GetComponentVersionArtifact failed."), e);
        } catch (Exception e) {
            throw new PackageDownloadException(
                    getErrorString("Failed to get the pre-signed url of a public or a " + "Lambda component artifact"),
                    e);
        }
    }

    SdkHttpClient getSdkHttpClient() {
        return ProxyUtils.getSdkHttpClient();
    }

    private long getContentLengthLong(SdkHttpResponse sdkHttpResponse) {
        long length = -1L;
        Optional<String> value = sdkHttpResponse.firstMatchingHeader(CONTENT_LENGTH_HEADER);
        try {
            if (value.isPresent()) {
                length = Long.parseLong(value.get());
            }
        } catch (NumberFormatException e) {
            logger.atError().log("Failed to parse content-length from http response", e);
        }
        return length;
    }
}
