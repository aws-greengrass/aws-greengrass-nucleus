package com.aws.iot.evergreen.packagemanager.plugins;

import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class GreengrassRepositoryDownloader implements ArtifactDownloader {
    private static final Logger logger = LogManager.getLogger(GreengrassRepositoryDownloader.class);
    private static final String CONTENT_DISPOSITION = "Content-Disposition";

    @SuppressWarnings("PMD.AssignmentInOperand")
    @Override
    public void downloadToPath(PackageIdentifier packageIdentifier, URI artifactUri, Path saveToPath)
            throws IOException {
        logger.atInfo().setEventType("download-artifact-from-greengrass-repo")
                .addKeyValue("packageIdentifier", packageIdentifier).addKeyValue("artifactUri", artifactUri).log();
        String preSignedUrl = getArtifactDownloadURL(packageIdentifier.getArn(), artifactUri.getSchemeSpecificPart());
        URL url = new URL(preSignedUrl);
        HttpURLConnection httpConn = null;
        try {
            httpConn = connect(url);
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
    }

    HttpURLConnection connect(URL url) throws IOException {
        return (HttpURLConnection) url.openConnection();
    }

    String getArtifactDownloadURL(String packageArn, String artifactName) {
        //TODO retrieve artifact presigned download URL from cloud as redirection
        return "placeholder";
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
