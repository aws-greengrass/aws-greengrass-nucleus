package com.aws.iot.evergreen.packagemanager;

import java.io.ByteArrayOutputStream;

public interface ArtifactProvider {

    ByteArrayOutputStream loadArtifact(String artifactUrl);

}
