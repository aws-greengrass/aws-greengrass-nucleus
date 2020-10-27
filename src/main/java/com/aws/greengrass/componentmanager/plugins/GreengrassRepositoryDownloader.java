/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.evergreen.AWSEvergreen;
import com.amazonaws.services.evergreen.model.GetComponentArtifactRequest;
import com.amazonaws.services.evergreen.model.GetComponentArtifactResult;
import com.aws.greengrass.componentmanager.GreengrassComponentServiceClientFactory;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.util.Map;
import javax.inject.Inject;

public class GreengrassRepositoryDownloader extends ArtifactDownloader {
    private static final Logger logger = LogManager.getLogger(GreengrassRepositoryDownloader.class);
    private static final String HTTP_HEADER_CONTENT_DISPOSITION = "Content-Disposition";
    private static final String HTTP_HEADER_LOCATION = "Location";
    private static final String ARTIFACT_DOWNLOAD_EXCEPTION_PMS_FMT =
            "Failed to download artifact %s for package %s-%s";
    public static final String ARTIFACT_URI_LOG_KEY = "artifactUri";
    public static final String COMPONENT_IDENTIFIER_LOG_KEY = "componentIdentifier";

    private final AWSEvergreen evgCmsClient;

    @Inject
    public GreengrassRepositoryDownloader(GreengrassComponentServiceClientFactory clientFactory) {
        super();
        this.evgCmsClient = clientFactory.getCmsClient();
    }

    @Override
    public boolean downloadRequired(ComponentIdentifier componentIdentifier, ComponentArtifact artifact,
                                    Path saveToPath) throws PackageDownloadException {
        // GG_NEEDS_REVIEW: TODO can we simplify getting filename without network request
        try {
            String preSignedUrl =
                    getArtifactDownloadURL(componentIdentifier, artifact.getArtifactUri().getSchemeSpecificPart());
            URL url = new URL(preSignedUrl);
            HttpURLConnection httpConn = connect(url);
            try {
                int responseCode = httpConn.getResponseCode();

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    String disposition = httpConn.getHeaderField(HTTP_HEADER_CONTENT_DISPOSITION);
                    String filename = extractFilename(url, disposition);
                    return !artifactExistsAndChecksum(artifact, saveToPath.resolve(filename));
                }
            } finally {
                if (httpConn != null) {
                    httpConn.disconnect();
                }
            }
        } catch (IOException e) {
            throw new PackageDownloadException("Failed to check greengrass artifact", e);
        } catch (PackageDownloadException e) {
            if (!saveToPath.resolve(artifact.getArtifactUri().getSchemeSpecificPart()).toFile().exists()) {
                throw e;
            }
            logger.atInfo("download-required-from-greengrass-repo")
                    .addKeyValue(COMPONENT_IDENTIFIER_LOG_KEY, componentIdentifier)
                    .addKeyValue(ARTIFACT_URI_LOG_KEY, artifact.getArtifactUri())
                    .log("Failed to download artifact, but found it locally, using that version", e);
            return false;
        }
        return true;
    }

    @Override
    public File downloadToPath(ComponentIdentifier componentIdentifier, ComponentArtifact artifact, Path saveToPath)
            throws IOException, PackageDownloadException {
        logger.atInfo().setEventType("download-artifact-from-greengrass-repo")
                .addKeyValue(COMPONENT_IDENTIFIER_LOG_KEY, componentIdentifier)
                .addKeyValue(ARTIFACT_URI_LOG_KEY, artifact.getArtifactUri().toString()).log();

        try {
            String preSignedUrl =
                    getArtifactDownloadURL(componentIdentifier, artifact.getArtifactUri().getSchemeSpecificPart());
            URL url = new URL(preSignedUrl);
            HttpURLConnection httpConn = connect(url);

            try {
                int responseCode = httpConn.getResponseCode();

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    String disposition = httpConn.getHeaderField(HTTP_HEADER_CONTENT_DISPOSITION);
                    String filename = extractFilename(url, disposition);

                    try (InputStream inputStream = httpConn.getInputStream()) {
                        if (artifactExistsAndChecksum(artifact, saveToPath.resolve(filename))) {
                            logger.atDebug().addKeyValue("artifact", artifact.getArtifactUri())
                                    .log("Artifact already exists, skipping download");
                        } else {
                            checkIntegrityAndSaveToStore(inputStream, artifact, componentIdentifier,
                                    saveToPath.resolve(filename));
                        }
                    }
                    return saveToPath.resolve(filename).toFile();
                }
                // GG_NEEDS_REVIEW: TODO handle the other status code
            } finally {
                if (httpConn != null) {
                    httpConn.disconnect();
                }
            }
        } catch (PackageDownloadException e) {
            if (!saveToPath.resolve(artifact.getArtifactUri().getSchemeSpecificPart()).toFile().exists()) {
                throw e;
            }
            logger.atInfo("download-artifact-from-greengrass-repo")
                    .addKeyValue(COMPONENT_IDENTIFIER_LOG_KEY, componentIdentifier)
                    .addKeyValue(ARTIFACT_URI_LOG_KEY, artifact.getArtifactUri())
                    .log("Failed to download artifact, but found it locally, using that version", e);
            // GG_NEEDS_REVIEW: TODO : In the download from cloud step we rely on the content-disposition header
            // to get the file name and that's the accurate name, but here we're only using the scheme specific part
            //  of the URI when we don't find the file in cloud, we need to follow up on what is the
            //  right way to get file name
            return saveToPath.resolve(artifact.getArtifactUri().getSchemeSpecificPart()).toFile();
        }
        return null;
    }

    @Override
    public long getDownloadSize(ComponentIdentifier componentIdentifier, ComponentArtifact artifact, Path saveToPath)
            throws PackageDownloadException {
        logger.atInfo().setEventType("get-download-size-from-greengrass-repo")
                .addKeyValue(COMPONENT_IDENTIFIER_LOG_KEY, componentIdentifier)
                .addKeyValue(ARTIFACT_URI_LOG_KEY, artifact.getArtifactUri().toString()).log();

        try {
            String preSignedUrl =
                    getArtifactDownloadURL(componentIdentifier, artifact.getArtifactUri().getSchemeSpecificPart());
            URL url = new URL(preSignedUrl);
            HttpURLConnection conn = connect(url);
            long length = conn.getContentLengthLong();
            if (length == -1) {
                throw new PackageDownloadException("Failed to get download size");
            }
            return length;
        } catch (IOException e) {
            throw new PackageDownloadException("Failed to get download size", e);
        }
    }

    @Override
    public File getArtifactFile(Path artifactDir, ComponentArtifact artifact, ComponentIdentifier componentIdentifier) {
        // GG_NEEDS_REVIEW: TODO : In the download from cloud step we rely on the content-disposition header to get the
        //  file name and that's the accurate name, but here we're only using the scheme specific part
        //  of the URI when we don't find the file in cloud, we need to follow up on what is the
        //  right way to get file name
        return artifactDir.resolve(artifact.getArtifactUri().getSchemeSpecificPart()).toFile();
    }

    HttpURLConnection connect(URL url) throws IOException {
        return (HttpURLConnection) url.openConnection();
    }

    String getArtifactDownloadURL(ComponentIdentifier componentIdentifier, String artifactName)
            throws PackageDownloadException {
        GetComponentArtifactRequest getComponentArtifactRequest =
                new GetComponentArtifactRequest().withArtifactName(artifactName)
                        .withComponentName(componentIdentifier.getName())
                        .withComponentVersion(componentIdentifier.getVersion().toString());

        // GG_NEEDS_REVIEW: TODO: This is horribly bad code, but unfortunately, the service is configured to return
        // 302 redirect and the auto-generated SDK does NOT like that. The only way to handle this at the moment is to
        // catch the exception for the redirect. This response code needs a revisit from the service side either to
        // change the response code or to gracefully respond instead of throwing exception
        try {
            GetComponentArtifactResult getComponentArtifactResult =
                    evgCmsClient.getComponentArtifact(getComponentArtifactRequest);
            return getComponentArtifactResult.getRedirectUrl();
        } catch (AmazonServiceException ase) {
            // GG_NEEDS_REVIEW: TODO: This should be expanded to handle various retryable/non-retryable exceptions
            // Ideally service side response is fixed and this can be merged with the Client Exception handling
            // section below
            int responseStatus = ase.getStatusCode();
            Map<String, String> headers = ase.getHttpHeaders();
            if (responseStatus != HttpURLConnection.HTTP_MOVED_TEMP || !headers.containsKey(HTTP_HEADER_LOCATION)) {
                throw new PackageDownloadException(
                        String.format(ARTIFACT_DOWNLOAD_EXCEPTION_PMS_FMT, artifactName, componentIdentifier.getName(),
                                componentIdentifier.getVersion().toString()), ase);
            }
            return headers.get(HTTP_HEADER_LOCATION);
        } catch (AmazonClientException ace) {
            // GG_NEEDS_REVIEW: TODO: This should be expanded to handle various retryable/non-retryable exceptions
            throw new PackageDownloadException(
                    String.format(ARTIFACT_DOWNLOAD_EXCEPTION_PMS_FMT, artifactName, componentIdentifier.getName(),
                            componentIdentifier.getVersion().toString()), ace);
        }
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
