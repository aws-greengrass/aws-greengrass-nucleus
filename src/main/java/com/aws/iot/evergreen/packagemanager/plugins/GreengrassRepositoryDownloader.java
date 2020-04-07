package com.aws.iot.evergreen.packagemanager.plugins;

import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

public class GreengrassRepositoryDownloader implements ArtifactDownloader {
    private static final Logger logger = LogManager.getLogger(GreengrassRepositoryDownloader.class);
    private static final int BUFFER_SIZE = 4096;

    @SuppressWarnings("PMD.AssignmentInOperand")
    @Override
    public void downloadArtifactToPath(PackageIdentifier packageIdentifier, URI artifactUri, Path saveToPath)
            throws IOException {
        logger.atInfo().setEventType("download-artifact-from-greengrass-repo").addKeyValue("packageIdentifier",
                packageIdentifier).addKeyValue("artifactUri", artifactUri).log();
        String preSignedUrl = getArtifactDownloadURL(packageIdentifier.getArn(), artifactUri.getSchemeSpecificPart());
        URL url = new URL(preSignedUrl);
        HttpURLConnection httpConn = null;
        try {
            httpConn = create(url);
            int responseCode = httpConn.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK) {
                String disposition = httpConn.getHeaderField("Content-Disposition");
                String filename = extractFilename(preSignedUrl, disposition);

                try (InputStream inputStream = httpConn.getInputStream();
                     OutputStream outputStream = Files.newOutputStream(saveToPath.resolve(filename))) {
                    int bytesRead;
                    byte[] buffer = new byte[BUFFER_SIZE];
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                }
            }
        } finally {
            if (httpConn != null) {
                httpConn.disconnect();
            }
        }
    }

    HttpURLConnection create(URL url) throws IOException {
        return (HttpURLConnection) url.openConnection();
    }

    String getArtifactDownloadURL(String packageArn, String artifactName) {
        //TODO retrieve artifact presigned download URL from cloud as redirection
        return "placeholder";
    }

    String extractFilename(String preSignedUrl, String contentDisposition) {
        if (contentDisposition != null) {
            int index = contentDisposition.indexOf("filename=");
            if (index > 0) {
                return contentDisposition.substring(index + 10, contentDisposition.length() - 1);
            }
        }
        int startIndex = preSignedUrl.lastIndexOf('/') + 1;
        int endIndex = preSignedUrl.indexOf('?');
        return endIndex == -1 ? preSignedUrl.substring(startIndex) : preSignedUrl.substring(startIndex, endIndex);
    }
}
