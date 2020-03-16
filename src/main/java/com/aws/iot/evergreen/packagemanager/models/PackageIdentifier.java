package com.aws.iot.evergreen.packagemanager.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.vdurmont.semver4j.Semver;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@JsonSerialize
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@EqualsAndHashCode
@AllArgsConstructor
@NoArgsConstructor
public class PackageIdentifier {
    @JsonProperty("Name")
    String name;
    @JsonProperty("Version")
    Semver version;
}
