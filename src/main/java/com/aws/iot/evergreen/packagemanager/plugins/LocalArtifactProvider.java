package com.aws.iot.evergreen.packagemanager.plugins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class LocalArtifactProvider implements ArtifactProvider {

    Path localSourcePath;

    public LocalArtifactProvider(Path localSourcePath) {
        this.localSourcePath = localSourcePath;
    }

    public LocalArtifactProvider(String localSourcePath) {
        this.localSourcePath = Paths.get(localSourcePath);
    }

    @Override
    public void downloadArtifactToPath(Path root) throws IOException {
        // TODO: Hacked together code, add proper pause resume behavior and a better API in general
        Path outPath = root.resolve(localSourcePath.getFileName());
        Files.copy(localSourcePath, outPath, REPLACE_EXISTING);
    }
}
