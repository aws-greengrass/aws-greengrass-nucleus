package com.aws.iot.evergreen.packagemanager.plugins;

import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;

public class GreengrassRepositoryDownloader implements ArtifactDownloader {
    private static final int BUFFER_SIZE = 4096;

    @Override
    public void downloadArtifactToPath(PackageIdentifier packageIdentifier, URI artifactUri, Path saveToPath)
            throws IOException {

        String preSignedUrl = getArtifactDownloadURL(packageIdentifier.getArn(), artifactUri.getSchemeSpecificPart());
        URL url = new URL(preSignedUrl);
        HttpURLConnection httpConn = (HttpURLConnection) url.openConnection();
        int responseCode = httpConn.getResponseCode();

        if (responseCode == HttpURLConnection.HTTP_OK) {
            String disposition = httpConn.getHeaderField("Content-Disposition");
            String filename = extractFilename(preSignedUrl, disposition);

            InputStream inputStream = httpConn.getInputStream();
            FileOutputStream outputStream = new FileOutputStream(saveToPath.resolve(filename).toString());

            int bytesRead = -1;
            byte[] buffer = new byte[BUFFER_SIZE];
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            outputStream.close();
            inputStream.close();
        }
        httpConn.disconnect();

    }

    private String getArtifactDownloadURL(String packageArn, String artifactName) {
        //TODO retrieve artifact presigned download URL from cloud as redirection
        return null;
    }

    private String extractFilename(String preSignedUrl, String contentDisposition) {
        if (contentDisposition != null) {
            int index = contentDisposition.indexOf("filename=");
            if (index > 0) {
                return contentDisposition.substring(index + 10, contentDisposition.length() - 1);
            }
        }
        return preSignedUrl.substring(preSignedUrl.lastIndexOf("/") + 1, preSignedUrl.indexOf("?"));
    }
}
