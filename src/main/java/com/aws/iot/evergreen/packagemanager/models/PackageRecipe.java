/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager.models;

import com.vdurmont.semver4j.Semver;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Value
@Builder
@AllArgsConstructor
public class PackageRecipe {

    RecipeTemplateVersion recipeTemplateVersion;

    String componentName;

    Semver version;

    String description;

    String publisher;

    // The following fields are the platform specific and has already been resolved when loading from the recipe file
    @Builder.Default
    Set<PackageParameter> packageParameters = Collections.emptySet();

    @Builder.Default
    Map<String, Object> lifecycle = Collections.emptyMap();

    @Builder.Default
    List<ComponentArtifact> artifacts = Collections.emptyList();

    @Builder.Default
    Map<String, RecipeDependencyProperties> dependencies = Collections.emptyMap();

    String componentType;
}
