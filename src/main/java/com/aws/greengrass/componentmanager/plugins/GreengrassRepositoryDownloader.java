/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.evergreen.AWSEvergreen;
import com.amazonaws.services.evergreen.model.GetComponentVersionArtifactRequest;
import com.amazonaws.services.evergreen.model.GetComponentVersionArtifactResult;
import com.aws.greengrass.componentmanager.GreengrassComponentServiceClientFactory;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.util.Pair;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import javax.inject.Inject;

public class GreengrassRepositoryDownloader extends ArtifactDownloader {
    private static final String HTTP_HEADER_CONTENT_DISPOSITION = "Content-Disposition";

    private final AWSEvergreen evgCmsClient;
    private Long artifactSize = null;
    private String artifactFilename = null;

    @Inject
    protected GreengrassRepositoryDownloader(GreengrassComponentServiceClientFactory clientFactory,
                                          ComponentIdentifier identifier, ComponentArtifact artifact,
                                          Path artifactDir) {
        super(identifier, artifact, artifactDir);
        this.evgCmsClient = clientFactory.getCmsClient();
    }

    // TODO: avoid calling cloud to get artifact file name.
    @Override
    protected String getArtifactFilenameNoRetry() throws PackageDownloadException, RetryableException {
        if (artifactFilename != null) {
            return artifactFilename;
        }
        retrieveArtifactInfo();
        return this.artifactFilename;
    }

    @Override
    protected Long getDownloadSizeNoRetry() throws PackageDownloadException, RetryableException {
        if (artifactSize != null) {
            return artifactSize;
        }
        retrieveArtifactInfo();
        return artifactSize;
    }

    @Override
    public Pair<InputStream, Runnable> readWithRange(long start, long end)
            throws PackageDownloadException, RetryableException {
        URL url = getArtifactDownloadURL(identifier, artifact.getArtifactUri().getSchemeSpecificPart());

        // establish http connection
        HttpURLConnection httpConn = null;
        int responseCode;
        try {
            httpConn = connect(url);
            httpConn.setRequestProperty(HTTP_RANGE_HEADER_KEY, String.format(HTTP_RANGE_HEADER_FORMAT, start, end));
            responseCode = httpConn.getResponseCode();
        } catch (IOException e) {
            if (httpConn != null) {
                httpConn.disconnect();
            }
            throw new RetryableException("error establish connect", e);
        }

        // get http response code
        try {
            if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                return new Pair<>(httpConn.getInputStream(), httpConn::disconnect);
            } else if (responseCode == HttpURLConnection.HTTP_OK) {
                // 200 means server doesn't recognize the Range header and returns all contents.
                // try to discard the offset number of bytes.
                InputStream inputStream = httpConn.getInputStream();
                long byteSkipped = inputStream.skip(start);

                // If number of bytes skipped is less than declared, throw error.
                if (byteSkipped != start) {
                    httpConn.disconnect();
                    throw new RetryableException("Unable to get partial content");
                }
                return new Pair<>(inputStream, httpConn::disconnect);
            } else if (responseCode == HttpURLConnection.HTTP_CLIENT_TIMEOUT) {
                httpConn.disconnect();
                throw new RetryableException("HTTP Error: " + responseCode);
            } else {
                httpConn.disconnect();
                throw new PackageDownloadException("Unable to download greengrass artifact. HTTP Error: "
                        + responseCode);
            }
        } catch (IOException ioException) {
            httpConn.disconnect();
            throw new RetryableException("Unable to get http input stream", ioException);
        }
    }

    // TODO: remove this overriding function once GGRepositoryDownloader doesn't need to call cloud to get
    // artifact file name.
    @Override
    public File getArtifactFile() {
        // GG_NEEDS_REVIEW: TODO : In the download from cloud step we rely on the content-disposition header to get the
        //  file name and that's the accurate name, but here we're only using the scheme specific part
        //  of the URI when we don't find the file in cloud, we need to follow up on what is the
        //  right way to get file name
        if (artifactFilename != null) {
            return artifactDir.resolve(artifactFilename).toFile();
        }
        try {
            return artifactDir.resolve(getArtifactFilenameNoRetry()).toFile();
        } catch (PackageDownloadException e) {
            logger.atWarn().log("Error in getting file name from HTTP response,"
                    + " getting local file name from URI scheme specific part", e);
            artifactFilename = artifact.getArtifactUri().getSchemeSpecificPart();
            return artifactDir.resolve(artifactFilename).toFile();
        } catch (RetryableException e) {
            logger.atWarn().log("Error in getting file name from HTTP response: {},"
                    + " getting local file name from URI scheme specific part", e.getMessage());
            return artifactDir.resolve(artifact.getArtifactUri().getSchemeSpecificPart()).toFile();
        }
    }

    // TODO: remove this overriding function once GGRepositoryDownloader doesn't need to call cloud to get
    // artifact file name.
    @Override
    public boolean downloadRequired() {
        try {
            // Override parent's behavior of checking local file from getArtifactFileName()
            // In GreengrassRepositoryDownloader, getArtifactFileName() requires calling cloud and may
            // throw exception.
            File localFile = getArtifactFile();
            return !artifactExistsAndChecksum(artifact, localFile.toPath());
        } catch (PackageDownloadException e) {
            return true;
        }
    }

    private void retrieveArtifactInfo() throws RetryableException, PackageDownloadException {
        if (artifactSize != null && artifactFilename != null) {
            return;
        }
        URL url = getArtifactDownloadURL(identifier, artifact.getArtifactUri().getSchemeSpecificPart());

        HttpURLConnection httpConn = null;
        try {
            httpConn = connect(url);
            int responseCode = httpConn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                long length = httpConn.getContentLengthLong();
                if (length == -1) {
                    throw new PackageDownloadException("Failed to get download size");
                }
                // GG_NEEDS_REVIEW: TODO can we simplify getting filename without network request
                String disposition = httpConn.getHeaderField(HTTP_HEADER_CONTENT_DISPOSITION);
                this.artifactSize = length;
                this.artifactFilename = extractFilename(url, disposition);
            } else {
                throw new PackageDownloadException("Failed to check greengrass artifact. HTTP response: "
                        + responseCode);
            }
        } catch (IOException e) {
            throw new RetryableException("Failed to check greengrass artifact.", e);
        } finally {
            if (httpConn != null) {
                httpConn.disconnect();
            }
        }
    }

    private URL getArtifactDownloadURL(ComponentIdentifier componentIdentifier, String artifactName)
            throws RetryableException, PackageDownloadException {
        GetComponentVersionArtifactRequest getComponentArtifactRequest =
                new GetComponentVersionArtifactRequest().withArtifactName(artifactName)
                        .withComponentName(componentIdentifier.getName())
                        .withComponentVersion(componentIdentifier.getVersion().toString());

        String preSignedUrl;
        try {
            GetComponentVersionArtifactResult getComponentArtifactResult =
                    evgCmsClient.getComponentVersionArtifact(getComponentArtifactRequest);
            preSignedUrl = getComponentArtifactResult.getPreSignedUrl();
        } catch (AmazonClientException ace) {
            String errorMsg = getErrorString("error in get artifact download URL");
            if (ace.isRetryable()) {
                throw new RetryableException(errorMsg, ace);
            }
            throw new PackageDownloadException(errorMsg, ace);
        }
        try {
            return new URL(preSignedUrl);
        } catch (MalformedURLException e) {
            throw new PackageDownloadException("Malformed artifact URL", e);
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
