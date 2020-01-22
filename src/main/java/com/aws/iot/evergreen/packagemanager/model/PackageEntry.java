package com.aws.iot.evergreen.packagemanager.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PackageEntry {

    private final String packageName;

    private final String packageVersion;

    private final List<String> artifactUrls;

    private String version;

    public PackageEntry(String packageName, String packageVersion) {
        this.packageName = packageName;
        this.packageVersion = packageVersion;
        this.artifactUrls = Collections.emptyList();
    }

    public PackageEntry(PackageEntry packageEntry, List<String> artifactUrls) {
        this.packageName = packageEntry.packageName;
        this.packageVersion = packageEntry.packageVersion;

        this.artifactUrls = Collections.unmodifiableList(artifactUrls);
    }
}
