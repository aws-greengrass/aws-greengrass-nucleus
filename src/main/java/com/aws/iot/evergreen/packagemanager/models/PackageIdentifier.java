package com.aws.iot.evergreen.packagemanager.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.vdurmont.semver4j.Semver;
import lombok.Getter;

@JsonSerialize
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PackageIdentifier {
    @JsonProperty("Name")
    @Getter
    String name;
    @JsonProperty("Version")
    @Getter
    Semver version;
}
