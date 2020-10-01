/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.greengrass.deployment.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Class to represent a single package along with its dependencies
 * that comes in the deployment configuration.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Setter
//@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode
@ToString
public class DeploymentPackageConfiguration {

    @JsonProperty("Name")
    private String packageName;

    @JsonProperty("RootComponent")
    private boolean rootComponent;

    // TODO: change to versionRequirements which can be a pinned version or a version range
    @JsonProperty("ResolvedVersion")
    private String resolvedVersion;

    @JsonProperty("Configuration")
    private Map<String, Object> configuration = new HashMap<>();

    @JsonProperty("ConfigurationUpdate")
    private List<ConfigurationUpdateOperation> configurationUpdateOperations;

    public DeploymentPackageConfiguration(String packageName, boolean rootComponent, String resolvedVersion,
                                          Map<String, Object> configuration,
                                          List<ConfigurationUpdateOperation> configurationUpdateOperations) {
        this.packageName = packageName;
        this.rootComponent = rootComponent;
        this.resolvedVersion = resolvedVersion;
        this.configuration = configuration;
        this.configurationUpdateOperations = configurationUpdateOperations;
    }

    public DeploymentPackageConfiguration(String packageName, boolean rootComponent, String resolvedVersion,
                                          Map<String, Object> configuration) {
        this.packageName = packageName;
        this.rootComponent = rootComponent;
        this.resolvedVersion = resolvedVersion;
        this.configuration = configuration;
    }
}
