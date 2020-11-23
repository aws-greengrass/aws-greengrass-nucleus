/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.e2e.helper;

import com.amazonaws.services.greengrassv2.AWSGreengrassV2;
import com.amazonaws.services.greengrassv2.model.CreateComponentVersionRequest;
import com.amazonaws.services.greengrassv2.model.CreateComponentVersionResult;
import com.amazonaws.services.greengrassv2.model.DeleteComponentRequest;
import com.amazonaws.services.greengrassv2.model.DeleteComponentResult;
import com.amazonaws.services.greengrassv2.model.RecipeSource;
import com.aws.greengrass.componentmanager.ComponentServiceHelper;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

@NoArgsConstructor(access = AccessLevel.PRIVATE) // so that it can't be 'new'
public class ComponentServiceTestHelper {
    protected static final Logger logger = LogManager.getLogger(ComponentServiceHelper.class);


    /**
     * Create a component with the given recipe file.
     *
     * @param cmsClient      client of Component Management Service
     * @param recipeFilePath the path to the component recipe file
     * @return {@link CreateComponentVersionResult}
     * @throws IOException if file reading fails
     */
    public static CreateComponentVersionResult createComponent(AWSGreengrassV2 cmsClient, Path recipeFilePath)
            throws IOException {
        ByteBuffer recipeBuf = ByteBuffer.wrap(Files.readAllBytes(recipeFilePath));
        CreateComponentVersionRequest createComponentRequest =
                new CreateComponentVersionRequest().withRecipeSource(new RecipeSource().withInlineRecipe(recipeBuf));
        logger.atDebug("create-component").kv("request", createComponentRequest).log();
        CreateComponentVersionResult createComponentResult = cmsClient.createComponentVersion(createComponentRequest);
        logger.atDebug("create-component").kv("result", createComponentResult).log();
        return createComponentResult;
    }


    /**
     * Delete a component of the given name and version.
     *
     * @param cmsClient        client of Component Management Service
     * @param componentArn   name of the component to delete
     * @return {@link DeleteComponentResult}
     */
    public static DeleteComponentResult deleteComponent(AWSGreengrassV2 cmsClient, String componentArn) {
        DeleteComponentRequest deleteComponentVersionRequest =
                new DeleteComponentRequest().withArn(componentArn);
        logger.atDebug("delete-component").kv("request", deleteComponentVersionRequest).log();
        DeleteComponentResult deleteComponentVersionResult =
                cmsClient.deleteComponent(deleteComponentVersionRequest);
        logger.atDebug("delete-component").kv("result", deleteComponentVersionResult).log();
        return deleteComponentVersionResult;
    }
}
