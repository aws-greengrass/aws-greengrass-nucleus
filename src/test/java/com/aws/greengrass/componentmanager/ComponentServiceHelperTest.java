/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.amazonaws.services.evergreen.AWSEvergreen;
import com.amazonaws.services.evergreen.model.ComponentCandidate;
import com.amazonaws.services.evergreen.model.ComponentContent;
import com.amazonaws.services.evergreen.model.CreateComponentRequest;
import com.amazonaws.services.evergreen.model.CreateComponentResult;
import com.amazonaws.services.evergreen.model.DeleteComponentVersionRequest;
import com.amazonaws.services.evergreen.model.GetComponentVersionRequest;
import com.amazonaws.services.evergreen.model.GetComponentVersionResult;
import com.amazonaws.services.evergreen.model.ResolveComponentVersionsRequest;
import com.amazonaws.services.evergreen.model.ResolveComponentVersionsResult;
import com.amazonaws.services.evergreen.model.ResourceNotFoundException;
import com.aws.greengrass.componentmanager.exceptions.NoAvailableComponentVersionException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import org.apache.commons.codec.Charsets;
import org.apache.commons.io.FileUtils;
import org.hamcrest.collection.IsMapContaining;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class ComponentServiceHelperTest {

    private static final Semver v1_0_0 = new Semver("1.0.0");
    private static final String COMPONENT_A = "A";
    private static final String DEPLOYMENT_CONFIGURATION_ID = "deploymentConfigurationId";

    @Mock
    private AWSEvergreen client;

    @Mock
    private GreengrassComponentServiceClientFactory clientFactory;

    private ComponentServiceHelper helper;

    @Captor
    private ArgumentCaptor<GetComponentVersionRequest> GetComponentVersionRequestArgumentCaptor;

    @BeforeEach
    void beforeEach() {
        when(clientFactory.getCmsClient()).thenReturn(client);
        this.helper = spy(new ComponentServiceHelper(clientFactory));
    }

    @Test
    void GIVEN_component_name_version_WHEN_download_component_recipe_THEN_task_succeed() throws Exception {
        String recipeContents = "testRecipeContent";
        ByteBuffer testRecipeBytes = ByteBuffer.wrap(recipeContents.getBytes());
        GetComponentVersionResult testResult = new GetComponentVersionResult().withRecipe(testRecipeBytes);
        doReturn(testResult).when(client).getComponentVersion(GetComponentVersionRequestArgumentCaptor.capture());
        String downloadPackageRecipeAsString = helper.downloadPackageRecipeAsString(
                new ComponentIdentifier(ComponentTestResourceHelper.MONITORING_SERVICE_PACKAGE_NAME,
                        new Semver("1.0.0")));

        assertEquals(recipeContents, downloadPackageRecipeAsString);
    }

    // GG_NEEDS_REVIEW: TODO: Add test cases for failure status codes once the SDK model is updated to return proper http responses

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
        ArgumentCaptor<DeleteComponentVersionRequest> requestCaptor = ArgumentCaptor.forClass(DeleteComponentVersionRequest.class);
        ComponentServiceHelper.deleteComponent(client, "mockName", "mockVersion");
        verify(client, times(1)).deleteComponentVersion(requestCaptor.capture());
        DeleteComponentVersionRequest request = requestCaptor.getValue();
        assertEquals("mockName", request.getComponentName());
        assertEquals("mockVersion", request.getComponentVersion());
    }

    @Test
    void GIVEN_component_version_requirements_WHEN_resolve_component_version_THEN_send_service_request()
            throws Exception {
        Map<String, Requirement> versionRequirements = new HashMap<>();
        versionRequirements.put("X", Requirement.buildNPM("^1.0"));
        versionRequirements.put("Y", Requirement.buildNPM("^1.5"));

        ComponentContent componentContent = new ComponentContent().withName(COMPONENT_A).withVersion(v1_0_0.getValue())
                .withRecipe(ByteBuffer.wrap("new recipe".getBytes(Charsets.UTF_8)));
        ResolveComponentVersionsResult result =
                new ResolveComponentVersionsResult().withComponents(Collections.singletonList(componentContent));
        when(client.resolveComponentVersions(any())).thenReturn(result);

        ComponentContent componentContentReturn =
                helper.resolveComponentVersion(COMPONENT_A, v1_0_0, versionRequirements, DEPLOYMENT_CONFIGURATION_ID);

        assertThat(componentContentReturn, is(componentContent));
        ArgumentCaptor<ResolveComponentVersionsRequest> requestArgumentCaptor =
                ArgumentCaptor.forClass(ResolveComponentVersionsRequest.class);
        verify(client).resolveComponentVersions(requestArgumentCaptor.capture());
        ResolveComponentVersionsRequest request = requestArgumentCaptor.getValue();
        //assertThat(request.getDeploymentConfigurationId(), is(DEPLOYMENT_CONFIGURATION_ID));
        assertThat(request.getDeploymentConfigurationId(), notNullValue());
        assertThat(request.getPlatform(), notNullValue());
        assertThat(request.getPlatform().getOs(), notNullValue());
        assertThat(request.getPlatform().getArchitecture(), notNullValue());
        assertThat(request.getComponentCandidates().size(), is(1));
        ComponentCandidate candidate = request.getComponentCandidates().get(0);
        assertThat(candidate.getName(), is(COMPONENT_A));
        assertThat(candidate.getVersion(), is("1.0.0"));
        assertThat(candidate.getVersionRequirements(), IsMapContaining.hasEntry("X", ">=1.0.0 <2.0.0"));
        assertThat(candidate.getVersionRequirements(), IsMapContaining.hasEntry("Y", ">=1.5.0 <2.0.0"));
    }

    @Test
    void GIVEN_component_version_requirements_WHEN_service_no_resource_found_THEN_throw_no_available_version_exception() {
        when(client.resolveComponentVersions(any())).thenThrow(ResourceNotFoundException.class);

        Exception exp = assertThrows(NoAvailableComponentVersionException.class, () -> helper
                .resolveComponentVersion(COMPONENT_A, v1_0_0,
                        Collections.singletonMap("X", Requirement.buildNPM("^1.0")), DEPLOYMENT_CONFIGURATION_ID));

        assertThat(exp.getMessage(), containsString("No applicable version found in cloud registry for component: 'A'"
                                                            + " satisfying requirement: '{X=>=1.0.0 <2.0.0}'."));
    }
}
