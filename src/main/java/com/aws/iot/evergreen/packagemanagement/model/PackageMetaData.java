package com.aws.iot.evergreen.packagemanagement.model;

import com.vdurmont.semver4j.Semver;
import java.util.Set;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.Value;

@Value
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class PackageMetaData {

    @NonNull @EqualsAndHashCode.Include
    String name;

    @NonNull @EqualsAndHashCode.Include Semver version;

    @NonNull Set<VersionConstraint> versionConstraints;

    @NonNull Set<PackageMetaData> dependsOn;


}
