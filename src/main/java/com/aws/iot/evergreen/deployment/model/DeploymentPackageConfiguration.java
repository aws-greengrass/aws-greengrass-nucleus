/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment.model;

import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Class to represent a single package along with its dependencies
 * that comes in the deployment configuration.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class DeploymentPackageConfiguration {

    @JsonProperty("Name")
    private String packageName;

    @JsonProperty("ResolvedVersion")
    private String resolvedVersion;

    @Deprecated
    @JsonProperty("VersionConstraint")
    private String versionConstraint;

    @JsonProperty("Configuration")
    private Map<String, Object> configuration = new HashMap<>();

    @Deprecated
    @JsonProperty("Dependencies")
    private List<PackageIdentifier> listOfDependencies;

}
