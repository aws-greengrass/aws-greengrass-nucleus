package com.aws.iot.evergreen.packagemanagement.model;

import com.vdurmont.semver4j.Semver;

import java.util.HashSet;
import java.util.Set;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.FieldDefaults;

@Getter
@ToString
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class PackageRegistryEntry {

    @EqualsAndHashCode.Include
    String name;

    @EqualsAndHashCode.Include
    Semver version;

    Set<Reference> dependBy;

    Set<Reference> dependOn;

    public PackageRegistryEntry(String name, String version) {
        this.name = name;
        this.version = new Semver(version, Semver.SemverType.NPM);
        this.dependBy = new HashSet<>();
        this.dependOn = new HashSet<>();
    }

    @Getter
    @ToString
    @FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
    @EqualsAndHashCode
    public static class Reference {
        String name;

        Semver version;

        String constraint;

        public Reference(String name, String version, String constraint) {
            this.name = name;
            this.version = new Semver(version, Semver.SemverType.NPM);
            this.constraint = constraint;
        }
    }
}
