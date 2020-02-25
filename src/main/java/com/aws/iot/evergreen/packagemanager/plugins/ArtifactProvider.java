package com.aws.iot.evergreen.packagemanager.plugins;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

public interface ArtifactProvider {

    void downloadArtifactToPath(Path outPath) throws IOException;

}
