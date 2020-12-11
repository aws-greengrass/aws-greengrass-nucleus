/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.e2e.helper;

import com.aws.greengrass.componentmanager.ComponentServiceHelper;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.greengrassv2.GreengrassV2Client;
import software.amazon.awssdk.services.greengrassv2.model.CreateComponentVersionRequest;
import software.amazon.awssdk.services.greengrassv2.model.CreateComponentVersionResponse;
import software.amazon.awssdk.services.greengrassv2.model.DeleteComponentRequest;
import software.amazon.awssdk.services.greengrassv2.model.DeleteComponentResponse;

import java.io.IOException;
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
     * @return {@link CreateComponentVersionResponse}
     * @throws IOException if file reading fails
     */
    public static CreateComponentVersionResponse createComponent(GreengrassV2Client cmsClient, Path recipeFilePath)
            throws IOException {
        byte[] recipeBuf = Files.readAllBytes(recipeFilePath);
        CreateComponentVersionRequest createComponentRequest =
                CreateComponentVersionRequest.builder().inlineRecipe(SdkBytes.fromByteArray(recipeBuf)).build();
        logger.atDebug("create-component").kv("request", createComponentRequest).log();
        CreateComponentVersionResponse createComponentResult = cmsClient.createComponentVersion(createComponentRequest);
        logger.atDebug("create-component").kv("result", createComponentResult).log();
        return createComponentResult;
    }


    /**
     * Delete a component of the given name and version.
     *
     * @param cmsClient        client of Component Management Service
     * @param componentArn   name of the component to delete
     * @return {@link DeleteComponentResponse}
     */
    public static DeleteComponentResponse deleteComponent(GreengrassV2Client cmsClient, String componentArn) {
        DeleteComponentRequest deleteComponentVersionRequest = DeleteComponentRequest.builder()
                .arn(componentArn).build();
        logger.atDebug("delete-component").kv("request", deleteComponentVersionRequest).log();
        DeleteComponentResponse deleteComponentVersionResult =
                cmsClient.deleteComponent(deleteComponentVersionRequest);
        logger.atDebug("delete-component").kv("result", deleteComponentVersionResult).log();
        return deleteComponentVersionResult;
    }
}
