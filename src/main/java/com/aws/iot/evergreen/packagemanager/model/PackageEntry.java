package com.aws.iot.evergreen.packagemanager.model;

import java.util.Collections;
import java.util.List;

public class PackageEntry {

    private final int id;

    private final String packageName;

    private final String packageVersion;

    private final List<String> artifactUrls;

    public PackageEntry(int id, String packageName, String packageVersion) {
        this.id = id;
        this.packageName = packageName;
        this.packageVersion = packageVersion;
        this.artifactUrls = Collections.emptyList();
    }

    public PackageEntry(PackageEntry packageEntry, List<String> artifactUrls) {
        this.id = packageEntry.id;
        this.packageName = packageEntry.packageName;
        this.packageVersion = packageEntry.packageVersion;

        this.artifactUrls = Collections.unmodifiableList(artifactUrls);
    }

    public int getId() {
        return id;
    }

    public List<String> getArtifactUrls() {
        return artifactUrls;
    }
}
