/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.deployment.model;

import com.aws.iot.evergreen.packagemanager.models.PackageParameter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Getter;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Class to represent a single package along with its dependencies
 * that comes in the deployment configuration.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
public class DeploymentPackageConfiguration {

    @JsonProperty("Name")
    String packageName;

    @JsonProperty("ResolvedVersion")
    String resolvedVersion;

    @JsonProperty("VersionConstraint")
    String versionConstraint;

    @JsonProperty("Parameters")
    Set<PackageParameter> parameters;

    @JsonProperty("Dependencies")
    List<NameVersionPair> listOfDependentPackages;

    @JsonSerialize
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class NameVersionPair {
        @JsonProperty("Name")
        @Getter
        String packageName;

        @JsonProperty("Version")
        @Getter
        String version;
    }
}
