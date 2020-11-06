/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.HashMap;
import java.util.Map;

/**
 * Class to represent a single package along with its dependencies that comes in the deployment configuration.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode
@ToString
public class DeploymentPackageConfiguration {

    @JsonProperty("Name")
    private String packageName;

    @JsonProperty("RootComponent")
    private boolean rootComponent;

    // TODO: [P41179644] change to versionRequirements which can be a pinned version or a version range
    @JsonProperty("ResolvedVersion")
    private String resolvedVersion;

    @Deprecated
    @JsonProperty("Configuration")
    private Map<String, Object> configuration = new HashMap<>();

    @JsonProperty("ConfigurationUpdate")
    private ConfigurationUpdateOperation configurationUpdateOperation;

    @JsonProperty("RunWith")
    private RunWith runWith;

    /**
     * Constructor for no update configuration update for backward compatibility.
     *
     * @param packageName     name of package
     * @param rootComponent   if it is root
     * @param resolvedVersion resolved version
     * @param configuration   configuration
     */
    public DeploymentPackageConfiguration(String packageName, boolean rootComponent, String resolvedVersion,
            Map<String, Object> configuration) {
        this.packageName = packageName;
        this.rootComponent = rootComponent;
        this.resolvedVersion = resolvedVersion;
        this.configuration = configuration;
    }

    /**
     * Constructor for no legacy configuration.
     *
     * @param packageName     name of package
     * @param rootComponent   if it is root
     * @param resolvedVersion resolved version
     * @param configurationUpdateOperation   configuration update
     */
    public DeploymentPackageConfiguration(String packageName, boolean rootComponent, String resolvedVersion,
            ConfigurationUpdateOperation configurationUpdateOperation) {
        this.packageName = packageName;
        this.rootComponent = rootComponent;
        this.resolvedVersion = resolvedVersion;
        this.configurationUpdateOperation = configurationUpdateOperation;
    }


    /**
     * Constructor. Non provided fields are null.
     * @param packageName packageName
     */
    public DeploymentPackageConfiguration(String packageName) {
        this.packageName = packageName;
    }
}
