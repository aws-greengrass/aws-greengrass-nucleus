package com.aws.iot.evergreen.packagemanager.models;

import com.aws.iot.evergreen.util.Utils;
import com.vdurmont.semver4j.Semver;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.FieldDefaults;

import java.util.Collections;
import java.util.Set;

@Getter
@ToString
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@EqualsAndHashCode
public class PackageMetadata {

    String name;

    Semver version;

    String versionConstraint;

    @EqualsAndHashCode.Exclude
    Set<PackageMetadata> dependsOn;

    /**
     * Package metadata representing the package details required by package manager for dependency resolution.
     * @param name Name of the package
     * @param version Package {@link Semver}
     * @param versionConstraint Version constraint
     * @param dependsOn Other packages of type {@link PackageMetadata} which this package depends on
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

    public PackageMetadata(String name, String version, String constraint) {
        this(name, version, constraint, null);
    }

}
