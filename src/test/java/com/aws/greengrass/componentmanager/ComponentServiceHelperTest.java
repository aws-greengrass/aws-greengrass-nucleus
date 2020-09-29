/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.amazonaws.services.evergreen.AWSEvergreen;
import com.amazonaws.services.evergreen.model.CreateComponentRequest;
import com.amazonaws.services.evergreen.model.CreateComponentResult;
import com.amazonaws.services.evergreen.model.DeleteComponentRequest;
import com.amazonaws.services.evergreen.model.GetComponentRequest;
import com.amazonaws.services.evergreen.model.GetComponentResult;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.vdurmont.semver4j.Semver;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.ByteBuffer;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class ComponentServiceHelperTest {

    @Mock
    private AWSEvergreen client;

    @Mock
    private GreengrassComponentServiceClientFactory clientFactory;

    private ComponentServiceHelper helper;

    @Captor
    private ArgumentCaptor<GetComponentRequest> getComponentRequestArgumentCaptor;

    @BeforeEach
    void beforeEach() {
        when(clientFactory.getCmsClient()).thenReturn(client);
        this.helper = spy(new ComponentServiceHelper(clientFactory));
    }

    @Test
    void GIVEN_component_name_version_WHEN_download_component_recipe_THEN_task_succeed() throws Exception {
        String recipeContents = "testRecipeContent";
        ByteBuffer testRecipeBytes = ByteBuffer.wrap(recipeContents.getBytes());
        GetComponentResult testResult = new GetComponentResult().withRecipe(testRecipeBytes);
        doReturn(testResult).when(client).getComponent(getComponentRequestArgumentCaptor.capture());
        String downloadPackageRecipeAsString = helper.downloadPackageRecipeAsString(
                new ComponentIdentifier(ComponentTestResourceHelper.MONITORING_SERVICE_PACKAGE_NAME, new Semver("1.0.0"),
                        "private"));

        assertEquals(recipeContents, downloadPackageRecipeAsString);
    }

    // TODO: Add test cases for failure status codes once the SDK model is updated to return proper http responses

    @Test
    void GIVEN_recipe_file_WHEN_create_component_THEN_upload_the_recipe(@TempDir Path recipeDir) throws Exception {
        ArgumentCaptor<CreateComponentRequest> createComponentRequestArgumentCaptor =
                ArgumentCaptor.forClass(CreateComponentRequest.class);
        CreateComponentResult mockResult = new CreateComponentResult();
        doReturn(mockResult).when(client).createComponent(createComponentRequestArgumentCaptor.capture());

        String testRecipeContent = "testContent";
        Path recipePath = recipeDir.resolve("recipe.yaml");
        FileUtils.writeStringToFile(recipePath.toFile(), testRecipeContent);
        ComponentServiceHelper.createComponent(client, recipePath);

        CreateComponentRequest createComponentRequest = createComponentRequestArgumentCaptor.getValue();
        assertEquals(testRecipeContent, new String(createComponentRequest.getRecipe().array()));
    }

    @Test
    void GIVEN_component_name_version_WHEN_delete_component_THEN_send_service_request() {
        ArgumentCaptor<DeleteComponentRequest> requestCaptor = ArgumentCaptor.forClass(DeleteComponentRequest.class);
        ComponentServiceHelper.deleteComponent(client, "mockName", "mockVersion");
        verify(client, times(1)).deleteComponent(requestCaptor.capture());
        DeleteComponentRequest request = requestCaptor.getValue();
        assertEquals("mockName", request.getComponentName());
        assertEquals("mockVersion", request.getComponentVersion());
    }
}
