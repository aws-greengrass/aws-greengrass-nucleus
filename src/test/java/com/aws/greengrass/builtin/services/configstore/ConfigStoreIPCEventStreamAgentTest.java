/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.configstore;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.UpdateBehaviorTree;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Coerce;
import org.hamcrest.collection.IsMapContaining;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.aws.greengrass.model.ConfigurationUpdateEvents;
import software.amazon.awssdk.aws.greengrass.model.ConfigurationValidityReport;
import software.amazon.awssdk.aws.greengrass.model.ConfigurationValidityStatus;
import software.amazon.awssdk.aws.greengrass.model.FailedUpdateConditionCheckError;
import software.amazon.awssdk.aws.greengrass.model.GetConfigurationRequest;
import software.amazon.awssdk.aws.greengrass.model.GetConfigurationResponse;
import software.amazon.awssdk.aws.greengrass.model.InvalidArgumentsError;
import software.amazon.awssdk.aws.greengrass.model.ResourceNotFoundError;
import software.amazon.awssdk.aws.greengrass.model.SendConfigurationValidityReportRequest;
import software.amazon.awssdk.aws.greengrass.model.SendConfigurationValidityReportResponse;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToConfigurationUpdateRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToConfigurationUpdateResponse;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToValidateConfigurationUpdatesRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToValidateConfigurationUpdatesResponse;
import software.amazon.awssdk.aws.greengrass.model.UpdateConfigurationRequest;
import software.amazon.awssdk.aws.greengrass.model.UpdateConfigurationResponse;
import software.amazon.awssdk.aws.greengrass.model.ValidateConfigurationUpdateEvents;
import software.amazon.awssdk.crt.eventstream.MessageType;
import software.amazon.awssdk.crt.eventstream.ServerConnectionContinuation;
import software.amazon.awssdk.eventstreamrpc.AuthenticationData;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.ACCESS_CONTROL_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class ConfigStoreIPCEventStreamAgentTest {
    private static final String TEST_COMPONENT_A = "Component_A";
    private static final String TEST_COMPONENT_B = "Component_B";
    private static final String TEST_CONFIG_KEY_1 = "temperature";
    private static final String TEST_CONFIG_KEY_2 = "humidity";
    private static final String TEST_CONFIG_KEY_3 = "AirPressure";
    private static final int TEST_CONFIG_KEY_1_INITIAL_VALUE = 20;
    private static final int TEST_CONFIG_KEY_2_INITIAL_VALUE = 15;
    private static final int TEST_CONFIG_KEY_3_INITIAL_VALUE = 100;

    @Mock
    private Kernel kernel;
    @Mock
    OperationContinuationHandlerContext mockContext;
    @Mock
    AuthenticationData mockAuthenticationData;
    @Mock
    OperationContinuationHandlerContext mockContext2;
    @Mock
    AuthenticationData mockAuthenticationData2;
    @Mock
    ServerConnectionContinuation mockServerConnectionContinuation;
    @Captor
    ArgumentCaptor<byte[]> byteArrayCaptor;

    private ConfigStoreIPCEventStreamAgent agent;
    private Configuration configuration;

    @BeforeEach
    public void setup() {
        configuration = new Configuration(new Context());
        Topics root = configuration.getRoot();
        root.lookup(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A, CONFIGURATION_CONFIG_KEY, TEST_CONFIG_KEY_1)
                .withNewerValue(100, TEST_CONFIG_KEY_1_INITIAL_VALUE);
        root.lookup(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A, CONFIGURATION_CONFIG_KEY, TEST_CONFIG_KEY_2)
                .withNewerValue(100, TEST_CONFIG_KEY_2_INITIAL_VALUE);
        root.lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_B);
        root.lookup(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_B, CONFIGURATION_CONFIG_KEY, TEST_CONFIG_KEY_3)
                .withNewerValue(100, TEST_CONFIG_KEY_3_INITIAL_VALUE);
        configuration.context.waitForPublishQueueToClear();
        lenient().when(kernel.getConfig()).thenReturn(configuration);

        when(mockContext.getContinuation()).thenReturn(mockServerConnectionContinuation);
        when(mockContext.getAuthenticationData()).thenReturn(mockAuthenticationData);
        lenient().when(mockContext2.getContinuation()).thenReturn(mock(ServerConnectionContinuation.class));
        lenient().when(mockContext2.getAuthenticationData()).thenReturn(mockAuthenticationData2);
        agent = new ConfigStoreIPCEventStreamAgent();
        agent.setKernel(kernel);
    }

    @AfterEach
    void cleanup() throws IOException {
        configuration.context.close();
    }

    @Test
    void GIVEN_agent_running_WHEN_get_config_request_for_own_config_THEN_return_config_value() {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_COMPONENT_A);
        when(kernel.findServiceTopic(TEST_COMPONENT_A))
                .thenReturn(configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A));
        GetConfigurationRequest request = new GetConfigurationRequest();
        request.setComponentName(TEST_COMPONENT_A);
        request.setKeyPath(Collections.singletonList(TEST_CONFIG_KEY_1));
        GetConfigurationResponse response = agent.getGetConfigurationHandler(mockContext).handleRequest(request);
        assertEquals(20, response.getValue().get(TEST_CONFIG_KEY_1));
    }

    @Test
    void GIVEN_agent_running_WHEN_get_config_request_for_cross_component_config_THEN_return_config_value() {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_COMPONENT_A);
        when(kernel.findServiceTopic(TEST_COMPONENT_B))
                .thenReturn(configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_B));
        GetConfigurationRequest request = new GetConfigurationRequest();
        request.setComponentName(TEST_COMPONENT_B);
        request.setKeyPath(Collections.singletonList(TEST_CONFIG_KEY_3));
        GetConfigurationResponse response = agent.getGetConfigurationHandler(mockContext).handleRequest(request);
        assertEquals(100, response.getValue().get(TEST_CONFIG_KEY_3));
    }

    @Test
    void GIVEN_agent_running_WHEN_get_config_request_for_nested_leaf_key_THEN_return_value() {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_COMPONENT_A);
        Topics componentAConfiguration =
                configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A);
        componentAConfiguration.lookup(CONFIGURATION_CONFIG_KEY, "SomeContainerKey", "SomeLeafKey").withValue("SomeValue");
        when(kernel.findServiceTopic(TEST_COMPONENT_A)).thenReturn(componentAConfiguration);
        GetConfigurationRequest request = new GetConfigurationRequest();
        request.setComponentName(TEST_COMPONENT_A);
        request.setKeyPath(Arrays.asList("SomeContainerKey", "SomeLeafKey"));
        GetConfigurationResponse response = agent.getGetConfigurationHandler(mockContext).handleRequest(request);
        assertEquals("SomeValue", response.getValue().get("SomeLeafKey"));
    }

    @Test
    void GIVEN_agent_running_WHEN_get_config_request_for_container_node_THEN_return_subtree_as_pojo() {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_COMPONENT_A);
        Topics componentAConfiguration =
                configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A);
        componentAConfiguration.lookup(CONFIGURATION_CONFIG_KEY, "SomeContainerKey", "SomeLeafKey").withValue("SomeValue");
        when(kernel.findServiceTopic(TEST_COMPONENT_A)).thenReturn(componentAConfiguration);
        GetConfigurationRequest request = new GetConfigurationRequest();
        request.setComponentName(TEST_COMPONENT_A);
        request.setKeyPath(Collections.singletonList("SomeContainerKey"));
        GetConfigurationResponse response = agent.getGetConfigurationHandler(mockContext).handleRequest(request);
        assertTrue(response.getValue() instanceof Map);
        Map<String, String> value = (Map) response.getValue();
        assertThat(value, IsMapContaining.hasEntry("SomeLeafKey", "SomeValue"));
    }

    @Test
    void GIVEN_agent_running_WHEN_get_config_request_for_nested_container_node_THEN_return_subtree_as_pojo() {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_COMPONENT_A);
        Topics componentAConfiguration =
                configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A);
        componentAConfiguration.lookup(CONFIGURATION_CONFIG_KEY, "Level1ContainerKey", "Level2ContainerKey", "SomeLeafKey")
                .withValue("SomeValue");
        when(kernel.findServiceTopic(TEST_COMPONENT_A)).thenReturn(componentAConfiguration);
        GetConfigurationRequest request = new GetConfigurationRequest();
        request.setComponentName(TEST_COMPONENT_A);
        request.setKeyPath(Arrays.asList("Level1ContainerKey", "Level2ContainerKey"));
        GetConfigurationResponse response = agent.getGetConfigurationHandler(mockContext).handleRequest(request);
        assertTrue(response.getValue() instanceof Map);
        Map<String, String> value = (Map) response.getValue();
        assertThat(value, IsMapContaining.hasEntry("SomeLeafKey", "SomeValue"));
    }

    @Test
    void GIVEN_get_config_request_WHEN_key_does_not_exist_THEN_fail() {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_COMPONENT_A);
        when(kernel.findServiceTopic(TEST_COMPONENT_A))
                .thenReturn(configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A));
        GetConfigurationRequest request = new GetConfigurationRequest();
        request.setComponentName(TEST_COMPONENT_A);
        request.setKeyPath(Collections.singletonList("WrongKey"));
        ResourceNotFoundError error = assertThrows(ResourceNotFoundError.class, () ->
                agent.getGetConfigurationHandler(mockContext).handleRequest(request));
        assertEquals("Key not found", error.getMessage());
    }

    @Test
    void GIVEN_get_config_request_WHEN_component_requested_does_not_exist_THEN_fail() {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_COMPONENT_A);
        GetConfigurationRequest request = new GetConfigurationRequest();
        request.setComponentName("WrongComponent");
        request.setKeyPath(Collections.singletonList("AnyKey"));
        ResourceNotFoundError error = assertThrows(ResourceNotFoundError.class, () ->
                agent.getGetConfigurationHandler(mockContext).handleRequest(request));
        assertEquals("Key not found", error.getMessage());
    }

    @Test
    void GIVEN_get_config_request_WHEN_component_requested_does_not_have_configuration_THEN_fail() {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_COMPONENT_A);
        when(kernel.findServiceTopic(TEST_COMPONENT_B))
                .thenReturn(configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_B));
        GetConfigurationRequest request = new GetConfigurationRequest();
        request.setComponentName(TEST_COMPONENT_B);
        request.setKeyPath(Collections.singletonList("AnyKey"));
        ResourceNotFoundError error = assertThrows(ResourceNotFoundError.class, () ->
                agent.getGetConfigurationHandler(mockContext).handleRequest(request));
        assertEquals("Key not found", error.getMessage());
    }

    @Test
    void GIVEN_agent_running_WHEN_update_config_request_THEN_update_config_value() {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_COMPONENT_A);
        when(kernel.findServiceTopic(TEST_COMPONENT_A))
                .thenReturn(configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A));
        UpdateConfigurationRequest request = new UpdateConfigurationRequest();
        request.setKeyPath(Collections.EMPTY_LIST);
        request.setValueToMerge(Collections.singletonMap(TEST_CONFIG_KEY_2, 30));
        request.setTimestamp(Instant.now());
        UpdateConfigurationResponse response = agent.getUpdateConfigurationHandler(mockContext).handleRequest(request);
        assertNotNull(response);
        assertEquals(30,
                kernel.findServiceTopic(TEST_COMPONENT_A).find(CONFIGURATION_CONFIG_KEY, TEST_CONFIG_KEY_2).getOnce());
    }

    @Test
    void GIVEN_agent_running_WHEN_update_config_request_for_ACL_THEN_update_fails() {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_COMPONENT_A);
        UpdateConfigurationRequest request = new UpdateConfigurationRequest();
        request.setKeyPath(Collections.EMPTY_LIST);
        request.setValueToMerge(Collections.singletonMap(ACCESS_CONTROL_NAMESPACE_TOPIC, Collections.singletonMap(
                "aws.greengrass.ipc.pubsub", "policy")));
        request.setTimestamp(Instant.now());
        Exception e = assertThrows(InvalidArgumentsError.class,
                () -> agent.getUpdateConfigurationHandler(mockContext).handleRequest(request));
        assertTrue(e.getMessage().contains("Config update is not allowed for following fields"));
    }

    @Test
    void GIVEN_agent_running_WHEN_update_config_request_for_ACL_nested_node_THEN_update_fails() {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_COMPONENT_A);
        UpdateConfigurationRequest request = new UpdateConfigurationRequest();
        request.setKeyPath(Arrays.asList(ACCESS_CONTROL_NAMESPACE_TOPIC));
        request.setValueToMerge(Collections.singletonMap("aws.greengrass.ipc.pubsub", "policy"));
        request.setTimestamp(Instant.now());
        Exception e = assertThrows(InvalidArgumentsError.class,
                () -> agent.getUpdateConfigurationHandler(mockContext).handleRequest(request));
        assertTrue(e.getMessage().contains("Config update is not allowed for following fields"));
    }

    @Test
    void GIVEN_update_config_request_WHEN_update_key_does_not_exist_THEN_create_key() {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_COMPONENT_A);
        when(kernel.findServiceTopic(TEST_COMPONENT_A))
                .thenReturn(configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A));
        UpdateConfigurationRequest request = new UpdateConfigurationRequest();
        request.setValueToMerge(Collections.singletonMap("NewKey", "SomeValue"));
        request.setTimestamp(Instant.now());
        UpdateConfigurationResponse response = agent.getUpdateConfigurationHandler(mockContext).handleRequest(request);
        assertNotNull(response);

        Topic newConfigKeyTopic = kernel.findServiceTopic(TEST_COMPONENT_A).find(CONFIGURATION_CONFIG_KEY, "NewKey");
        assertNotNull(newConfigKeyTopic);
        assertEquals("SomeValue", newConfigKeyTopic.getOnce());
    }

    @Test
    void GIVEN_update_config_request_WHEN_proposed_timestamp_is_stale_THEN_fail() {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_COMPONENT_A);
        Topics componentAConfiguration =
                configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A);
        when(kernel.findServiceTopic(TEST_COMPONENT_A)).thenReturn(componentAConfiguration);
        long actualModTime = componentAConfiguration.lookup(CONFIGURATION_CONFIG_KEY, TEST_CONFIG_KEY_1).getModtime();
        UpdateConfigurationRequest request = new UpdateConfigurationRequest();
        request.setKeyPath(Collections.EMPTY_LIST);
        request.setValueToMerge(Collections.singletonMap(TEST_CONFIG_KEY_1, 30));
        request.setTimestamp(Instant.ofEpochMilli(actualModTime - 10));
        FailedUpdateConditionCheckError error = assertThrows(FailedUpdateConditionCheckError.class, () ->
                agent.getUpdateConfigurationHandler(mockContext).handleRequest(request));
        assertEquals("Proposed timestamp is older than the config's latest modified timestamp",
                error.getMessage());
    }

    @Test
    void GIVEN_update_config_request_WHEN_requested_node_does_not_exist_THEN_update_is_successful() {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_COMPONENT_A);
        Topics componentAConfiguration =
                configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A);
        when(kernel.findServiceTopic(TEST_COMPONENT_A)).thenReturn(componentAConfiguration);

        UpdateConfigurationRequest request = new UpdateConfigurationRequest();
        request.setKeyPath(Arrays.asList("SomeContainerKey"));
        request.setValueToMerge(Collections.singletonMap("SomeLeafKey", "SomeOtherValue"));
        request.setTimestamp(Instant.now());
        agent.getUpdateConfigurationHandler(mockContext).handleRequest(request);
        Topics updateTopics = componentAConfiguration.findTopics(CONFIGURATION_CONFIG_KEY, "SomeContainerKey");
        assertNotNull(updateTopics);
        assertEquals("SomeOtherValue", Coerce.toString(((Topic)updateTopics.getChild("SomeLeafKey")).getOnce()));
    }

    @Test
    void GIVEN_update_config_request_WHEN_requested_node_is_container_THEN_update_is_successful() {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_COMPONENT_A);
        Topics componentAConfiguration =
                configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A);
        componentAConfiguration.lookup(CONFIGURATION_CONFIG_KEY, "SomeContainerKey", "SomeLeafKey")
                .withNewerValue(Instant.now().toEpochMilli(), "SomeValue");
        when(kernel.findServiceTopic(TEST_COMPONENT_A)).thenReturn(componentAConfiguration);

        UpdateConfigurationRequest request = new UpdateConfigurationRequest();
        request.setKeyPath(Arrays.asList("SomeContainerKey"));
        request.setValueToMerge(Collections.singletonMap("SomeLeafKey", "SomeOtherValue"));
        request.setTimestamp(Instant.now());
        agent.getUpdateConfigurationHandler(mockContext).handleRequest(request);
        Topics updateTopics = componentAConfiguration.findTopics(CONFIGURATION_CONFIG_KEY, "SomeContainerKey");
        assertNotNull(updateTopics);
        assertEquals("SomeOtherValue", Coerce.toString(((Topic)updateTopics.getChild("SomeLeafKey")).getOnce()));
    }

    @Test
    void GIVEN_update_config_request_WHEN_requested_node_is_leaf_THEN_update_to_convert_to_container_is_successful() {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_COMPONENT_A);
        Topics componentAConfiguration =
                configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A);
        componentAConfiguration.lookup(CONFIGURATION_CONFIG_KEY, "SomeContainerKey", "SomeLeafKey").withValue("SomeValue");
        when(kernel.findServiceTopic(TEST_COMPONENT_A)).thenReturn(componentAConfiguration);

        UpdateConfigurationRequest request = new UpdateConfigurationRequest();
        request.setKeyPath(Arrays.asList("SomeContainerKey"));
        request.setValueToMerge(Collections.singletonMap("SomeLeafKey", Collections.singletonMap("newKey",
                "SomeOtherValue")));
        request.setTimestamp(Instant.now());

        agent.getUpdateConfigurationHandler(mockContext).handleRequest(request);

        Topics updateTopics = componentAConfiguration.findTopics(CONFIGURATION_CONFIG_KEY, "SomeContainerKey");
        assertNotNull(updateTopics);
        Topics convertedNode = (Topics)updateTopics.getChild("SomeLeafKey");
        assertEquals("SomeOtherValue",
                Coerce.toString(((Topic)convertedNode.getChild("newKey")).getOnce()));
    }

    @Test
    void GIVEN_update_config_request_WHEN_requested_node_is_container_THEN_update_to_convert_to_leaf_is_successful() {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_COMPONENT_A);
        Topics componentAConfiguration =
                configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A);
        componentAConfiguration.lookup(CONFIGURATION_CONFIG_KEY, "SomeContainerKey", "SomeLeafKey").withValue("SomeValue");
        when(kernel.findServiceTopic(TEST_COMPONENT_A)).thenReturn(componentAConfiguration);

        UpdateConfigurationRequest request = new UpdateConfigurationRequest();
        request.setValueToMerge(Collections.singletonMap("SomeContainerKey", "SomeOtherValue"));
        request.setTimestamp(Instant.now());
        agent.getUpdateConfigurationHandler(mockContext).handleRequest(request);

        Topic updateTopic = componentAConfiguration.find(CONFIGURATION_CONFIG_KEY, "SomeContainerKey");
        assertNotNull(updateTopic);
        assertEquals("SomeOtherValue",
                Coerce.toString(updateTopic.getOnce()));
    }

    @Test
    void GIVEN_subscribe_to_config_update_request_WHEN_requests_all_config_THEN_child_update_triggers_event() {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_COMPONENT_B);
        when(kernel.findServiceTopic(TEST_COMPONENT_A))
                .thenReturn(configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A));
        when(mockServerConnectionContinuation.sendMessage(anyList(), byteArrayCaptor.capture(), any(MessageType.class), anyInt()))
                .thenReturn(new CompletableFuture<>());
        SubscribeToConfigurationUpdateRequest subscribe = new SubscribeToConfigurationUpdateRequest();
        subscribe.setComponentName(TEST_COMPONENT_A);
        SubscribeToConfigurationUpdateResponse response = agent.getConfigurationUpdateHandler(mockContext).handleRequest(subscribe);
        assertNotNull(response);

        configuration.getRoot()
                .lookup(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A, CONFIGURATION_CONFIG_KEY, TEST_CONFIG_KEY_1)
                .withValue(25);

        verify(mockServerConnectionContinuation, timeout(10000))
                .sendMessage(anyList(), any(), any(MessageType.class), anyInt());
        assertNotNull(byteArrayCaptor.getValue());
        ConfigurationUpdateEvents sentMessage = agent.getConfigurationUpdateHandler(mockContext)
                .getOperationModelContext().getServiceModel()
                .fromJson(ConfigurationUpdateEvents.class, byteArrayCaptor.getValue());
        sentMessage.selfDesignateSetUnionMember();
        assertNotNull(sentMessage.getConfigurationUpdateEvent());
        assertEquals(TEST_COMPONENT_A, sentMessage.getConfigurationUpdateEvent().getComponentName());
        assertThat(sentMessage.getConfigurationUpdateEvent().getKeyPath(),
                containsInAnyOrder(TEST_CONFIG_KEY_1));
    }

    @Test
    void GIVEN_subscribe_to_config_update_request_WHEN_requests_leaf_node_THEN_self_update_triggers_event() {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_COMPONENT_B);
        Topics componentAConfiguration =
                configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A);
        when(kernel.findServiceTopic(TEST_COMPONENT_A)).thenReturn(componentAConfiguration);
        when(mockServerConnectionContinuation.sendMessage(anyList(), byteArrayCaptor.capture(), any(MessageType.class), anyInt()))
                .thenReturn(new CompletableFuture<>());
        SubscribeToConfigurationUpdateRequest subscribe = new SubscribeToConfigurationUpdateRequest();
        subscribe.setComponentName(TEST_COMPONENT_A);
        subscribe.setKeyPath(Collections.singletonList(TEST_CONFIG_KEY_1));
        SubscribeToConfigurationUpdateResponse response = agent.getConfigurationUpdateHandler(mockContext).handleRequest(subscribe);
        assertNotNull(response);

        configuration.getRoot()
                .lookup(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A, CONFIGURATION_CONFIG_KEY, TEST_CONFIG_KEY_1)
                .withValue(25);

        verify(mockServerConnectionContinuation, timeout(10000))
                .sendMessage(anyList(), any(), any(MessageType.class), anyInt());
        assertNotNull(byteArrayCaptor.getValue());
        ConfigurationUpdateEvents sentMessage = agent.getConfigurationUpdateHandler(mockContext)
                .getOperationModelContext().getServiceModel()
                .fromJson(ConfigurationUpdateEvents.class, byteArrayCaptor.getValue());

        sentMessage.selfDesignateSetUnionMember();
        assertNotNull(sentMessage.getConfigurationUpdateEvent());
        assertEquals(TEST_COMPONENT_A, sentMessage.getConfigurationUpdateEvent().getComponentName());
        assertThat(sentMessage.getConfigurationUpdateEvent().getKeyPath(),
                containsInAnyOrder(TEST_CONFIG_KEY_1));
    }

    @Test
    void GIVEN_subscribe_to_config_update_request_WHEN_requests_container_node_THEN_next_child_update_triggers_event() {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_COMPONENT_B);
        Topics componentAConfiguration =
                configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A);
        componentAConfiguration.lookup(CONFIGURATION_CONFIG_KEY, "SomeContainerNode", "SomeLeafNode").withValue("SomeValue");
        configuration.context.waitForPublishQueueToClear();
        when(kernel.findServiceTopic(TEST_COMPONENT_A)).thenReturn(componentAConfiguration);
        when(mockServerConnectionContinuation.sendMessage(anyList(), byteArrayCaptor.capture(), any(MessageType.class), anyInt()))
                .thenReturn(new CompletableFuture<>());
        SubscribeToConfigurationUpdateRequest subscribe = new SubscribeToConfigurationUpdateRequest();
        subscribe.setComponentName(TEST_COMPONENT_A);
        subscribe.setKeyPath(Arrays.asList("SomeContainerNode", "SomeLeafNode"));
        SubscribeToConfigurationUpdateResponse response = agent.getConfigurationUpdateHandler(mockContext).handleRequest(subscribe);
        assertNotNull(response);

        configuration.getRoot()
                .lookup(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A, CONFIGURATION_CONFIG_KEY, "SomeContainerNode",
                        "SomeLeafNode").withValue("SomeNewValue");

        verify(mockServerConnectionContinuation, timeout(10000))
                .sendMessage(anyList(), any(), any(MessageType.class), anyInt());
        assertNotNull(byteArrayCaptor.getValue());
        ConfigurationUpdateEvents sentMessage = agent.getConfigurationUpdateHandler(mockContext)
                .getOperationModelContext().getServiceModel()
                .fromJson(ConfigurationUpdateEvents.class, byteArrayCaptor.getValue());

        sentMessage.selfDesignateSetUnionMember();
        assertNotNull(sentMessage.getConfigurationUpdateEvent());
        assertEquals(TEST_COMPONENT_A, sentMessage.getConfigurationUpdateEvent().getComponentName());
        assertThat(sentMessage.getConfigurationUpdateEvent().getKeyPath(),
                containsInAnyOrder("SomeContainerNode", "SomeLeafNode"));
    }

    @Test
    void GIVEN_subscribe_to_config_update_request_WHEN_requests_nested_container_node_THEN_child_update_triggers_event() {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_COMPONENT_B);
        Topics componentAConfiguration =
                configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A);
        componentAConfiguration
                .lookup(CONFIGURATION_CONFIG_KEY, "Level1ContainerNode", "Level2ContainerNode", "SomeLeafNode")
                .withValue("SomeValue");
        configuration.context.waitForPublishQueueToClear();
        when(kernel.findServiceTopic(TEST_COMPONENT_A)).thenReturn(componentAConfiguration);
        when(mockServerConnectionContinuation.sendMessage(anyList(), byteArrayCaptor.capture(), any(MessageType.class), anyInt()))
                .thenReturn(new CompletableFuture<>());
        SubscribeToConfigurationUpdateRequest subscribe = new SubscribeToConfigurationUpdateRequest();
        subscribe.setComponentName(TEST_COMPONENT_A);
        subscribe.setKeyPath(Arrays.asList("Level1ContainerNode", "Level2ContainerNode"));
        SubscribeToConfigurationUpdateResponse response = agent.getConfigurationUpdateHandler(mockContext).handleRequest(subscribe);
        assertNotNull(response);

        configuration.getRoot()
                .lookup(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A, CONFIGURATION_CONFIG_KEY, "Level1ContainerNode",
                        "Level2ContainerNode", "SomeLeafNode").withValue("SomeNewValue");

        verify(mockServerConnectionContinuation, timeout(10000))
                .sendMessage(anyList(), any(), any(MessageType.class), anyInt());
        assertNotNull(byteArrayCaptor.getValue());
        ConfigurationUpdateEvents sentMessage = agent.getConfigurationUpdateHandler(mockContext)
                .getOperationModelContext().getServiceModel()
                .fromJson(ConfigurationUpdateEvents.class, byteArrayCaptor.getValue());

        sentMessage.selfDesignateSetUnionMember();
        assertNotNull(sentMessage.getConfigurationUpdateEvent());
        assertEquals(TEST_COMPONENT_A, sentMessage.getConfigurationUpdateEvent().getComponentName());
        assertThat(sentMessage.getConfigurationUpdateEvent().getKeyPath(),
                containsInAnyOrder("Level1ContainerNode", "Level2ContainerNode", "SomeLeafNode"));
    }

    @Test
    void GIVEN_subscribe_to_config_update_request_WHEN_timestamp_changes_but_not_value_THEN_no_event_triggered() {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_COMPONENT_B);
        Topics componentAConfiguration =
                configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A);
        when(kernel.findServiceTopic(TEST_COMPONENT_A)).thenReturn(componentAConfiguration);
        SubscribeToConfigurationUpdateRequest subscribe = new SubscribeToConfigurationUpdateRequest();
        subscribe.setComponentName(TEST_COMPONENT_A);
        subscribe.setKeyPath(Collections.singletonList(TEST_CONFIG_KEY_1));
        SubscribeToConfigurationUpdateResponse response = agent.getConfigurationUpdateHandler(mockContext).handleRequest(subscribe);
        assertNotNull(response);

        // Add the same key-value to the parent but with a newer timestamp so timestampUpdated event for the topic
        // will be triggered
        long modTime = System.currentTimeMillis();
        Topics parent = configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A,
                CONFIGURATION_CONFIG_KEY);
        parent.updateFromMap(Collections.singletonMap(TEST_CONFIG_KEY_1, TEST_CONFIG_KEY_1_INITIAL_VALUE),
                        new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.MERGE, modTime));
        // Wait until config watchers finish processing changes, ipc subscription is a watcher too
        configuration.context.waitForPublishQueueToClear();
        // Mod time should be updated but event shouldn't be sent
        assertEquals(modTime, parent.find(TEST_CONFIG_KEY_1).getModtime());
        verify(mockServerConnectionContinuation, never())
                .sendMessage(anyList(), any(), any(MessageType.class), anyInt());
    }

    @Test
    void GIVEN_agent_running_WHEN_subscribe_to_validate_config_request_THEN_validation_event_can_be_triggered()
            throws Exception {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_COMPONENT_A);
        when(mockServerConnectionContinuation.sendMessage(anyList(), byteArrayCaptor.capture(), any(MessageType.class), anyInt()))
                .thenReturn(new CompletableFuture<>());
        SubscribeToValidateConfigurationUpdatesRequest request = new SubscribeToValidateConfigurationUpdatesRequest();
        SubscribeToValidateConfigurationUpdatesResponse response = agent.getValidateConfigurationUpdatesHandler(mockContext).handleRequest(request);
        assertNotNull(response);

        Map<String, Object> configToValidate = new HashMap<>();
        configToValidate.put(TEST_CONFIG_KEY_1, 0);
        configToValidate.put(TEST_CONFIG_KEY_2, 100);

        assertTrue(agent.validateConfiguration(TEST_COMPONENT_A, "A", configToValidate, new CompletableFuture<>()));
        verify(mockServerConnectionContinuation, timeout(10000))
                .sendMessage(anyList(), any(), any(MessageType.class), anyInt());

        ValidateConfigurationUpdateEvents events = agent.getValidateConfigurationUpdatesHandler(mockContext)
                .getOperationModelContext().getServiceModel()
                .fromJson(ValidateConfigurationUpdateEvents.class, byteArrayCaptor.getValue());

        events.selfDesignateSetUnionMember();
        assertNotNull(events.getValidateConfigurationUpdateEvent());
        assertNotNull(events.getValidateConfigurationUpdateEvent().getConfiguration());
        assertFalse(events.getValidateConfigurationUpdateEvent().getConfiguration().isEmpty());
        assertTrue(events.getValidateConfigurationUpdateEvent().getConfiguration().containsKey(TEST_CONFIG_KEY_1));
        assertTrue(events.getValidateConfigurationUpdateEvent().getConfiguration().containsKey(TEST_CONFIG_KEY_2));
        assertEquals(0.0, events.getValidateConfigurationUpdateEvent().getConfiguration().get(TEST_CONFIG_KEY_1));
        assertEquals(100.0, events.getValidateConfigurationUpdateEvent().getConfiguration().get(TEST_CONFIG_KEY_2));
    }

    @Test
    void GIVEN_waiting_for_validation_response_WHEN_abandon_validation_event_THEN_succeed() throws Exception {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_COMPONENT_A);
        when(mockServerConnectionContinuation.sendMessage(anyList(), byteArrayCaptor.capture(), any(MessageType.class), anyInt()))
                .thenReturn(new CompletableFuture<>());
        SubscribeToValidateConfigurationUpdatesRequest request = new SubscribeToValidateConfigurationUpdatesRequest();
        SubscribeToValidateConfigurationUpdatesResponse response = agent.getValidateConfigurationUpdatesHandler(mockContext).handleRequest(request);
        assertNotNull(response);

        Map<String, Object> configToValidate = new HashMap<>();
        configToValidate.put(TEST_CONFIG_KEY_1, 0);
        configToValidate.put(TEST_CONFIG_KEY_2, 100);

        CompletableFuture validationTracker = new CompletableFuture<>();
        assertTrue(agent.validateConfiguration(TEST_COMPONENT_A, "A", configToValidate, validationTracker));
        assertTrue(agent.discardValidationReportTracker("A", TEST_COMPONENT_A, validationTracker));
    }

    @Test
    void GIVEN_validation_event_being_tracked_WHEN_send_config_validity_report_request_THEN_notify_validation_requester()
            throws Exception {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_COMPONENT_A);
        when(mockServerConnectionContinuation.sendMessage(anyList(), byteArrayCaptor.capture(), any(MessageType.class), anyInt()))
                .thenReturn(new CompletableFuture<>());
        SubscribeToValidateConfigurationUpdatesRequest request = new SubscribeToValidateConfigurationUpdatesRequest();
        SubscribeToValidateConfigurationUpdatesResponse response = agent.getValidateConfigurationUpdatesHandler(mockContext).handleRequest(request);
        assertNotNull(response);

        Map<String, Object> configToValidate = new HashMap<>();
        configToValidate.put(TEST_CONFIG_KEY_1, 0);
        configToValidate.put(TEST_CONFIG_KEY_2, 100);

        CompletableFuture<ConfigurationValidityReport> validationTracker = new CompletableFuture<>();
        assertTrue(agent.validateConfiguration(TEST_COMPONENT_A, "A", configToValidate, validationTracker));

        SendConfigurationValidityReportRequest reportRequest = new SendConfigurationValidityReportRequest();
        ConfigurationValidityReport validityReport = new ConfigurationValidityReport();
        validityReport.setStatus(ConfigurationValidityStatus.ACCEPTED);
        validityReport.setDeploymentId("A");
        reportRequest.setConfigurationValidityReport(validityReport);
        SendConfigurationValidityReportResponse reportResponse =
                agent.getSendConfigurationValidityReportHandler(mockContext).handleRequest(reportRequest);
        assertNotNull(reportResponse);

        assertTrue(validationTracker.isDone());
        assertEquals(ConfigurationValidityStatus.ACCEPTED, validationTracker.get().getStatus());
    }

    @Test
    void GIVEN_no_validation_event_is_tracked_WHEN_send_config_validity_report_request_THEN_fail() {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_COMPONENT_A);
        SendConfigurationValidityReportRequest reportRequest = new SendConfigurationValidityReportRequest();
        ConfigurationValidityReport validityReport = new ConfigurationValidityReport();
        validityReport.setStatus(ConfigurationValidityStatus.ACCEPTED);
        validityReport.setDeploymentId("abc");
        reportRequest.setConfigurationValidityReport(validityReport);
        InvalidArgumentsError error = assertThrows(InvalidArgumentsError.class, () ->
                agent.getSendConfigurationValidityReportHandler(mockContext).handleRequest(reportRequest));
        assertEquals("Validation request either timed out or was never made", error.getMessage());
    }

    @Test
    void GIVEN_request_has_null_deployment_id_THEN_fail() {
        SendConfigurationValidityReportRequest reportRequest = new SendConfigurationValidityReportRequest();
        ConfigurationValidityReport validityReport = new ConfigurationValidityReport();
        reportRequest.setConfigurationValidityReport(validityReport);
        InvalidArgumentsError error = assertThrows(InvalidArgumentsError.class, () ->
                agent.getSendConfigurationValidityReportHandler(mockContext).handleRequest(reportRequest));
        assertEquals("Cannot accept configuration validity report, the deployment ID provided was null",
                error.getMessage());
    }
}
