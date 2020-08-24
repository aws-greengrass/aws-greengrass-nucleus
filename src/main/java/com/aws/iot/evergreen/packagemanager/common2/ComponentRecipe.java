/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.aws.iot.evergreen.packagemanager.common2;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.vdurmont.semver4j.Semver;
import java.util.Collections;
import java.util.List;
import lombok.Builder;
import lombok.Value;

@JsonDeserialize(builder = ComponentRecipe.ComponentRecipeBuilder.class)
@Value
@Builder
public class ComponentRecipe {

    RecipeTemplateVersion templateVersion;

    String componentName;

    Semver version;

    String componentType;

    String description;

    String publisher;

    @Builder.Default
    List<PlatformSpecificManifest> manifests = Collections.emptyList();

    @JsonPOJOBuilder(withPrefix = "")
    public static class ComponentRecipeBuilder {
    }

    public enum RecipeTemplateVersion {
        @JsonProperty("2020-01-25")
        JAN_25_2020
    }
}
