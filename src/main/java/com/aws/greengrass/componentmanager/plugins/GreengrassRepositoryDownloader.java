/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.evergreen.model.GetComponentVersionArtifactDeprecatedRequest;
import com.amazonaws.services.evergreen.model.GetComponentVersionArtifactDeprecatedResult;
import com.aws.greengrass.componentmanager.GreengrassComponentServiceClientFactory;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.security.MessageDigest;

public class GreengrassRepositoryDownloader extends ArtifactDownloader {

    private static final String HTTP_HEADER_CONTENT_DISPOSITION = "Content-Disposition";

    private final GreengrassComponentServiceClientFactory clientFactory;
    private Long artifactSize = null;
    private String artifactFilename = null;

    protected GreengrassRepositoryDownloader(GreengrassComponentServiceClientFactory clientFactory,
                                          ComponentIdentifier identifier, ComponentArtifact artifact,
                                          Path artifactDir) {
        super(identifier, artifact, artifactDir);
        this.clientFactory = clientFactory;
    }

    // TODO: avoid calling cloud to get artifact file name.
    @Override
    protected String getArtifactFilename() throws PackageDownloadException, InterruptedException {
        if (artifactFilename != null) {
            return artifactFilename;
        }
        retrieveArtifactInfo();
        return this.artifactFilename;
    }

    @Override
    public Long getDownloadSize() throws PackageDownloadException, InterruptedException {
        if (artifactSize != null) {
            return artifactSize;
        }
        retrieveArtifactInfo();
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

    // TODO: remove this overriding function once GGRepositoryDownloader doesn't need to call cloud to get
    // artifact file name.
    @Override
    public boolean downloadRequired() throws PackageDownloadException, InterruptedException {
        try {
            // Override parent's behavior of checking local file from getArtifactFileName()
            // In GreengrassRepositoryDownloader, getArtifactFileName() requires calling cloud and may
            // throw exception.
            File localFile = getArtifactFile();
            return !artifactExistsAndChecksum(artifact, localFile.toPath());
        } catch (PackageDownloadException e) {
            artifactFilename = artifact.getArtifactUri().getSchemeSpecificPart();
            File localArtifactFile = artifactDir.resolve(artifactFilename).toFile();
            if (!localArtifactFile.exists()) {
                throw e;
            }
            artifact.setFileName(artifactFilename);
            return !artifactExistsAndChecksum(artifact, localArtifactFile.toPath());
        }
    }

    private void retrieveArtifactInfo() throws PackageDownloadException, InterruptedException {
        if (artifactSize != null && artifactFilename != null) {
            return;
        }
      
        // TODO remove after data plane switching to new GCS API
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
                    String disposition = httpConn.getHeaderField(HTTP_HEADER_CONTENT_DISPOSITION);
                    String filename = extractFilename(url, disposition);

                    // GG_NEEDS_REVIEW: TODO can we simplify getting filename without network request
                    this.artifactSize = length;
                    this.artifactFilename = filename;
                    artifact.setFileName(filename);
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
    }

    private URL getArtifactDownloadURL(ComponentIdentifier componentIdentifier, String artifactName)
            throws PackageDownloadException {
        GetComponentVersionArtifactDeprecatedRequest getComponentArtifactRequest =
                new GetComponentVersionArtifactDeprecatedRequest().withArtifactName(artifactName)
                        .withComponentName(componentIdentifier.getName())
                        .withComponentVersion(componentIdentifier.getVersion().toString());

        String preSignedUrl;
        try {
            GetComponentVersionArtifactDeprecatedResult getComponentArtifactResult =
                    clientFactory.getCmsClient().getComponentVersionArtifactDeprecated(getComponentArtifactRequest);
            preSignedUrl = getComponentArtifactResult.getPreSignedUrl();
        } catch (AmazonClientException ace) {
            throw new PackageDownloadException(getErrorString("error in get artifact download URL"), ace);
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

    static String extractFilename(URL preSignedUrl, String contentDisposition) {
        if (contentDisposition != null) {
            String filenameKey = "filename=";
            int index = contentDisposition.indexOf(filenameKey);
            if (index > 0) {
                //extract filename from content, remove double quotes
                return contentDisposition.substring(index + filenameKey.length()).replaceAll("^\"|\"$", "");
            }
        }
        //extract filename from URL
        //URL can contain parameters, such as /filename.txt?sessionId=value
        //extract 'filename.txt' from it
        String[] pathStrings = preSignedUrl.getPath().split("/");
        return pathStrings[pathStrings.length - 1];
    }
}
