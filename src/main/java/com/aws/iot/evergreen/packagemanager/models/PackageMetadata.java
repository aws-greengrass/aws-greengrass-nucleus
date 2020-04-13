package com.aws.iot.evergreen.packagemanager.models;

import lombok.AllArgsConstructor;
import lombok.Value;

import java.util.Map;

@Value
@AllArgsConstructor
public class PackageMetadata {
    PackageIdentifier packageIdentifier;

    Map<String, String> dependencies;   // from dependency package name to version requirement
}
