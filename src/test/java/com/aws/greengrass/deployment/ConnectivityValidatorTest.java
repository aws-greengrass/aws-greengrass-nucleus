/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.deployment.model.DeploymentResult.DeploymentStatus;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.security.SecurityService;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.GreengrassServiceClientFactory;
import com.aws.greengrass.util.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttException;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;
import software.amazon.awssdk.services.greengrassv2data.GreengrassV2DataClient;
import software.amazon.awssdk.services.greengrassv2data.model.ListThingGroupsForCoreDeviceRequest;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
public class ConnectivityValidatorTest {
    private static final String EXCEPTION_MSG = "expected failure message";
    @Mock
    private MqttClient mqttClient;
    @Mock
    private AwsIotMqttConnectionBuilder awsIotMqttConnectionBuilder;
    @Mock
    private MqttClientConnection mqttClientConnection;
    @Mock
    private GreengrassServiceClientFactory factory;
    @Mock
    private SecurityService securityService;
    @Mock
    private GreengrassV2DataClient greengrassV2DataClient;
    @Mock
    private DeviceConfiguration deviceConfiguration;
    @Mock
    private DeviceConfiguration deploymentConfiguration;
    @Mock
    private CompletableFuture<Boolean> mqttConnectionResult;

    private Kernel kernel;
    private Context context;
    private Topics topics;
    private Topic topic;
    private Topic differentTopic;
    private ConnectivityValidator validator;


    @BeforeEach
    void beforeEach() throws InterruptedException {
        kernel = new Kernel();
        context = kernel.getContext();
        topics = Topics.of(context, "topics-name", null);
        topic = Topic.of(context, "topic-name", "value");
        differentTopic = Topic.of(context, "topic-name", "different-value");
        validator = new ConnectivityValidator(kernel, context, mqttClient, factory, securityService, new HashMap<>(), 0);
        validator.setDeploymentConfiguration(deploymentConfiguration);
    }

    @AfterEach
    void afterEach() {
        kernel.shutdown();
    }

    @Test
    void GIVEN_connectivity_validator_When_new_configuration_is_valid_THEN_return_true() {
        when(deviceConfiguration.getNetworkProxyNamespace()).thenReturn(topics);
        when(deploymentConfiguration.getNetworkProxyNamespace()).thenReturn(topics);
        when(deviceConfiguration.getAWSRegion()).thenReturn(topic);
        when(deploymentConfiguration.getAWSRegion()).thenReturn(differentTopic);

        // Mock Mqtt Validation
        String thingName = "thingName";
        String expectedClientId = thingName + ConnectivityValidator.CLIENT_ID_SUFFIX;
        int mqttTimeout = 1000;
        CompletableFuture<Boolean> mqttConnection = CompletableFuture.completedFuture(true);

        when(mqttClient.createMqttConnectionBuilder(deploymentConfiguration, securityService, null))
                .thenReturn(awsIotMqttConnectionBuilder);
        when(deploymentConfiguration.getThingName()).thenReturn(Topic.of(context, "name", thingName));
        when(awsIotMqttConnectionBuilder.withClientId(expectedClientId)).thenReturn(awsIotMqttConnectionBuilder);
        when(awsIotMqttConnectionBuilder.build()).thenReturn(mqttClientConnection);
        Topics mockMqttTopics = mock(Topics.class);
        when(deploymentConfiguration.getMQTTNamespace()).thenReturn(mockMqttTopics);
        when(mockMqttTopics.findOrDefault(anyInt(), anyString())).thenReturn(mqttTimeout);
        when(mqttClientConnection.connect()).thenReturn(mqttConnection);

        // Mock Http Validation
        when(factory.createClientFromConfig(any())).thenReturn(new Pair<>(null, greengrassV2DataClient));
        when(greengrassV2DataClient.listThingGroupsForCoreDevice((ListThingGroupsForCoreDeviceRequest) any()))
                .thenReturn(null);

        CompletableFuture<DeploymentResult> totallyCompleteFuture = new CompletableFuture<>();
        assertTrue(validator.validate(totallyCompleteFuture, deviceConfiguration));
    }

    @Test
    void GIVEN_config_When_connectivity_validation_is_disabled_THEN_return_false() {
        when(deploymentConfiguration.isConnectivityValidationEnabled()).thenReturn(false);
        assertFalse(validator.isValidationEnabled());
    }

    @Test
    void GIVEN_offline_config_When_connectivity_validation_is_enabled_THEN_return_false() {
        when(deploymentConfiguration.isConnectivityValidationEnabled()).thenReturn(true);
        when(deploymentConfiguration.isDeviceConfiguredToTalkToCloud()).thenReturn(false);
        assertFalse(validator.isValidationEnabled());
    }

    @Test
    void GIVEN_online_config_When_connectivity_validation_is_enabled_THEN_return_true() {
        when(deploymentConfiguration.isConnectivityValidationEnabled()).thenReturn(true);
        when(deploymentConfiguration.isDeviceConfiguredToTalkToCloud()).thenReturn(true);
        assertTrue(validator.isValidationEnabled());
    }

    @Test
    void GIVEN_http_exception_WHEN_http_validation_THEN_deployment_fails(ExtensionContext ctx) throws ExecutionException, InterruptedException {
        // Ignore the expected exception thrown
        ignoreExceptionOfType(ctx, RuntimeException.class);

        // Mock Http exception
        when(factory.createClientFromConfig(any())).thenReturn(new Pair<>(null, greengrassV2DataClient));
        when(greengrassV2DataClient.listThingGroupsForCoreDevice((ListThingGroupsForCoreDeviceRequest) any()))
                .thenThrow(new RuntimeException(EXCEPTION_MSG));

        // WHEN
        CompletableFuture<DeploymentResult> totallyCompleteFuture = new CompletableFuture<>();
        assertFalse(validator.httpClientCanConnect(totallyCompleteFuture));

        // THEN
        assertTrue(totallyCompleteFuture.isDone());
        DeploymentResult result = totallyCompleteFuture.get();
        String message = result.getFailureCause().getMessage();
        String cause = result.getFailureCause().getCause().getMessage();
        assertEquals(result.getDeploymentStatus(), DeploymentStatus.FAILED_NO_STATE_CHANGE);
        assertTrue(message.contains("HTTP client validation failed"));
        assertTrue(cause.contains(EXCEPTION_MSG));
    }

    @Test
    void GIVEN_mqtt_exception_WHEN_mqtt_validation_THEN_deployment_fails(ExtensionContext ctx) throws ExecutionException, InterruptedException {
        // Ignore the expected exception thrown
        ignoreExceptionOfType(ctx, MqttException.class);

        // Mock Mqtt Exception
        String thingName = "thingName";
        String expectedClientId = thingName + ConnectivityValidator.CLIENT_ID_SUFFIX;
        int mqttTimeout = 1000;
        when(mqttClient.createMqttConnectionBuilder(deploymentConfiguration, securityService, null))
                .thenReturn(awsIotMqttConnectionBuilder);
        when(deploymentConfiguration.getThingName()).thenReturn(Topic.of(context, "name", thingName));
        when(awsIotMqttConnectionBuilder.withClientId(expectedClientId)).thenReturn(awsIotMqttConnectionBuilder);
        when(awsIotMqttConnectionBuilder.build()).thenReturn(mqttClientConnection);
        Topics mockMqttTopics = mock(Topics.class);
        when(deploymentConfiguration.getMQTTNamespace()).thenReturn(mockMqttTopics);
        when(mockMqttTopics.findOrDefault(anyInt(), anyString())).thenReturn(mqttTimeout);
        when(mqttClientConnection.connect()).thenThrow(new MqttException(EXCEPTION_MSG));

        // WHEN
        CompletableFuture<DeploymentResult> totallyCompleteFuture = new CompletableFuture<>();
        assertFalse(validator.mqttClientCanConnect(totallyCompleteFuture));

        // THEN
        assertTrue(totallyCompleteFuture.isDone());
        DeploymentResult result = totallyCompleteFuture.get();
        String message = result.getFailureCause().getMessage();
        String cause = result.getFailureCause().getCause().getMessage();
        assertEquals(result.getDeploymentStatus(), DeploymentStatus.FAILED_NO_STATE_CHANGE);
        assertTrue(message.contains("failed to connect"));
        assertTrue(cause.contains(EXCEPTION_MSG));
    }

    @Test
    void GIVEN_timeout_exception_WHEN_mqtt_validation_THEN_deployment_fails(ExtensionContext ctx) throws ExecutionException, InterruptedException, TimeoutException {
        // Ignore the expected exception thrown
        ignoreExceptionOfType(ctx, TimeoutException.class);

        // Mock Mqtt Exception
        String thingName = "thingName";
        String expectedClientId = thingName + ConnectivityValidator.CLIENT_ID_SUFFIX;
        int mqttTimeout = 1000;
        when(mqttClient.createMqttConnectionBuilder(deploymentConfiguration, securityService, null))
                .thenReturn(awsIotMqttConnectionBuilder);
        when(deploymentConfiguration.getThingName()).thenReturn(Topic.of(context, "name", thingName));
        when(awsIotMqttConnectionBuilder.withClientId(expectedClientId)).thenReturn(awsIotMqttConnectionBuilder);
        when(awsIotMqttConnectionBuilder.build()).thenReturn(mqttClientConnection);
        Topics mockMqttTopics = mock(Topics.class);
        when(deploymentConfiguration.getMQTTNamespace()).thenReturn(mockMqttTopics);
        when(mockMqttTopics.findOrDefault(anyInt(), anyString())).thenReturn(mqttTimeout);
        when(mqttClientConnection.connect()).thenReturn(mqttConnectionResult);
        when(mqttConnectionResult.get(mqttTimeout, TimeUnit.MILLISECONDS)).thenThrow(new TimeoutException(EXCEPTION_MSG));

        // WHEN
        CompletableFuture<DeploymentResult> totallyCompleteFuture = new CompletableFuture<>();
        assertFalse(validator.mqttClientCanConnect(totallyCompleteFuture));

        // THEN
        assertTrue(totallyCompleteFuture.isDone());
        DeploymentResult result = totallyCompleteFuture.get();
        String message = result.getFailureCause().getMessage();
        String cause = result.getFailureCause().getCause().getMessage();
        assertEquals(result.getDeploymentStatus(), DeploymentStatus.FAILED_NO_STATE_CHANGE);
        assertTrue(message.contains("validation timed out"));
        assertTrue(cause.contains(EXCEPTION_MSG));
    }

    @Test
    void GIVEN_execution_exception_WHEN_mqtt_validation_THEN_deployment_fails(ExtensionContext ctx) throws ExecutionException, InterruptedException, TimeoutException {
        // Ignore the expected exception thrown
        ignoreExceptionOfType(ctx, ExecutionException.class);

        // Mock Mqtt Exception
        String thingName = "thingName";
        String expectedClientId = thingName + ConnectivityValidator.CLIENT_ID_SUFFIX;
        int mqttTimeout = 1000;
        when(mqttClient.createMqttConnectionBuilder(deploymentConfiguration, securityService, null))
                .thenReturn(awsIotMqttConnectionBuilder);
        when(deploymentConfiguration.getThingName()).thenReturn(Topic.of(context, "name", thingName));
        when(awsIotMqttConnectionBuilder.withClientId(expectedClientId)).thenReturn(awsIotMqttConnectionBuilder);
        when(awsIotMqttConnectionBuilder.build()).thenReturn(mqttClientConnection);
        Topics mockMqttTopics = mock(Topics.class);
        when(deploymentConfiguration.getMQTTNamespace()).thenReturn(mockMqttTopics);
        when(mockMqttTopics.findOrDefault(anyInt(), anyString())).thenReturn(mqttTimeout);
        when(mqttClientConnection.connect()).thenReturn(mqttConnectionResult);
        Throwable executionThrowable = new Throwable(EXCEPTION_MSG);
        when(mqttConnectionResult.get(mqttTimeout, TimeUnit.MILLISECONDS)).thenThrow(new ExecutionException(executionThrowable));

        // WHEN
        CompletableFuture<DeploymentResult> totallyCompleteFuture = new CompletableFuture<>();
        assertFalse(validator.mqttClientCanConnect(totallyCompleteFuture));

        // THEN
        assertTrue(totallyCompleteFuture.isDone());
        DeploymentResult result = totallyCompleteFuture.get();
        String message = result.getFailureCause().getMessage();
        String cause = result.getFailureCause().getCause().getMessage();
        assertEquals(result.getDeploymentStatus(), DeploymentStatus.FAILED_NO_STATE_CHANGE);
        assertTrue(message.contains("completed exceptionally"));
        assertTrue(cause.contains(EXCEPTION_MSG));
    }

    @Test
    void GIVEN_interrupted_exception_WHEN_mqtt_validation_THEN_deployment_fails(ExtensionContext ctx) throws ExecutionException, InterruptedException, TimeoutException {
        // Ignore the expected exception thrown
        ignoreExceptionOfType(ctx, InterruptedException.class);

        // Mock Mqtt Exception
        String thingName = "thingName";
        String expectedClientId = thingName + ConnectivityValidator.CLIENT_ID_SUFFIX;
        int mqttTimeout = 1000;
        when(mqttClient.createMqttConnectionBuilder(deploymentConfiguration, securityService, null))
                .thenReturn(awsIotMqttConnectionBuilder);
        when(deploymentConfiguration.getThingName()).thenReturn(Topic.of(context, "name", thingName));
        when(awsIotMqttConnectionBuilder.withClientId(expectedClientId)).thenReturn(awsIotMqttConnectionBuilder);
        when(awsIotMqttConnectionBuilder.build()).thenReturn(mqttClientConnection);
        Topics mockMqttTopics = mock(Topics.class);
        when(deploymentConfiguration.getMQTTNamespace()).thenReturn(mockMqttTopics);
        when(mockMqttTopics.findOrDefault(anyInt(), anyString())).thenReturn(mqttTimeout);
        when(mqttClientConnection.connect()).thenReturn(mqttConnectionResult);
        when(mqttConnectionResult.get(mqttTimeout, TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException(EXCEPTION_MSG));

        // WHEN
        CompletableFuture<DeploymentResult> totallyCompleteFuture = new CompletableFuture<>();
        assertFalse(validator.mqttClientCanConnect(totallyCompleteFuture));

        //THEN
        assertTrue(totallyCompleteFuture.isDone());
        DeploymentResult result = totallyCompleteFuture.get();
        String message = result.getFailureCause().getMessage();
        String cause = result.getFailureCause().getCause().getMessage();
        assertEquals(result.getDeploymentStatus(), DeploymentStatus.FAILED_NO_STATE_CHANGE);
        assertTrue(message.contains("connection was interrupted"));
        assertTrue(cause.contains(EXCEPTION_MSG));
    }
}