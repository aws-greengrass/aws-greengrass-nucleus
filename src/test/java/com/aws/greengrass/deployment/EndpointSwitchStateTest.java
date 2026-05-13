/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class EndpointSwitchStateTest {

    @Mock
    private DeploymentService deploymentService;
    @Mock
    private Topics runtimeConfig;

    private EndpointSwitchState endpointSwitchState;

    @BeforeEach
    void beforeEach() {
        when(deploymentService.getRuntimeConfig()).thenReturn(runtimeConfig);
        endpointSwitchState = new EndpointSwitchState(deploymentService);
    }

    @Test
    void GIVEN_no_keys_WHEN_isEndpointSwitchDeployment_THEN_returns_false() {
        when(runtimeConfig.find(EndpointSwitchState.SOURCE_IOT_DATA_ENDPOINT_KEY)).thenReturn(null);

        assertFalse(endpointSwitchState.isEndpointSwitchDeployment("any-deployment-id"));
    }

    @Test
    void GIVEN_keys_present_matching_id_WHEN_isEndpointSwitchDeployment_THEN_returns_true() {
        Topic endpointTopic = mock(Topic.class);
        when(endpointTopic.getOnce()).thenReturn("source.iot.us-east-1.amazonaws.com");
        when(runtimeConfig.find(EndpointSwitchState.SOURCE_IOT_DATA_ENDPOINT_KEY)).thenReturn(endpointTopic);

        Topic deploymentIdTopic = mock(Topic.class);
        when(deploymentIdTopic.getOnce()).thenReturn("deployment-123");
        when(runtimeConfig.find(EndpointSwitchState.SOURCE_DEPLOYMENT_ID_KEY)).thenReturn(deploymentIdTopic);

        assertTrue(endpointSwitchState.isEndpointSwitchDeployment("deployment-123"));
    }

    @Test
    void GIVEN_keys_present_different_id_WHEN_isEndpointSwitchDeployment_THEN_returns_false() {
        Topic endpointTopic = mock(Topic.class);
        when(endpointTopic.getOnce()).thenReturn("source.iot.us-east-1.amazonaws.com");
        when(runtimeConfig.find(EndpointSwitchState.SOURCE_IOT_DATA_ENDPOINT_KEY)).thenReturn(endpointTopic);

        Topic deploymentIdTopic = mock(Topic.class);
        when(deploymentIdTopic.getOnce()).thenReturn("deployment-123");
        when(runtimeConfig.find(EndpointSwitchState.SOURCE_DEPLOYMENT_ID_KEY)).thenReturn(deploymentIdTopic);

        assertFalse(endpointSwitchState.isEndpointSwitchDeployment("other-deployment"));
    }

    @Test
    void GIVEN_keys_present_WHEN_getSourceIotDataEndpoint_THEN_returns_value() {
        Topic endpointTopic = mock(Topic.class);
        when(endpointTopic.getOnce()).thenReturn("source.iot.us-east-1.amazonaws.com");
        when(runtimeConfig.find(EndpointSwitchState.SOURCE_IOT_DATA_ENDPOINT_KEY)).thenReturn(endpointTopic);

        assertEquals("source.iot.us-east-1.amazonaws.com", endpointSwitchState.getSourceIotDataEndpoint());
    }

    @Test
    void GIVEN_no_keys_WHEN_getSourceIotDataEndpoint_THEN_returns_null() {
        when(runtimeConfig.find(EndpointSwitchState.SOURCE_IOT_DATA_ENDPOINT_KEY)).thenReturn(null);

        assertNull(endpointSwitchState.getSourceIotDataEndpoint());
    }

    @Test
    void GIVEN_empty_value_WHEN_getSourceIotDataEndpoint_THEN_returns_null() {
        Topic endpointTopic = mock(Topic.class);
        when(endpointTopic.getOnce()).thenReturn("");
        when(runtimeConfig.find(EndpointSwitchState.SOURCE_IOT_DATA_ENDPOINT_KEY)).thenReturn(endpointTopic);

        assertNull(endpointSwitchState.getSourceIotDataEndpoint());
    }

    @Test
    void GIVEN_values_WHEN_persist_THEN_keys_stored() {
        Topic endpointTopic = mock(Topic.class);
        when(runtimeConfig.lookup(EndpointSwitchState.SOURCE_IOT_DATA_ENDPOINT_KEY)).thenReturn(endpointTopic);
        Topic deploymentIdTopic = mock(Topic.class);
        when(runtimeConfig.lookup(EndpointSwitchState.SOURCE_DEPLOYMENT_ID_KEY)).thenReturn(deploymentIdTopic);

        endpointSwitchState.persist("source.iot.us-east-1.amazonaws.com", "deployment-456");

        verify(endpointTopic).withValue("source.iot.us-east-1.amazonaws.com");
        verify(deploymentIdTopic).withValue("deployment-456");
    }

    @Test
    void GIVEN_keys_exist_WHEN_clear_THEN_keys_removed() {
        Topic endpointTopic = mock(Topic.class);
        when(runtimeConfig.find(EndpointSwitchState.SOURCE_IOT_DATA_ENDPOINT_KEY)).thenReturn(endpointTopic);
        Topic deploymentIdTopic = mock(Topic.class);
        when(runtimeConfig.find(EndpointSwitchState.SOURCE_DEPLOYMENT_ID_KEY)).thenReturn(deploymentIdTopic);

        endpointSwitchState.clear();

        verify(endpointTopic).remove();
        verify(deploymentIdTopic).remove();
    }

    @Test
    void GIVEN_no_keys_WHEN_clear_THEN_no_error() {
        when(runtimeConfig.find(EndpointSwitchState.SOURCE_IOT_DATA_ENDPOINT_KEY)).thenReturn(null);
        when(runtimeConfig.find(EndpointSwitchState.SOURCE_DEPLOYMENT_ID_KEY)).thenReturn(null);

        // Should not throw
        endpointSwitchState.clear();
    }
}
