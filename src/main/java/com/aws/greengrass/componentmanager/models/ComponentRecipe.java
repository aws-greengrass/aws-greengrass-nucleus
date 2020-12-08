/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.models;

import com.amazon.aws.iot.greengrass.component.common.ComponentConfiguration;
import com.amazon.aws.iot.greengrass.component.common.ComponentType;
import com.amazon.aws.iot.greengrass.component.common.DependencyProperties;
import com.amazon.aws.iot.greengrass.component.common.RecipeFormatVersion;
import com.vdurmont.semver4j.Semver;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Value
@Builder
@AllArgsConstructor
public class ComponentRecipe {

    RecipeFormatVersion recipeTemplateVersion;

    String componentName;

    Semver version;

    String description;

    String publisher;

    ComponentConfiguration componentConfiguration;

    // The following fields are the platform specific and has already been resolved when loading from the recipe file

    @Builder.Default
    Map<String, Object> lifecycle = Collections.emptyMap();

    @Builder.Default
    List<ComponentArtifact> artifacts = Collections.emptyList();

    @Builder.Default
    Map<String, DependencyProperties> dependencies = Collections.emptyMap();

    ComponentType componentType;
}
