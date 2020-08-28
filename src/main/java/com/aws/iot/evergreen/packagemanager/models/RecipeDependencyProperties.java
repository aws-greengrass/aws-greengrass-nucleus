/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.packagemanager.models;

import lombok.AllArgsConstructor;
import lombok.Value;

@Value
@AllArgsConstructor
public class RecipeDependencyProperties {
    String versionRequirements; // TODO Make it strongly typed with Semver.Requirement
    String dependencyType;  //TODO Make it enum

    /**
     * RecipeDependencyProperties constructor.
     *
     * @param versionRequirements dependency version constraints
     */
    @SuppressWarnings("PMD.NullAssignment")
    // dependencyType could be null now. TODO not allow null after changing to enum
    public RecipeDependencyProperties(String versionRequirements) {
        this.versionRequirements = versionRequirements;
        this.dependencyType = null;
    }
}
