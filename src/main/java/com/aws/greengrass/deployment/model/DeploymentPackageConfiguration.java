/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;


/**
 * Class to represent a single package along with its dependencies that comes in the deployment configuration.
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode
@ToString
public class DeploymentPackageConfiguration {

    private String name;
    private boolean rootComponent;

    // TODO: [P41179644] change to versionRequirements which can be a pinned version or a version range
    private String resolvedVersion;
    private ConfigurationUpdateOperation configurationUpdate;
    private RunWith runWith;

    /**
     * Constructor for no update configuration update. Used for testing
     *
     * @param name     name of package
     * @param rootComponent   if it is root
     * @param resolvedVersion resolved version
     */
    public DeploymentPackageConfiguration(String name, boolean rootComponent, String resolvedVersion) {
        this.name = name;
        this.rootComponent = rootComponent;
        this.resolvedVersion = resolvedVersion;
    }

    /**
     * Constructor for no legacy configuration.
     *
     * @param name     name of package
     * @param rootComponent   if it is root
     * @param resolvedVersion resolved version
     * @param configurationUpdate   configuration update
     */
    public DeploymentPackageConfiguration(String name, boolean rootComponent, String resolvedVersion,
                                          ConfigurationUpdateOperation configurationUpdate) {
        this.name = name;
        this.rootComponent = rootComponent;
        this.resolvedVersion = resolvedVersion;
        this.configurationUpdate = configurationUpdate;
    }


    /**
     * Constructor. Non provided fields are null.
     * @param name packageName
     */
    public DeploymentPackageConfiguration(String name) {
        this.name = name;
    }
}
