/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.amazonaws.services.evergreen.AWSEvergreen;
import com.amazonaws.services.evergreen.model.ComponentCandidate;
import com.amazonaws.services.evergreen.model.GetComponentVersionDeprecatedRequest;
import com.amazonaws.services.evergreen.model.GetComponentVersionDeprecatedResult;
import com.amazonaws.services.evergreen.model.ResolveComponentCandidatesRequest;
import com.amazonaws.services.evergreen.model.ResolveComponentCandidatesResult;
import com.amazonaws.services.evergreen.model.ResolvedComponentVersion;
import com.amazonaws.services.evergreen.model.ResourceNotFoundException;
import com.aws.greengrass.componentmanager.exceptions.NoAvailableComponentVersionException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import org.apache.commons.codec.Charsets;
import org.hamcrest.collection.IsMapContaining;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.ByteBuffer;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class ComponentServiceHelperTest {

    private static final Semver v1_0_0 = new Semver("1.0.0");
    private static final String COMPONENT_A = "A";

    @Mock
    private AWSEvergreen client;

    @Mock
    private GreengrassComponentServiceClientFactory clientFactory;

    private ComponentServiceHelper helper;

    @Captor
    private ArgumentCaptor<GetComponentVersionDeprecatedRequest> GetComponentVersionDeprecatedRequestArgumentCaptor;

    @BeforeEach
    void beforeEach() {
        PlatformResolver platformResolver = new PlatformResolver(null);
        lenient().when(clientFactory.getCmsClient()).thenReturn(client);
        this.helper = spy(new ComponentServiceHelper(clientFactory, platformResolver));
    }

    @Test
    void GIVEN_component_name_version_WHEN_download_component_recipe_THEN_task_succeed() throws Exception {
        String recipeContents = "testRecipeContent";
        ByteBuffer testRecipeBytes = ByteBuffer.wrap(recipeContents.getBytes());
        GetComponentVersionDeprecatedResult testResult =
                new GetComponentVersionDeprecatedResult().withRecipe(testRecipeBytes);
        doReturn(testResult).when(client)
                .getComponentVersionDeprecated(GetComponentVersionDeprecatedRequestArgumentCaptor.capture());
        String downloadPackageRecipeAsString = helper.downloadPackageRecipeAsString(
                new ComponentIdentifier(ComponentTestResourceHelper.MONITORING_SERVICE_PACKAGE_NAME,
                                        new Semver("1.0.0")));

        assertEquals(recipeContents, downloadPackageRecipeAsString);
    }

    @Test
    void GIVEN_component_version_requirements_WHEN_resolve_component_version_THEN_send_service_request()
            throws Exception {
        Map<String, Requirement> versionRequirements = new HashMap<>();
        versionRequirements.put("X", Requirement.buildNPM("^1.0"));
        versionRequirements.put("Y", Requirement.buildNPM("^1.5"));

        ResolvedComponentVersion resolvedComponentVersion =
                new ResolvedComponentVersion().withName(COMPONENT_A).withVersion(v1_0_0.getValue())
                        .withRecipe(ByteBuffer.wrap("new recipe".getBytes(Charsets.UTF_8)));
        ResolveComponentCandidatesResult result = new ResolveComponentCandidatesResult()
                .withResolvedComponentVersions(Collections.singletonList(resolvedComponentVersion));
        when(client.resolveComponentCandidates(any())).thenReturn(result);

        ResolvedComponentVersion componentContentReturn =
                helper.resolveComponentVersion(COMPONENT_A, v1_0_0, versionRequirements);

        assertThat(componentContentReturn, is(resolvedComponentVersion));
        ArgumentCaptor<ResolveComponentCandidatesRequest> requestArgumentCaptor =
                ArgumentCaptor.forClass(ResolveComponentCandidatesRequest.class);
        verify(client).resolveComponentCandidates(requestArgumentCaptor.capture());
        ResolveComponentCandidatesRequest request = requestArgumentCaptor.getValue();
        assertThat(request.getPlatform(), notNullValue());
        // assertThat(request.getPlatform().getAttributes(), notNullValue());
        // Map<String, String> attributes = request.getPlatform().getAttributes();
        // assertThat(attributes, hasKey(PlatformResolver.OS_KEY));
        // assertThat(attributes, hasKey(PlatformResolver.ARCHITECTURE_KEY));
        // assertThat(request.getPlatform().getOs(), nullValue());
        // assertThat(request.getPlatform().getArchitecture(), nullValue());
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
        when(client.resolveComponentCandidates(any())).thenThrow(ResourceNotFoundException.class);

        Exception exp = assertThrows(NoAvailableComponentVersionException.class, () -> helper
                .resolveComponentVersion(COMPONENT_A, v1_0_0,
                                         Collections.singletonMap("X", Requirement.buildNPM("^1.0"))));

        assertThat(exp.getMessage(), containsString("No applicable version found in cloud registry for component: 'A'"
                                                            + " satisfying requirement: '{X=>=1.0.0 <2.0.0}'."));
    }
}
