package com.aws.iot.evergreen.packagemanager.models;

import com.vdurmont.semver4j.Semver;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.HashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class PackageRegistryEntry {

    @EqualsAndHashCode.Include
    private final String name;

    @EqualsAndHashCode.Include
    private Semver version;

    private final Map<String, Reference> dependsOnBy;

    private final Map<String, Reference> dependsOn;

    /**
     * package registry entry constructor.
     *
     * @param name        package name
     * @param version     package version
     * @param dependsOnBy package dependents
     */
    public PackageRegistryEntry(String name, Semver version, Map<String, Reference> dependsOnBy) {
        this.name = name;
        this.version = version;
        this.dependsOnBy = dependsOnBy;
        this.dependsOn = new HashMap<>();
    }

    @Data
    @AllArgsConstructor
    public static class Reference {
        private final String name;

        private Semver version;

        private String constraint;
    }
}
