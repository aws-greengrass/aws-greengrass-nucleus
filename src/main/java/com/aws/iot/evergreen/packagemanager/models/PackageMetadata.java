package com.aws.iot.evergreen.packagemanager.models;

import com.aws.iot.evergreen.util.Utils;
import com.vdurmont.semver4j.Semver;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.Collections;
import java.util.Set;

@Getter
@ToString
@EqualsAndHashCode
public class PackageMetadata {

    private final String name;

    private final Semver version;

    private final String versionConstraint;

    @EqualsAndHashCode.Exclude
    private final Set<PackageMetadata> dependsOn;

    /**
<<<<<<< HEAD
     * Constructor for PackageMetadata.
     *
     * @param name package name
     * @param version package version
     * @param versionConstraint package version constraint
     * @param dependsOn dependency package information
=======
     * package metadata constructor.
     * @param name package name
     * @param version package version
     * @param versionConstraint dependent version constraint
     * @param dependsOn package dependencies
>>>>>>> checkstyle, renaming
     */
    public PackageMetadata(String name, String version, String versionConstraint, Set<PackageMetadata> dependsOn) {
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
    }

    /**
<<<<<<< HEAD
     * Constructor for PackageMetadata when no dependency information is known.
     *
     * @param name package name
     * @param version package version
     * @param constraint package version constraint
=======
     * package metadata constructor.
     * @param name package name
     * @param version package version
     * @param constraint dependent version constraint
>>>>>>> checkstyle, renaming
     */
    public PackageMetadata(String name, String version, String constraint) {
        this(name, version, constraint, null);
    }

}
