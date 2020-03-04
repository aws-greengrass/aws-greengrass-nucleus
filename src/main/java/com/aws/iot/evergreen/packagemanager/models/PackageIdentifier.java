package com.aws.iot.evergreen.packagemanager.models;

import com.vdurmont.semver4j.Semver;

// TODO: temporarily suppress this warning which will be gone after these fields get used.
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "UUF_UNUSED_FIELD")
public class PackageIdentifier {
    String name;
    Semver version;
}
