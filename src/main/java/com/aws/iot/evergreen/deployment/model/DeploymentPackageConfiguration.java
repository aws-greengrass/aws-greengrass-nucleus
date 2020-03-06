/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment.model;

import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.models.PackageParameter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.Set;

/**
 * Class to represent a single package along with its dependencies
 * that comes in the deployment configuration.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@AllArgsConstructor
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
    List<PackageIdentifier> listOfDependencies;

}
