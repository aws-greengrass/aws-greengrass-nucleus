/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.aws.greengrass.componentmanager.exceptions.IncompatiblePlatformClaimByComponentException;
import com.aws.greengrass.componentmanager.exceptions.NoAvailableComponentVersionException;
import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testing.TestFeatureParameterInterface;
import com.aws.greengrass.testing.TestFeatureParameters;
import com.aws.greengrass.util.GreengrassServiceClientFactory;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import org.apache.commons.codec.Charsets;
import org.hamcrest.collection.IsMapContaining;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.greengrassv2data.GreengrassV2DataClient;
import software.amazon.awssdk.services.greengrassv2data.model.ComponentCandidate;
import software.amazon.awssdk.services.greengrassv2data.model.ResolveComponentCandidatesRequest;
import software.amazon.awssdk.services.greengrassv2data.model.ResolveComponentCandidatesResponse;
import software.amazon.awssdk.services.greengrassv2data.model.ResolvedComponentVersion;
import software.amazon.awssdk.services.greengrassv2data.model.ResourceNotFoundException;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.aws.greengrass.componentmanager.ComponentServiceHelper.CLIENT_RETRY_COUNT;
import static com.aws.greengrass.componentmanager.ComponentServiceHelper.CLIENT_RETRY_INTERVAL_MILLIS_FEATURE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class ComponentServiceHelperTest {

    private static final Semver v1_0_0 = new Semver("1.0.0");
    private static final String COMPONENT_A = "A";

    @Mock
    private GreengrassV2DataClient client;

    @Mock
    private GreengrassServiceClientFactory clientFactory;

    @Mock
    private TestFeatureParameterInterface DEFAULT_HANDLER;

    private ComponentServiceHelper helper;

    @BeforeEach
    void beforeEach() {
        when(DEFAULT_HANDLER.retrieveWithDefault(eq(Duration.class), eq(CLIENT_RETRY_INTERVAL_MILLIS_FEATURE), any()))
                .thenReturn(Duration.ZERO);
        TestFeatureParameters.internalEnableTestingFeatureParameters(DEFAULT_HANDLER);
        PlatformResolver platformResolver = new PlatformResolver(null);
        this.helper = spy(new ComponentServiceHelper(clientFactory, platformResolver));
    }

    @AfterEach
    void afterEach() {
        TestFeatureParameters.internalDisableTestingFeatureParameters();
    }

    @Test
    void GIVEN_component_version_requirements_WHEN_resolve_component_version_THEN_send_service_request()
            throws Exception {
        Map<String, Requirement> versionRequirements = new HashMap<>();
        versionRequirements.put("X", Requirement.buildNPM("^1.0"));
        versionRequirements.put("Y", Requirement.buildNPM("^1.5"));

        ResolvedComponentVersion componentVersion =
                ResolvedComponentVersion.builder().componentName(COMPONENT_A).componentVersion(v1_0_0.getValue())
                        .recipe(SdkBytes.fromByteArray("new recipe" .getBytes(Charsets.UTF_8))).build();
        ResolveComponentCandidatesResponse result = ResolveComponentCandidatesResponse.builder()
                .resolvedComponentVersions(Collections.singletonList(componentVersion)).build();

        when(clientFactory.fetchGreengrassV2DataClient()).thenReturn(client);
        when(client.resolveComponentCandidates(any(ResolveComponentCandidatesRequest.class))).thenReturn(result);

        ResolvedComponentVersion componentVersionReturn =
                helper.resolveComponentVersion(COMPONENT_A, v1_0_0, versionRequirements);

        assertThat(componentVersionReturn, is(componentVersion));
        ArgumentCaptor<ResolveComponentCandidatesRequest> requestArgumentCaptor =
                ArgumentCaptor.forClass(ResolveComponentCandidatesRequest.class);
        verify(client).resolveComponentCandidates(requestArgumentCaptor.capture());
        ResolveComponentCandidatesRequest request = requestArgumentCaptor.getValue();
        assertThat(request.platform(), notNullValue());
        assertThat(request.platform().attributes(), notNullValue());
        Map<String, String> attributes = request.platform().attributes();
        assertThat(attributes, hasKey(PlatformResolver.OS_KEY));
        assertThat(attributes, hasKey(PlatformResolver.ARCHITECTURE_KEY));
        assertThat(request.componentCandidates().size(), is(1));
        ComponentCandidate candidate = request.componentCandidates().get(0);
        assertThat(candidate.componentName(), is(COMPONENT_A));
        assertThat(candidate.componentVersion(), is("1.0.0"));
        assertThat(candidate.versionRequirements(), IsMapContaining.hasEntry("X", ">=1.0.0 <2.0.0"));
        assertThat(candidate.versionRequirements(), IsMapContaining.hasEntry("Y", ">=1.5.0 <2.0.0"));
    }

    @Test
    void GIVEN_component_version_requirements_WHEN_service_no_resource_found_THEN_throw_no_available_version_exception()
            throws DeviceConfigurationException {
        String expMessage = "Component A has no usable version satisfying requirements B";
        when(clientFactory.fetchGreengrassV2DataClient()).thenReturn(client);
        when(client.resolveComponentCandidates(any(ResolveComponentCandidatesRequest.class)))
                .thenThrow(ResourceNotFoundException.builder().message(expMessage).build());

        Exception exp = assertThrows(NoAvailableComponentVersionException.class, () -> helper
                .resolveComponentVersion(COMPONENT_A, v1_0_0,
                        Collections.singletonMap("X", Requirement.buildNPM("^1.0"))));

        assertThat(exp.getMessage(),
                containsString("Component A version constraints: X requires >=1.0.0 <2.0.0."));
        assertThat(exp.getMessage(),
                containsString("No cloud component version satisfies the requirements"));
    }

    @Test
    void GIVEN_component_version_requirements_WHEN_service_incompatible_platform_claim_THEN_throw_incompatible_platform_claim_exception()
            throws DeviceConfigurationException {
        String expMessage = "The latest version of Component A doesn't claim platform B compatibility";
        when(clientFactory.fetchGreengrassV2DataClient()).thenReturn(client);
        when(client.resolveComponentCandidates(any(ResolveComponentCandidatesRequest.class)))
                .thenThrow(ResourceNotFoundException.builder().message(expMessage).build());

        Exception exp = assertThrows(IncompatiblePlatformClaimByComponentException.class, () -> helper
                .resolveComponentVersion(COMPONENT_A, v1_0_0, Collections.emptyMap()));

        assertThat(exp.getMessage(),
                containsString("The version of component requested does not claim platform compatibility"));
    }

    @Test
    void GIVEN_component_version_requirements_WHEN_fails_to_retrieve_greengrassV2DataClient_THEN_retries()
            throws Exception {
        Map<String, Requirement> versionRequirements = new HashMap<>();
        versionRequirements.put("X", Requirement.buildNPM("^1.0"));
        when(clientFactory.fetchGreengrassV2DataClient()).thenThrow(DeviceConfigurationException.class);

        assertThrows(DeviceConfigurationException.class, () ->
                helper.resolveComponentVersion(COMPONENT_A, v1_0_0, versionRequirements));

        verify(clientFactory, times(CLIENT_RETRY_COUNT)).fetchGreengrassV2DataClient();

    }
}
