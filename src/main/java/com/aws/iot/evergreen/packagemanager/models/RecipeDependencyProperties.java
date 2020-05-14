/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.packagemanager.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@Getter
@AllArgsConstructor
@EqualsAndHashCode
@JsonSerialize
public class RecipeDependencyProperties {
    @JsonProperty("VersionRequirements")
    String versionRequirements;
    @JsonProperty("DependencyType")
    String dependencyType;

    /**
     * RecipeDependencyProperties constructor.
     *
     * @param versionRequirements dependency version constraints
     */
    public RecipeDependencyProperties(String versionRequirements) {
        this.versionRequirements = versionRequirements;
    }
}
