/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.config.ChildChanged;
import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.deployment.model.Deployment.DeploymentType;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.StandaloneMqttConnector;
import com.aws.greengrass.security.SecurityService;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.iotshadow.IotShadowClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_IOT_DATA_ENDPOINT;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
public class ShadowDeploymentListenerTest {

    private static final String TEST_CONFIG_ARN = "arn:aws:greengrass:us-east-1:123:config";

    @Mock
    private DeploymentQueue mockDeploymentQueue;
    @Mock
    private DeploymentStatusKeeper mockDeploymentStatusKeeper;
    @Mock
    private MqttClient mockMqttClient;
    @Mock
    private DeviceConfiguration mockDeviceConfiguration;
    @Mock
    private IotShadowClient mockIotShadowClient;
    @Mock
    private Kernel mockKernel;
    @Mock
    private Context mockContext;

    private ExecutorService mockExecutorService;

    private ShadowDeploymentListener shadowDeploymentListener;

    @BeforeEach
    public void setup() {
        mockExecutorService = Executors.newSingleThreadExecutor();
        shadowDeploymentListener = new ShadowDeploymentListener(mockDeploymentQueue, mockDeploymentStatusKeeper,
                mockMqttClient, mockExecutorService, mockDeviceConfiguration, mockIotShadowClient, mockKernel);
    }

    @AfterEach
    public void close() {
        mockExecutorService.shutdownNow();
    }

    @Test
    public void testCommunicationWithIotCore_successful() {
        when(mockKernel.getContext()).thenReturn(mockContext);
        lenient().when(mockContext.runOnPublishQueueAndWait(any())).thenReturn(null);
        doReturn(CompletableFuture.completedFuture(null)).when(mockIotShadowClient)
                .SubscribeToUpdateNamedShadowAccepted(any(), any(), any(), any());
        doReturn(CompletableFuture.completedFuture(null)).when(mockIotShadowClient)
                .SubscribeToUpdateNamedShadowRejected(any(), any(), any(), any());
        doReturn(CompletableFuture.completedFuture(null)).when(mockIotShadowClient)
                .SubscribeToGetNamedShadowAccepted(any(), any(), any(), any());
        shadowDeploymentListener.postInject();
        verify(mockMqttClient, times(1)).addToCallbackEvents(any());
        verify(mockIotShadowClient, timeout(500).times(1))
                .SubscribeToUpdateNamedShadowAccepted(any(), any(), any(), any());
        verify(mockIotShadowClient, timeout(500).times(1))
                .SubscribeToUpdateNamedShadowRejected(any(), any(), any(), any());
        verify(mockIotShadowClient, timeout(500).times(1))
                .SubscribeToGetNamedShadowAccepted(any(), any(), any(), any());
    }

    @Test
    public void testCommunicationWithIotCore_unsuccessful() throws DeviceConfigurationException {
        doThrow(new DeviceConfigurationException("Error")).when(mockDeviceConfiguration).validate();
        shadowDeploymentListener.postInject();
        verify(mockMqttClient, times(1)).addToCallbackEvents(any());
        verify(mockIotShadowClient, timeout(500).times(0))
                .SubscribeToUpdateNamedShadowAccepted(any(), any(), any(), any());
        verify(mockIotShadowClient, timeout(500).times(0))
                .SubscribeToUpdateNamedShadowRejected(any(), any(), any(), any());
        verify(mockIotShadowClient, timeout(500).times(0))
                .SubscribeToGetNamedShadowAccepted(any(), any(), any(), any());
    }

    @Test
    public void testCommunicationWithIotCore_unsuccessful_THEN_retry_on_update() throws DeviceConfigurationException {
        when(mockKernel.getContext()).thenReturn(mockContext);
        lenient().when(mockContext.runOnPublishQueueAndWait(any())).thenReturn(null);
        doThrow(new DeviceConfigurationException("Error")).doNothing().when(mockDeviceConfiguration).validate();
        doReturn(CompletableFuture.completedFuture(null)).when(mockIotShadowClient)
                .SubscribeToUpdateNamedShadowAccepted(any(), any(), any(), any());
        doReturn(CompletableFuture.completedFuture(null)).when(mockIotShadowClient)
                .SubscribeToUpdateNamedShadowRejected(any(), any(), any(), any());
        doReturn(CompletableFuture.completedFuture(null)).when(mockIotShadowClient)
                .SubscribeToGetNamedShadowAccepted(any(), any(), any(), any());
        shadowDeploymentListener.postInject();
        ArgumentCaptor<ChildChanged> ccCaptor = ArgumentCaptor.forClass(ChildChanged.class);
        verify(mockDeviceConfiguration).onAnyChange(ccCaptor.capture());
        Node mockNode = mock(Node.class);
        lenient().when(mockNode.childOf(DEVICE_PARAM_IOT_DATA_ENDPOINT)).thenReturn(true);
        ccCaptor.getValue().childChanged(WhatHappened.childChanged, mockNode);
        verify(mockMqttClient, times(1)).addToCallbackEvents(any());
        verify(mockIotShadowClient, timeout(1000).times(1))
                .SubscribeToUpdateNamedShadowAccepted(any(), any(), any(), any());
        verify(mockIotShadowClient, timeout(1000).times(1))
                .SubscribeToUpdateNamedShadowRejected(any(), any(), any(), any());
        verify(mockIotShadowClient, timeout(1000).times(1))
                .SubscribeToGetNamedShadowAccepted(any(), any(), any(), any());
    }

    @Test
    void GIVEN_source_endpoint_and_id_match_WHEN_shadow_status_changed_THEN_publishes_to_source_and_clears()
            throws Exception {
        EndpointSwitchState mockEndpointSwitchState = mock(EndpointSwitchState.class);
        when(mockContext.get(EndpointSwitchState.class)).thenReturn(mockEndpointSwitchState);
        when(mockKernel.getContext()).thenReturn(mockContext);
        lenient().when(mockContext.runOnPublishQueueAndWait(any())).thenReturn(null);
        doReturn(CompletableFuture.completedFuture(null)).when(mockIotShadowClient)
                .SubscribeToUpdateNamedShadowAccepted(any(), any(), any(), any());
        doReturn(CompletableFuture.completedFuture(null)).when(mockIotShadowClient)
                .SubscribeToUpdateNamedShadowRejected(any(), any(), any(), any());
        doReturn(CompletableFuture.completedFuture(null)).when(mockIotShadowClient)
                .SubscribeToGetNamedShadowAccepted(any(), any(), any(), any());

        when(mockEndpointSwitchState.isEndpointSwitchDeployment(TEST_CONFIG_ARN)).thenReturn(true);
        when(mockEndpointSwitchState.getSourceIotDataEndpoint()).thenReturn("source.iot.us-east-1.amazonaws.com");

        when(mockDeviceConfiguration.getStandaloneMqttTimeout()).thenReturn(60_000L);

        Topic thingNameTopic = mock(Topic.class);
        when(thingNameTopic.getOnce()).thenReturn("TestThing");
        when(mockDeviceConfiguration.getThingName()).thenReturn(thingNameTopic);

        SecurityService mockSecurityService = mock(SecurityService.class);
        when(mockContext.get(SecurityService.class)).thenReturn(mockSecurityService);

        try (StandaloneMqttConnector mockConnector = mock(StandaloneMqttConnector.class);
             MockedStatic<StandaloneMqttConnector> staticMock = mockStatic(StandaloneMqttConnector.class)) {
            staticMock.when(() -> StandaloneMqttConnector.forEndpointSwitch(any(), any(), any()))
                    .thenReturn(mockConnector);

            // Capture the consumer registered with deploymentStatusKeeper
            ArgumentCaptor<java.util.function.Function> consumerCaptor =
                    ArgumentCaptor.forClass(java.util.function.Function.class);
            shadowDeploymentListener.postInject();
            verify(mockDeploymentStatusKeeper, timeout(5000)).registerDeploymentStatusConsumer(
                    eq(DeploymentType.SHADOW), consumerCaptor.capture(), any());

            Map<String, Object> details = new HashMap<>();
            details.put("ConfigurationArn", TEST_CONFIG_ARN);
            details.put("DeploymentStatus", "SUCCEEDED");
            Map<String, Object> statusDetails = new HashMap<>();
            statusDetails.put("detailed-deployment-status", "SUCCESSFUL");
            details.put("DeploymentStatusDetails", statusDetails);

            Boolean result = (Boolean) consumerCaptor.getValue().apply(details);
            assertTrue(result);

            verify(mockConnector).connect(60_000L);
            ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);
            verify(mockConnector).publish(
                    contains("shadow/name/AWSManagedGreengrassV2Deployment/update"),
                    payloadCaptor.capture(), eq(QualityOfService.AT_LEAST_ONCE), eq(60_000L));
            // Verify shadow payload structure
            String payload = new String(payloadCaptor.getValue(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(payload.contains("\"status\":\"SUCCEEDED\""), "Payload should contain status");
            assertTrue(payload.contains("\"state\""), "Payload should have state wrapper");
            assertTrue(payload.contains("\"reported\""), "Payload should have reported section");
            assertFalse(payload.contains("\"version\""), "Shadow update should NOT contain version field");
            verify(mockEndpointSwitchState).clear();
        }
    }

    @Test
    void GIVEN_endpoint_switch_IN_PROGRESS_WHEN_shadow_status_changed_THEN_returns_true_without_publish()
            throws Exception {
        EndpointSwitchState mockEndpointSwitchState = mock(EndpointSwitchState.class);
        lenient().when(mockContext.get(EndpointSwitchState.class)).thenReturn(mockEndpointSwitchState);
        lenient().when(mockKernel.getContext()).thenReturn(mockContext);
        lenient().when(mockContext.runOnPublishQueueAndWait(any())).thenReturn(null);
        lenient().doReturn(CompletableFuture.completedFuture(null)).when(mockIotShadowClient)
                .SubscribeToUpdateNamedShadowAccepted(any(), any(), any(), any());
        lenient().doReturn(CompletableFuture.completedFuture(null)).when(mockIotShadowClient)
                .SubscribeToUpdateNamedShadowRejected(any(), any(), any(), any());
        lenient().doReturn(CompletableFuture.completedFuture(null)).when(mockIotShadowClient)
                .SubscribeToGetNamedShadowAccepted(any(), any(), any(), any());

        when(mockEndpointSwitchState.isEndpointSwitchDeployment(TEST_CONFIG_ARN))
                .thenReturn(true);

        ArgumentCaptor<java.util.function.Function> consumerCaptor =
                ArgumentCaptor.forClass(java.util.function.Function.class);
        shadowDeploymentListener.postInject();
        verify(mockDeploymentStatusKeeper, timeout(5000)).registerDeploymentStatusConsumer(
                eq(DeploymentType.SHADOW), consumerCaptor.capture(), any());

        Map<String, Object> details = new HashMap<>();
        details.put("ConfigurationArn", TEST_CONFIG_ARN);
        details.put("DeploymentStatus", "IN_PROGRESS");
        Map<String, Object> statusDetails = new HashMap<>();
        details.put("DeploymentStatusDetails", statusDetails);

        Boolean result = (Boolean) consumerCaptor.getValue().apply(details);
        assertTrue(result);

        // IN_PROGRESS should be dropped — no standalone connection, no clear
        verify(mockEndpointSwitchState, never()).getSourceIotDataEndpoint();
        verify(mockEndpointSwitchState, never()).clear();
    }

    @Test
    void GIVEN_source_endpoint_id_mismatch_WHEN_shadow_status_changed_THEN_uses_normal_path() throws Exception {
        EndpointSwitchState mockEndpointSwitchState = mock(EndpointSwitchState.class);
        when(mockContext.get(EndpointSwitchState.class)).thenReturn(mockEndpointSwitchState);
        when(mockKernel.getContext()).thenReturn(mockContext);
        lenient().when(mockContext.runOnPublishQueueAndWait(any())).thenReturn(null);
        lenient().doReturn(CompletableFuture.completedFuture(null)).when(mockIotShadowClient)
                .SubscribeToUpdateNamedShadowAccepted(any(), any(), any(), any());
        lenient().doReturn(CompletableFuture.completedFuture(null)).when(mockIotShadowClient)
                .SubscribeToUpdateNamedShadowRejected(any(), any(), any(), any());
        lenient().doReturn(CompletableFuture.completedFuture(null)).when(mockIotShadowClient)
                .SubscribeToGetNamedShadowAccepted(any(), any(), any(), any());

        when(mockEndpointSwitchState.isEndpointSwitchDeployment(TEST_CONFIG_ARN)).thenReturn(false);

        ArgumentCaptor<java.util.function.Function> consumerCaptor =
                ArgumentCaptor.forClass(java.util.function.Function.class);
        shadowDeploymentListener.postInject();
        verify(mockDeploymentStatusKeeper, timeout(5000)).registerDeploymentStatusConsumer(
                eq(DeploymentType.SHADOW), consumerCaptor.capture(), any());

        Map<String, Object> details = new HashMap<>();
        details.put("ConfigurationArn", TEST_CONFIG_ARN);
        details.put("DeploymentStatus", "SUCCEEDED");
        Map<String, Object> statusDetails = new HashMap<>();
        statusDetails.put("detailed-deployment-status", "SUCCESSFUL");
        details.put("DeploymentStatusDetails", statusDetails);

        when(mockIotShadowClient.PublishUpdateNamedShadow(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(0));
        consumerCaptor.getValue().apply(details);

        // Not an endpoint-switch deployment — no source key clearing
        verify(mockEndpointSwitchState, never()).clear();
    }

    @Test
    void GIVEN_no_source_endpoint_WHEN_shadow_status_changed_THEN_normal_path() throws Exception {
        EndpointSwitchState mockEndpointSwitchState = mock(EndpointSwitchState.class);
        when(mockContext.get(EndpointSwitchState.class)).thenReturn(mockEndpointSwitchState);
        when(mockKernel.getContext()).thenReturn(mockContext);
        lenient().when(mockContext.runOnPublishQueueAndWait(any())).thenReturn(null);
        doReturn(CompletableFuture.completedFuture(null)).when(mockIotShadowClient)
                .SubscribeToUpdateNamedShadowAccepted(any(), any(), any(), any());
        doReturn(CompletableFuture.completedFuture(null)).when(mockIotShadowClient)
                .SubscribeToUpdateNamedShadowRejected(any(), any(), any(), any());
        doReturn(CompletableFuture.completedFuture(null)).when(mockIotShadowClient)
                .SubscribeToGetNamedShadowAccepted(any(), any(), any(), any());

        when(mockEndpointSwitchState.isEndpointSwitchDeployment(TEST_CONFIG_ARN)).thenReturn(false);

        ArgumentCaptor<java.util.function.Function> consumerCaptor =
                ArgumentCaptor.forClass(java.util.function.Function.class);
        shadowDeploymentListener.postInject();
        verify(mockDeploymentStatusKeeper, timeout(5000)).registerDeploymentStatusConsumer(
                eq(DeploymentType.SHADOW), consumerCaptor.capture(), any());

        Map<String, Object> details = new HashMap<>();
        details.put("ConfigurationArn", TEST_CONFIG_ARN);
        details.put("DeploymentStatus", "SUCCEEDED");
        Map<String, Object> statusDetails = new HashMap<>();
        statusDetails.put("detailed-deployment-status", "SUCCESSFUL");
        details.put("DeploymentStatusDetails", statusDetails);

        // Normal path — no source routing
        when(mockIotShadowClient.PublishUpdateNamedShadow(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(0));
        consumerCaptor.getValue().apply(details);
    }
}
