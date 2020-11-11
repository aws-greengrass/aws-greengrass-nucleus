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
import com.aws.greengrass.util.CrashableSupplier;
import com.aws.greengrass.util.Pair;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import javax.inject.Inject;

public class GreengrassRepositoryDownloader extends ArtifactDownloader {
    @SuppressFBWarnings({"MS_SHOULD_BE_FINAL"})
    protected static int MAX_RETRY = 5;

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
    protected String getArtifactFilename() throws PackageDownloadException {
        if (artifactFilename != null) {
            return artifactFilename;
        }
        retrieveArtifactInfo();
        return this.artifactFilename;
    }

    @Override
    public Long getDownloadSize() throws PackageDownloadException {
        if (artifactSize != null) {
            return artifactSize;
        }
        retrieveArtifactInfo();
        return artifactSize;
    }

    @Override
    public Pair<InputStream, Runnable> readWithRange(long start, long end)
            throws PackageDownloadException {
        URL url = getArtifactDownloadURL(identifier, artifact.getArtifactUri().getSchemeSpecificPart());

        return runWithRetry("establish HTTP connection", () -> {
            HttpURLConnection httpConn = null;
            InputStream inputStreamResult = null;

            try {
                // establish http connection
                httpConn = connect(url);
                httpConn.setRequestProperty(HTTP_RANGE_HEADER_KEY,
                        String.format(HTTP_RANGE_HEADER_FORMAT, start, end));
                int responseCode = httpConn.getResponseCode();

                // check response code
                if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    inputStreamResult = httpConn.getInputStream();
                } else if (responseCode == HttpURLConnection.HTTP_OK) {
                    // 200 means server doesn't recognize the Range header and returns all contents.
                    // try to discard the offset number of bytes.
                    InputStream inputStream = httpConn.getInputStream();
                    long byteSkipped = inputStream.skip(start);

                    // If number of bytes skipped is less than declared, throw error.
                    if (byteSkipped != start) {
                        throw new IOException("Unable to get partial content");
                    }
                    inputStreamResult = inputStream;
                } else if (responseCode == HttpURLConnection.HTTP_CLIENT_TIMEOUT) {
                    throw new IOException("HTTP Error: " + responseCode);
                } else {
                    throw new PackageDownloadException(getErrorString("Unable to download greengrass artifact. "
                            + "HTTP Error: " + responseCode));
                }
                return new Pair<InputStream, Runnable>(inputStreamResult, httpConn::disconnect);
            } finally {
                // if no input stream result is provided, disconnect http connection
                if (inputStreamResult == null) {
                    httpConn.disconnect();
                }
            }
        });
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
            return artifactDir.resolve(getArtifactFilename()).toFile();
        } catch (PackageDownloadException e) {
            logger.atWarn().log("Error in getting file name from HTTP response,"
                    + " getting local file name from URI scheme specific part", e);
            artifactFilename = artifact.getArtifactUri().getSchemeSpecificPart();
            return artifactDir.resolve(artifactFilename).toFile();
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

    private void retrieveArtifactInfo() throws PackageDownloadException {
        if (artifactSize != null && artifactFilename != null) {
            return;
        }
        URL url = getArtifactDownloadURL(identifier, artifact.getArtifactUri().getSchemeSpecificPart());

        runWithRetry("get-artifact-info", () -> {
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
            } finally {
                if (httpConn != null) {
                    httpConn.disconnect();
                }
            }
            return null;
        });
    }

    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.AvoidRethrowingException"})
    protected <T> T runWithRetry(String taskDescription, CrashableSupplier<T, Exception> taskToRetry)
            throws PackageDownloadException {
        int retryInterval = INIT_RETRY_INTERVAL_MILLI;
        int retry = 0;
        IOException retryableException = null;
        while (retry < MAX_RETRY) {
            retry++;
            try {
                return taskToRetry.apply();
            } catch (IOException e) {
                logger.atInfo().kv("exception", e.getMessage()).log("Retry " + taskDescription);
                retryableException = e;
                if (retry >= MAX_RETRY) {
                    break;
                }
                try {
                    Thread.sleep(retryInterval);
                    if (retryInterval < MAX_RETRY_INTERVAL_MILLI) {
                        retryInterval = retryInterval * 2;
                    } else {
                        retryInterval = MAX_RETRY_INTERVAL_MILLI;
                    }
                } catch (InterruptedException ie) {
                    logger.atInfo().log("Interrupted while waiting to retry " + taskDescription);
                    return null;
                }
            } catch (PackageDownloadException e) {
                throw e;
            } catch (Exception e) {
                throw new PackageDownloadException("Unexpected error in " + taskDescription, e);
            }
        }
        throw new PackageDownloadException(
                String.format("Fail to execute %s after retrying %d times", taskDescription, MAX_RETRY),
                retryableException);
    }

    private URL getArtifactDownloadURL(ComponentIdentifier componentIdentifier, String artifactName)
            throws PackageDownloadException {
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
