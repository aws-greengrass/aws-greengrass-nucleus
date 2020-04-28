package com.aws.iot.evergreen.packagemanager.plugins;

import com.aws.iot.evergreen.packagemanager.exceptions.PackageDownloadException;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

public interface ArtifactDownloader {

    void downloadToPath(PackageIdentifier packageIdentifier, URI artifactUri, Path saveToPath)
            throws IOException, PackageDownloadException;
}
