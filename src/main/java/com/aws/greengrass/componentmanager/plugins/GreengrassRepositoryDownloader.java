/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins;

import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.GreengrassComponentServiceClientFactory;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.greengrassv2.model.GetComponentVersionArtifactRequest;
import software.amazon.awssdk.services.greengrassv2.model.GetComponentVersionArtifactResponse;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Objects;

public class GreengrassRepositoryDownloader extends ArtifactDownloader {
    private final ComponentStore componentStore;
    private final GreengrassComponentServiceClientFactory clientFactory;
    private Long artifactSize = null;

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
    public Long getDownloadSize() throws PackageDownloadException, InterruptedException {
        if (artifactSize != null) {
            return artifactSize;
        }

        URL url = getArtifactDownloadURL(identifier, artifact.getArtifactUri().getSchemeSpecificPart());

        runWithRetry("get-artifact-info", MAX_RETRY, () -> {
            HttpURLConnection httpConn = null;
            try {
                httpConn = connect(url);
                int responseCode = httpConn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    long length = httpConn.getContentLengthLong();
                    if (length == -1) {
                        throw new PackageDownloadException("Failed to get download size");
                    }
                    this.artifactSize = length;
                } else {
                    throw new PackageDownloadException("Failed to check greengrass artifact. HTTP response: "
                            + responseCode);
                }
            } finally {
                if (httpConn != null) {
                    httpConn.disconnect();
                }
            }
            return null;
        });
        return artifactSize;
    }

    @Override
    protected long download(long rangeStart, long rangeEnd, MessageDigest messageDigest)
            throws PackageDownloadException, InterruptedException {
        URL url = getArtifactDownloadURL(identifier, artifact.getArtifactUri().getSchemeSpecificPart());

        return runWithRetry("establish HTTP connection", MAX_RETRY, () -> {
            HttpURLConnection httpConn = null;
            try {
                // establish http connection
                httpConn = connect(url);
                httpConn.setRequestProperty(HTTP_RANGE_HEADER_KEY,
                        String.format(HTTP_RANGE_HEADER_FORMAT, rangeStart, rangeEnd));
                int responseCode = httpConn.getResponseCode();

                // check response code
                if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    return download(httpConn.getInputStream(), messageDigest);
                } else if (responseCode == HttpURLConnection.HTTP_OK) {
                    if (httpConn.getContentLengthLong() < rangeEnd) {
                        String errMsg = String.format("Artifact size mismatch."
                               + "Expected artifact size %d. HTTP contentLength %d",
                               rangeEnd, httpConn.getContentLengthLong());
                        throw new PackageDownloadException(errMsg);
                    }
                    // 200 means server doesn't recognize the Range header and returns all contents.
                    // try to discard the offset number of bytes.
                    InputStream inputStream = httpConn.getInputStream();
                    long byteSkipped = inputStream.skip(rangeStart);

                    // If number of bytes skipped is less than declared, throw error.
                    if (byteSkipped != rangeStart) {
                        logger.atWarn().log("HTTP Error: " + responseCode);
                        return (long) 0;
                    }
                    return download(inputStream, messageDigest);
                } else if (responseCode == HttpURLConnection.HTTP_CLIENT_TIMEOUT) {
                    logger.atWarn().log("Unable to download greengrass artifact. HTTP Error: " + responseCode);
                    return (long) 0;
                } else {
                    throw new PackageDownloadException(getErrorString("Unable to download greengrass artifact. "
                            + "HTTP Error: " + responseCode));
                }
            } finally {
                if (httpConn != null) {
                    httpConn.disconnect();
                }
            }
        });
    }

    private URL getArtifactDownloadURL(ComponentIdentifier componentIdentifier, String artifactName)
            throws PackageDownloadException {
        String arn;
        try {
            arn = componentStore.getRecipeMetadata(componentIdentifier).getComponentVersionArn();
        } catch (PackageLoadingException e) {
            throw new PackageDownloadException(
                    "Failed to get component version arn from component store. The arn is required for getting artifact"
                            + " from greengrass cloud.",
                    e);
        }

        GetComponentVersionArtifactRequest getComponentArtifactRequest =
                GetComponentVersionArtifactRequest.builder().artifactName(artifactName).arn(arn).build();
        String preSignedUrl;
        try {
            GetComponentVersionArtifactResponse getComponentArtifactResult =
                    clientFactory.getCmsClient().getComponentVersionArtifact(getComponentArtifactRequest);
            preSignedUrl = getComponentArtifactResult.preSignedUrl();
        } catch (SdkClientException e) {
            throw new PackageDownloadException(getErrorString("error in get artifact download URL"), e);
        }
        try {
            return new URL(preSignedUrl);
        } catch (MalformedURLException e) {
            throw new PackageDownloadException(getErrorString("Malformed artifact URL"), e);
        }
    }

    HttpURLConnection connect(URL url) throws IOException {
        return (HttpURLConnection) url.openConnection();
    }
}
