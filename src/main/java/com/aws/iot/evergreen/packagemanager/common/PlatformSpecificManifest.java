/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.packagemanager.common;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Value;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@JsonDeserialize(builder = PlatformSpecificManifest.PlatformSpecificManifestBuilder.class)
@Value
@Builder
public class PlatformSpecificManifest {

    Platform platform;

    @Builder.Default
    List<ComponentParameter> parameters = Collections.emptyList();

    @Builder.Default
    Map<String, Object> lifecycle = Collections.emptyMap();

    @Builder.Default
    List<ComponentArtifact> artifacts = Collections.emptyList();

    @Builder.Default
    Map<String, DependencyProperties> dependencies = Collections.emptyMap();

    @JsonPOJOBuilder(withPrefix = "")
    public static class PlatformSpecificManifestBuilder {
    }

}
