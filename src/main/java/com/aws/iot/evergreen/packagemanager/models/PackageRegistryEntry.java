package com.aws.iot.evergreen.packagemanager.models;

import com.vdurmont.semver4j.Semver;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class PackageRegistryEntry {

    @EqualsAndHashCode.Include
    private final String name;

    @EqualsAndHashCode.Include
    private Semver version;

    private final Map<String, Reference> dependsBy;

    private final Map<String, Reference> dependsOn;

    public PackageRegistryEntry(String name, Semver version, Map<String, Reference> dependsBy) {
        this.name = name;
        this.version = version;
        this.dependsBy = dependsBy;
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
