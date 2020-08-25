/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.packagemanager.common;


import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Value;

@JsonDeserialize(builder = DependencyProperties.DependencyPropertiesBuilder.class)
@Value
@Builder
public class DependencyProperties {
    String versionRequirement;

    String dependencyType;

    @JsonPOJOBuilder(withPrefix = "")
    public static class DependencyPropertiesBuilder {
    }
}
