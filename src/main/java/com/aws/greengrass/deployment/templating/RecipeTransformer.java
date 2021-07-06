/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

import com.aws.greengrass.componentmanager.models.ComponentRecipe;

import java.nio.file.Path;
import java.util.List;

/**
 * Interface representing a runnable that takes as input a minimized recipe and generates a full recipe file and
 * artifacts.
 */
public interface RecipeTransformer {
    /**
     * Transforms the parameter file into a full recipe.
     * @param paramFile the parameter file object.
     */
    void transform(ComponentRecipe paramFile);

    /**
     * Gets the new (full) recipe.
     * @return the expanded recipe as an object.
     */
    ComponentRecipe getNewRecipe();

    /**
     * Gets a list of artifacts to copy to the created component's artifact directory.
     * @return a list of paths representing the artifacts to copy.
     */
    List<Path> getArtifactsToCopy();
}
