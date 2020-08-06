/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager.plugins;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.evergreen.AWSEvergreen;
import com.amazonaws.services.evergreen.model.GetComponentArtifactRequest;
import com.amazonaws.services.evergreen.model.GetComponentArtifactResult;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.GreengrassPackageServiceClientFactory;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageDownloadException;
import com.aws.iot.evergreen.packagemanager.models.ComponentArtifact;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import javax.inject.Inject;

public class GreengrassRepositoryDownloader implements ArtifactDownloader {
    private static final Logger logger = LogManager.getLogger(GreengrassRepositoryDownloader.class);
    private static final String CONTENT_DISPOSITION = "Content-Disposition";
    private static final String HTTP_HEADER_LOCATION = "Location";
    private static final String ARTIFACT_DOWNLOAD_EXCEPTION_PMS_FMT =
            "Failed to download artifact %s for package %s-%s";

    private final AWSEvergreen evgCmsClient;

    @Inject
    public GreengrassRepositoryDownloader(GreengrassPackageServiceClientFactory clientFactory) {
        this.evgCmsClient = clientFactory.getCmsClient();
    }

    @Override
    public void downloadToPath(PackageIdentifier packageIdentifier, ComponentArtifact artifact, Path saveToPath)
            throws IOException, PackageDownloadException {
        logger.atInfo().setEventType("download-artifact-from-greengrass-repo")
                .addKeyValue("packageIdentifier", packageIdentifier)
                .addKeyValue("artifactUri", artifact.getArtifactUri().toString()).log();

        try {
            String preSignedUrl =
                    getArtifactDownloadURL(packageIdentifier, artifact.getArtifactUri().getSchemeSpecificPart());
            URL url = new URL(preSignedUrl);
            HttpURLConnection httpConn = connect(url);

            try {
                int responseCode = httpConn.getResponseCode();

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    String disposition = httpConn.getHeaderField(CONTENT_DISPOSITION);
                    String filename = extractFilename(url, disposition);

                    try (InputStream inputStream = httpConn.getInputStream()) {
                        Files.copy(inputStream, saveToPath.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                //TODO handle the other status code
            } finally {
                if (httpConn != null) {
                    httpConn.disconnect();
                }
            }
        } catch (PackageDownloadException e) {
            if (!saveToPath.resolve(artifact.getArtifactUri().getSchemeSpecificPart()).toFile().exists()) {
                throw e;
            }
            logger.atInfo("download-artifact-from-greengrass-repo").addKeyValue("packageIdentifier", packageIdentifier)
                    .addKeyValue("artifactUri", artifact.getArtifactUri())
                    .log("Failed to download artifact, but found it locally, using that version", e);
        }
    }

    HttpURLConnection connect(URL url) throws IOException {
        return (HttpURLConnection) url.openConnection();
    }

    String getArtifactDownloadURL(PackageIdentifier packageIdentifier, String artifactName)
            throws PackageDownloadException {
        GetComponentArtifactRequest getComponentArtifactRequest =
                new GetComponentArtifactRequest().withArtifactName(artifactName)
                        .withComponentName(packageIdentifier.getName())
                        .withComponentVersion(packageIdentifier.getVersion().toString())
                        .withScope(packageIdentifier.getScope());

        // TODO: This is horribly bad code, but unfortunately, the service is configured to return 302 redirect and
        // the auto-generated SDK does NOT like that. The only way to handle this at the moment is to catch the
        // exception for the redirect. This response code needs a revisit from the service side either to change the
        // response code or to gracefully respond instead of throwing exception
        try {
            GetComponentArtifactResult getComponentArtifactResult =
                    evgCmsClient.getComponentArtifact(getComponentArtifactRequest);
            return getComponentArtifactResult.getRedirectUrl();
        } catch (AmazonServiceException ase) {
            // TODO: This should be expanded to handle various types of retryable/non-retryable exceptions
            // Ideally service side response is fixed and this can be merged with the Client Exception handling
            // section below
            int responseStatus = ase.getStatusCode();
            Map<String, String> headers = ase.getHttpHeaders();
            if (responseStatus != HttpURLConnection.HTTP_MOVED_TEMP || !headers.containsKey(HTTP_HEADER_LOCATION)) {
                throw new PackageDownloadException(
                        String.format(ARTIFACT_DOWNLOAD_EXCEPTION_PMS_FMT, artifactName, packageIdentifier.getName(),
                                packageIdentifier.getVersion().toString()), ase);
            }
            return headers.get(HTTP_HEADER_LOCATION);
        } catch (AmazonClientException ace) {
            // TODO: This should be expanded to handle various types of retryable/non-retryable exceptions
            throw new PackageDownloadException(
                    String.format(ARTIFACT_DOWNLOAD_EXCEPTION_PMS_FMT, artifactName, packageIdentifier.getName(),
                            packageIdentifier.getVersion().toString()), ace);
        }
    }

    String extractFilename(URL preSignedUrl, String contentDisposition) {
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
