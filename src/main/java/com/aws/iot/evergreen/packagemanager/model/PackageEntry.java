package com.aws.iot.evergreen.packagemanager.model;

import java.util.Collections;
import java.util.List;

public class PackageEntry {

    private final int id;

    private final String packageName;

    private final String packageVersion;

    private final List<String> artifactPaths;

    public PackageEntry(int id, String packageName, String packageVersion) {
        this.id = id;
        this.packageName = packageName;
        this.packageVersion = packageVersion;
        this.artifactPaths = Collections.emptyList();
    }

    public PackageEntry(int id, String packageName, String packageVersion, List<String> artifactPaths) {
        this.id = id;
        this.packageName = packageName;
        this.packageVersion = packageVersion;

        this.artifactPaths = Collections.unmodifiableList(artifactPaths);
    }

    public int getId() {
        return id;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getPackageVersion() {
        return packageVersion;
    }

    public List<String> getArtifactPaths() {
        return artifactPaths;
    }
}
