/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.e2e.helper;

import com.amazonaws.services.evergreen.AWSEvergreen;
import com.amazonaws.services.evergreen.model.CreateComponentResult;
import com.amazonaws.services.evergreen.model.CreateComponentVersionDeprecatedRequest;
import com.amazonaws.services.evergreen.model.CreateComponentVersionDeprecatedResult;
import com.amazonaws.services.evergreen.model.DeleteComponentVersionDeprecatedRequest;
import com.amazonaws.services.evergreen.model.DeleteComponentVersionDeprecatedResult;
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
     * @param evergreenClient client of Component Management Service
     * @param recipeFilePath  the path to the component recipe file
     * @return {@link CreateComponentResult}
     * @throws IOException if file reading fails
     */
    // TODO change it to the finalized control plane method
    public static CreateComponentVersionDeprecatedResult createComponent(AWSEvergreen evergreenClient,
            Path recipeFilePath) throws IOException {
        ByteBuffer recipeBuf = ByteBuffer.wrap(Files.readAllBytes(recipeFilePath));
        CreateComponentVersionDeprecatedRequest createComponentRequest =
                new CreateComponentVersionDeprecatedRequest().withRecipe(recipeBuf);
        logger.atDebug("create-component").kv("request", createComponentRequest).log();
        CreateComponentVersionDeprecatedResult createComponentResult =
                evergreenClient.createComponentVersionDeprecated(createComponentRequest);
        logger.atDebug("create-component").kv("result", createComponentResult).log();
        return createComponentResult;
    }


    /**
     * Delete a component of the given name and version.
     *
     * @param cmsClient        client of Component Management Service
     * @param componentName    name of the component to delete
     * @param componentVersion version of the component to delete
     * @return {@link DeleteComponentVersionDeprecatedResult}
     */
    // TODO change it to the finalized control plane method
    public static DeleteComponentVersionDeprecatedResult deleteComponent(AWSEvergreen cmsClient, String componentName,
            String componentVersion) {
        DeleteComponentVersionDeprecatedRequest deleteComponentVersionRequest =
                new DeleteComponentVersionDeprecatedRequest().withComponentName(componentName)
                        .withComponentVersion(componentVersion);
        logger.atDebug("delete-component").kv("request", deleteComponentVersionRequest).log();
        DeleteComponentVersionDeprecatedResult deleteComponentVersionResult =
                cmsClient.deleteComponentVersionDeprecated(deleteComponentVersionRequest);
        logger.atDebug("delete-component").kv("result", deleteComponentVersionResult).log();
        return deleteComponentVersionResult;
    }
}
