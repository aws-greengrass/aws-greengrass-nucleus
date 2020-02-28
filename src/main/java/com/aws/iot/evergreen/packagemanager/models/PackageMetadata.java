package com.aws.iot.evergreen.packagemanager.models;

import com.aws.iot.evergreen.util.Utils;
import com.vdurmont.semver4j.Semver;
import lombok.EqualsAndHashCode;
import lombok.Value;

import java.util.Collections;
import java.util.Set;

@Value
public class PackageMetadata {

    String name;

    Semver version;

    String versionConstraint;

    @EqualsAndHashCode.Exclude
    Set<PackageMetadata> dependsOn;

    Set<PackageParameter> parameters;

    /**
     * Constructor for PackageMetadata.
     *
     * @param name package name
     * @param version package version
     * @param versionConstraint package version constraint
     * @param dependsOn dependency package information
     */
    public PackageMetadata(String name, String version, String versionConstraint, Set<PackageMetadata> dependsOn,
                           Set<PackageParameter> parameters) {
        if (Utils.isEmpty(name)) {
            throw new IllegalArgumentException("package name can't be empty");
        }
        if (Utils.isEmpty(version)) {
            throw new IllegalArgumentException("package version can't be empty");
        }
        if (Utils.isEmpty(versionConstraint)) {
            throw new IllegalArgumentException("package version constraint can't be empty");
        }
        this.name = name;
        this.version = new Semver(version, Semver.SemverType.NPM);
        this.versionConstraint = versionConstraint;
        this.dependsOn = dependsOn == null ? Collections.emptySet() : Collections.unmodifiableSet(dependsOn);
        this.parameters = parameters == null ? Collections.emptySet()
                : Collections.unmodifiableSet(parameters);
    }

    /**
     * Constructor for PackageMetadata when no dependency information is known.
     *
     * @param name package name
     * @param version package version
     * @param constraint package version constraint
     */
    public PackageMetadata(String name, String version, String constraint) {
        this(name, version, constraint, null, null);
    }

}
