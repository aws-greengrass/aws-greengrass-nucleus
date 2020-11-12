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
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import javax.inject.Inject;

public class GreengrassRepositoryDownloader extends ArtifactDownloader {
    private static final Logger logger = LogManager.getLogger(GreengrassRepositoryDownloader.class);
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
        String filename = getFilename(artifact);
        return !artifactExistsAndChecksum(artifact, saveToPath.resolve(filename));
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
                    String filename = getFilename(artifact);

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
                // TODO: [P41214764]: Handle all status codes in downloading greengrass: artifacts
            } finally {
                httpConn.disconnect();
            }
        } catch (PackageDownloadException e) {
            if (!saveToPath.resolve(getFilename(artifact)).toFile().exists()) {
                throw e;
            }
            logger.atInfo("download-artifact-from-greengrass-repo")
                    .addKeyValue(COMPONENT_IDENTIFIER_LOG_KEY, componentIdentifier)
                    .addKeyValue(ARTIFACT_URI_LOG_KEY, artifact.getArtifactUri())
                    .log("Failed to download artifact, but found it locally, using that version", e);
            return saveToPath.resolve(getFilename(artifact)).toFile();
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
        return artifactDir.resolve(getFilename(artifact)).toFile();
    }

    String getFilename(ComponentArtifact artifact) {
        String ssp = artifact.getArtifactUri().getSchemeSpecificPart();
        return Objects.toString(Paths.get(ssp).getFileName());
    }

    HttpURLConnection connect(URL url) throws IOException {
        return (HttpURLConnection) url.openConnection();
    }

    String getArtifactDownloadURL(ComponentIdentifier componentIdentifier, String artifactName)
            throws PackageDownloadException {
        GetComponentVersionArtifactRequest getComponentArtifactRequest =
                new GetComponentVersionArtifactRequest().withArtifactName(artifactName)
                        .withComponentName(componentIdentifier.getName())
                        .withComponentVersion(componentIdentifier.getVersion().toString());

        try {
            GetComponentVersionArtifactResult getComponentArtifactResult =
                    evgCmsClient.getComponentVersionArtifact(getComponentArtifactRequest);
            return getComponentArtifactResult.getPreSignedUrl();
        } catch (AmazonClientException ace) {
            // TODO: [P41215221]: Properly handle all retryable/nonretryable exceptions
            throw new PackageDownloadException(
                    String.format(ARTIFACT_DOWNLOAD_EXCEPTION_PMS_FMT, artifactName, componentIdentifier.getName(),
                            componentIdentifier.getVersion().toString()), ace);
        }
    }
}
