package com.aws.iot.evergreen.packagemanager.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.vdurmont.semver4j.Semver;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Value;

@JsonSerialize
@JsonInclude(JsonInclude.Include.NON_NULL)
@Value
@NoArgsConstructor(force = true, access = AccessLevel.PRIVATE)
@AllArgsConstructor
public class PackageIdentifier {
    @JsonProperty("Name")
    String name;
    @JsonProperty("Version")
    Semver version;
    @JsonProperty("ARN")
    String arn;

    /**
     * PackageIdentifier constructor.
     * @param name package name
     * @param version package version in semver
     */
    public PackageIdentifier(String name, Semver version) {
        this.name = name;
        this.version = version;
        this.arn = String.format("%s-%s", name, version.getValue());
    }

    @Override
    public String toString() {
        return String.format("%s-v%s", name, version);
    }
}
