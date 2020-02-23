package com.aws.iot.evergreen.packagemanager.models;

import com.aws.iot.evergreen.packagemanager.plugins.ArtifactProvider;

import java.util.Map;
import java.util.Set;

public interface PackageConfigFormat {

    Set<ArtifactProvider> getArtifactProviders();

    Map<String, String> getDependencies();
}
