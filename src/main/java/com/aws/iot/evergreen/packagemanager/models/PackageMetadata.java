package com.aws.iot.evergreen.packagemanager.models;

import lombok.AllArgsConstructor;
import lombok.Value;

import java.util.Map;

@Value
@AllArgsConstructor
public class PackageMetadata implements Comparable<PackageMetadata> {
    PackageIdentifier packageIdentifier;

    Map<String, String> dependencies;   // from dependency package name to version requirement

    @Override
    public int compareTo(PackageMetadata o) {
        return packageIdentifier.compareTo(o.packageIdentifier);
    }
}
