package com.aws.iot.evergreen.packagemanagement.model;

import com.vdurmont.semver4j.Semver;
import java.util.Collections;
import java.util.Set;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.FieldDefaults;
import org.apache.commons.lang3.StringUtils;

@Getter
@ToString
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class PackageMetadata {

    @EqualsAndHashCode.Include
    String name;

    @EqualsAndHashCode.Include
    Semver version;

    Set<String> versionConstraints;

    Set<PackageMetadata> dependsOn;


    public PackageMetadata(String name, String version, Set<String> versionConstraints,
                           Set<PackageMetadata> dependsOn) {
        if (StringUtils.isBlank(name)) {
            throw new NullPointerException("package name can't be blank");
        }
        if (StringUtils.isBlank(version)) {
            throw new NullPointerException("package version can't be blank");
        }
        this.name = name;
        this.version = new Semver(version, Semver.SemverType.NPM);

        this.versionConstraints = versionConstraints == null ? Collections.emptySet() :
                Collections.unmodifiableSet(versionConstraints);
        this.dependsOn = dependsOn == null ? Collections.emptySet() : Collections.unmodifiableSet(dependsOn);

    }

    public PackageMetadata(String name, String version) {
        this(name, version, null, null);
    }

}
