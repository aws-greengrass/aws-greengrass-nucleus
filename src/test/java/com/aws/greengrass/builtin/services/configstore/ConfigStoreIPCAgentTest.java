/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.configstore;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.ipc.ConnectionContext;
import com.aws.greengrass.ipc.common.BuiltInServiceDestinationCode;
import com.aws.greengrass.ipc.common.ServiceEventHelper;
import com.aws.greengrass.ipc.services.configstore.ConfigStoreImpl;
import com.aws.greengrass.ipc.services.configstore.ConfigStoreResponseStatus;
import com.aws.greengrass.ipc.services.configstore.ConfigStoreServiceOpCodes;
import com.aws.greengrass.ipc.services.configstore.ConfigurationUpdateEvent;
import com.aws.greengrass.ipc.services.configstore.ConfigurationValidityReport;
import com.aws.greengrass.ipc.services.configstore.ConfigurationValidityStatus;
import com.aws.greengrass.ipc.services.configstore.GetConfigurationRequest;
import com.aws.greengrass.ipc.services.configstore.GetConfigurationResponse;
import com.aws.greengrass.ipc.services.configstore.SendConfigurationValidityReportRequest;
import com.aws.greengrass.ipc.services.configstore.SendConfigurationValidityReportResponse;
import com.aws.greengrass.ipc.services.configstore.SubscribeToConfigurationUpdateRequest;
import com.aws.greengrass.ipc.services.configstore.SubscribeToConfigurationUpdateResponse;
import com.aws.greengrass.ipc.services.configstore.UpdateConfigurationRequest;
import com.aws.greengrass.ipc.services.configstore.UpdateConfigurationResponse;
import com.aws.greengrass.ipc.services.configstore.ValidateConfigurationUpdateEvent;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.hamcrest.collection.IsMapContaining;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@ExtendWith({MockitoExtension.class, GGExtension.class})
class ConfigStoreIPCAgentTest {
    private static final String TEST_COMPONENT_A = "Component_A";
    private static final String TEST_COMPONENT_B = "Component_B";
    private static final String TEST_CONFIG_KEY_1 = "temperature";
    private static final String TEST_CONFIG_KEY_2 = "humidity";
    private static final int TEST_CONFIG_KEY_1_INITIAL_VALUE = 20;
    private static final int TEST_CONFIG_KEY_2_INITIAL_VALUE = 15;

    @Mock
    private Kernel kernel;

    @Mock
    private ServiceEventHelper serviceEventHelper;

    @Mock
    private ConnectionContext componentAContext;

    @Mock
    private ConnectionContext componentBContext;

    private ConfigStoreIPCAgent agent;

    private Configuration configuration;

    @BeforeEach
    void setup() {
        agent = new ConfigStoreIPCAgent(kernel, serviceEventHelper);

        configuration = new Configuration(new Context());
        Topics root = configuration.getRoot();
        root.lookup(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A, PARAMETERS_CONFIG_KEY, TEST_CONFIG_KEY_1)
                .withNewerValue(100, TEST_CONFIG_KEY_1_INITIAL_VALUE);
        root.lookup(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A, PARAMETERS_CONFIG_KEY, TEST_CONFIG_KEY_2)
                .withNewerValue(100, TEST_CONFIG_KEY_2_INITIAL_VALUE);
        root.lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_B);
        configuration.context.waitForPublishQueueToClear();
        lenient().when(kernel.getConfig()).thenReturn(configuration);

        lenient().when(componentAContext.getServiceName()).thenReturn(TEST_COMPONENT_A);
        lenient().when(componentBContext.getServiceName()).thenReturn(TEST_COMPONENT_B);
    }

    @AfterEach
    void cleanup() throws IOException {
        kernel.getConfig().context.close();
    }

    @Test
    void GIVEN_agent_running_WHEN_get_config_request_for_own_config_THEN_return_config_value() {
        when(kernel.findServiceTopic(TEST_COMPONENT_A))
                .thenReturn(configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A));
        GetConfigurationRequest request = GetConfigurationRequest.builder().componentName(TEST_COMPONENT_A)
                .keyPath(Collections.singletonList(TEST_CONFIG_KEY_1)).build();
        GetConfigurationResponse response = agent.getConfig(request, componentAContext);
        assertEquals(ConfigStoreResponseStatus.Success, response.getStatus());
        assertEquals(20, response.getValue());
    }

    @Test
    void GIVEN_agent_running_WHEN_get_config_request_for_cross_component_config_THEN_return_config_value() {
        when(kernel.findServiceTopic(TEST_COMPONENT_A))
                .thenReturn(configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A));
        GetConfigurationRequest request = GetConfigurationRequest.builder().componentName(TEST_COMPONENT_A)
                .keyPath(Collections.singletonList(TEST_CONFIG_KEY_2)).build();
        GetConfigurationResponse response = agent.getConfig(request, componentBContext);
        assertEquals(ConfigStoreResponseStatus.Success, response.getStatus());
        assertEquals(15, response.getValue());
    }

    @Test
    void GIVEN_agent_running_WHEN_get_config_request_for_nested_leaf_key_THEN_return_value() {
        Topics componentAConfiguration =
                configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A);
        componentAConfiguration.lookup(PARAMETERS_CONFIG_KEY, "SomeContainerKey", "SomeLeafKey").withValue("SomeValue");
        when(kernel.findServiceTopic(TEST_COMPONENT_A)).thenReturn(componentAConfiguration);
        GetConfigurationRequest request = GetConfigurationRequest.builder().componentName(TEST_COMPONENT_A)
                .keyPath(Arrays.asList("SomeContainerKey", "SomeLeafKey")).build();
        GetConfigurationResponse response = agent.getConfig(request, componentAContext);
        assertEquals(ConfigStoreResponseStatus.Success, response.getStatus());
        assertEquals("SomeValue", response.getValue());
    }

    @Test
    void GIVEN_agent_running_WHEN_get_config_request_for_container_node_THEN_return_subtree_as_pojo() {
        Topics componentAConfiguration =
                configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A);
        componentAConfiguration.lookup(PARAMETERS_CONFIG_KEY, "SomeContainerKey", "SomeLeafKey").withValue("SomeValue");
        when(kernel.findServiceTopic(TEST_COMPONENT_A)).thenReturn(componentAConfiguration);
        GetConfigurationRequest request = GetConfigurationRequest.builder().componentName(TEST_COMPONENT_A)
                .keyPath(Collections.singletonList("SomeContainerKey")).build();
        GetConfigurationResponse response = agent.getConfig(request, componentAContext);
        assertEquals(ConfigStoreResponseStatus.Success, response.getStatus());
        assertTrue(response.getValue() instanceof Map);
        Map<String, String> value = (Map) response.getValue();
        assertThat(value, IsMapContaining.hasEntry("SomeLeafKey", "SomeValue"));
    }

    @Test
    void GIVEN_agent_running_WHEN_get_config_request_for_nested_container_node_THEN_return_subtree_as_pojo() {
        Topics componentAConfiguration =
                configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A);
        componentAConfiguration.lookup(PARAMETERS_CONFIG_KEY, "Level1ContainerKey", "Level2ContainerKey", "SomeLeafKey")
                .withValue("SomeValue");
        when(kernel.findServiceTopic(TEST_COMPONENT_A)).thenReturn(componentAConfiguration);
        GetConfigurationRequest request = GetConfigurationRequest.builder().componentName(TEST_COMPONENT_A)
                .keyPath(Arrays.asList("Level1ContainerKey", "Level2ContainerKey")).build();
        GetConfigurationResponse response = agent.getConfig(request, componentAContext);
        assertEquals(ConfigStoreResponseStatus.Success, response.getStatus());
        assertTrue(response.getValue() instanceof Map);
        Map<String, String> value = (Map) response.getValue();
        assertThat(value, IsMapContaining.hasEntry("SomeLeafKey", "SomeValue"));
    }

    @Test
    void GIVEN_get_config_request_WHEN_key_does_not_exist_THEN_fail() {
        when(kernel.findServiceTopic(TEST_COMPONENT_A))
                .thenReturn(configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A));
        GetConfigurationRequest request = GetConfigurationRequest.builder().componentName(TEST_COMPONENT_A)
                .keyPath(Collections.singletonList("WrongKey")).build();
        GetConfigurationResponse response = agent.getConfig(request, componentAContext);
        assertEquals(ConfigStoreResponseStatus.ResourceNotFoundError, response.getStatus());
        assertEquals("Key not found", response.getErrorMessage());
    }

    @Test
    void GIVEN_get_config_request_WHEN_component_requested_does_not_exist_THEN_fail() {
        GetConfigurationRequest request = GetConfigurationRequest.builder().componentName("WrongComponent")
                .keyPath(Collections.singletonList("AnyKey")).build();
        GetConfigurationResponse response = agent.getConfig(request, componentAContext);
        assertEquals(ConfigStoreResponseStatus.ResourceNotFoundError, response.getStatus());
        assertEquals("Key not found", response.getErrorMessage());
    }

    @Test
    void GIVEN_get_config_request_WHEN_component_requested_does_not_have_configuration_THEN_fail() {
        when(kernel.findServiceTopic(TEST_COMPONENT_B))
                .thenReturn(configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_B));
        GetConfigurationRequest request = GetConfigurationRequest.builder().componentName(TEST_COMPONENT_B)
                .keyPath(Collections.singletonList("AnyKey")).build();
        GetConfigurationResponse response = agent.getConfig(request, componentAContext);
        assertEquals(ConfigStoreResponseStatus.ResourceNotFoundError, response.getStatus());
        assertEquals("Key not found", response.getErrorMessage());
    }

    @Test
    void GIVEN_agent_running_WHEN_update_config_request_THEN_update_config_value() {
        when(kernel.findServiceTopic(TEST_COMPONENT_A))
                .thenReturn(configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A));
        UpdateConfigurationRequest request = UpdateConfigurationRequest.builder().componentName(TEST_COMPONENT_A)
                .keyPath(Collections.singletonList(TEST_CONFIG_KEY_2)).newValue(30)
                .timestamp(System.currentTimeMillis()).build();
        UpdateConfigurationResponse response = agent.updateConfig(request, componentAContext);
        assertEquals(ConfigStoreResponseStatus.Success, response.getStatus());
        assertEquals(30,
                kernel.findServiceTopic(TEST_COMPONENT_A).find(PARAMETERS_CONFIG_KEY, TEST_CONFIG_KEY_2).getOnce());
    }

    @Test
    void GIVEN_update_config_request_WHEN_update_key_does_not_exist_THEN_create_key() {
        when(kernel.findServiceTopic(TEST_COMPONENT_A))
                .thenReturn(configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A));
        UpdateConfigurationRequest request = UpdateConfigurationRequest.builder().componentName(TEST_COMPONENT_A)
                .keyPath(Collections.singletonList("NewKey")).newValue("SomeValue").currentValue("")
                .timestamp(System.currentTimeMillis()).build();
        UpdateConfigurationResponse response = agent.updateConfig(request, componentAContext);
        Topic newConfigKeyTopic = kernel.findServiceTopic(TEST_COMPONENT_A).find(PARAMETERS_CONFIG_KEY, "NewKey");
        assertEquals(ConfigStoreResponseStatus.Success, response.getStatus());
        assertNotNull(newConfigKeyTopic);
        assertEquals("SomeValue", newConfigKeyTopic.getOnce());
    }

    @Test
    void GIVEN_update_config_request_WHEN_current_value_in_request_matches_current_value_for_node_THEN_update() {
        when(kernel.findServiceTopic(TEST_COMPONENT_A))
                .thenReturn(configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A));
        UpdateConfigurationRequest request = UpdateConfigurationRequest.builder().componentName(TEST_COMPONENT_A)
                .keyPath(Collections.singletonList(TEST_CONFIG_KEY_1)).newValue(30)
                .timestamp(System.currentTimeMillis()).currentValue(TEST_CONFIG_KEY_1_INITIAL_VALUE).build();
        UpdateConfigurationResponse response = agent.updateConfig(request, componentAContext);
        assertEquals(ConfigStoreResponseStatus.Success, response.getStatus());
        assertEquals(30,
                kernel.findServiceTopic(TEST_COMPONENT_A).find(PARAMETERS_CONFIG_KEY, TEST_CONFIG_KEY_1).getOnce());
    }

    @Test
    void GIVEN_update_config_request_WHEN_current_value_in_request_does_not_match_current_value_for_node_THEN_fail() {
        when(kernel.findServiceTopic(TEST_COMPONENT_A))
                .thenReturn(configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A));
        UpdateConfigurationRequest request = UpdateConfigurationRequest.builder().componentName(TEST_COMPONENT_A)
                .keyPath(Collections.singletonList(TEST_CONFIG_KEY_1)).newValue(30)
                .timestamp(System.currentTimeMillis()).currentValue(100).build();
        UpdateConfigurationResponse response = agent.updateConfig(request, componentAContext);
        assertEquals(ConfigStoreResponseStatus.FailedUpdateConditionCheck, response.getStatus());
        assertEquals("Current value for config is different from the current value needed for the update",
                response.getErrorMessage());
    }

    @Test
    void GIVEN_update_config_request_WHEN_proposed_timestamp_is_stale_THEN_fail() {
        Topics componentAConfiguration =
                configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A);
        when(kernel.findServiceTopic(TEST_COMPONENT_A)).thenReturn(componentAConfiguration);
        long actualModTime = componentAConfiguration.lookup(PARAMETERS_CONFIG_KEY, TEST_CONFIG_KEY_1).getModtime();
        UpdateConfigurationRequest request = UpdateConfigurationRequest.builder().componentName(TEST_COMPONENT_A)
                .keyPath(Collections.singletonList(TEST_CONFIG_KEY_1)).newValue(30).timestamp(actualModTime - 10).build();
        UpdateConfigurationResponse response = agent.updateConfig(request, componentAContext);
        assertEquals(ConfigStoreResponseStatus.FailedUpdateConditionCheck, response.getStatus());
        assertEquals("Proposed timestamp is older than the config's latest modified timestamp",
                response.getErrorMessage());
    }

    @Test
    void GIVEN_update_config_request_WHEN_requested_node_is_container_THEN_fail() {
        Topics componentAConfiguration =
                configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A);
        componentAConfiguration.lookup(PARAMETERS_CONFIG_KEY, "SomeContainerKey", "SomeLeafKey").withValue("SomeValue");
        when(kernel.findServiceTopic(TEST_COMPONENT_A)).thenReturn(componentAConfiguration);
        UpdateConfigurationRequest request = UpdateConfigurationRequest.builder().componentName(TEST_COMPONENT_A)
                .keyPath(Collections.singletonList("SomeContainerKey")).newValue("SomeOtherValue")
                .timestamp(System.currentTimeMillis()).build();
        UpdateConfigurationResponse response = agent.updateConfig(request, componentAContext);
        assertEquals(ConfigStoreResponseStatus.InvalidRequest, response.getStatus());
        assertEquals("Cannot update a non-leaf config node", response.getErrorMessage());
    }

    @Test
    void GIVEN_update_config_request_WHEN_requested_component_is_not_self_THEN_fail() {
        UpdateConfigurationRequest request = UpdateConfigurationRequest.builder().componentName(TEST_COMPONENT_A)
                .keyPath(Collections.singletonList(TEST_CONFIG_KEY_1)).newValue(20)
                .timestamp(System.currentTimeMillis()).build();
        UpdateConfigurationResponse response = agent.updateConfig(request, componentBContext);
        assertEquals(ConfigStoreResponseStatus.InvalidRequest, response.getStatus());
        assertEquals("Cross component updates are not allowed", response.getErrorMessage());
    }

    @Test
    void GIVEN_subscribe_to_config_update_request_WHEN_requests_all_config_THEN_child_update_triggers_event()
            throws Exception {
        when(kernel.findServiceTopic(TEST_COMPONENT_A))
                .thenReturn(configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A));
        SubscribeToConfigurationUpdateRequest request =
                SubscribeToConfigurationUpdateRequest.builder().componentName(TEST_COMPONENT_A).build();
        SubscribeToConfigurationUpdateResponse response = agent.subscribeToConfigUpdate(request, componentBContext);
        assertEquals(ConfigStoreResponseStatus.Success, response.getStatus());

        ConfigurationUpdateEvent updateEvent = ConfigurationUpdateEvent.builder().componentName(TEST_COMPONENT_A)
                .changedKeyPath(Collections.singletonList(TEST_CONFIG_KEY_1)).build();
        CountDownLatch eventSentToClient = new CountDownLatch(1);
        when(serviceEventHelper.sendServiceEvent(any(ConnectionContext.class), any(ConfigurationUpdateEvent.class),
                any(BuiltInServiceDestinationCode.class), anyInt(), anyInt())).thenAnswer(invocationOnMock -> {
            eventSentToClient.countDown();
            return null;
        });

        configuration.getRoot()
                .lookup(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A, PARAMETERS_CONFIG_KEY, TEST_CONFIG_KEY_1)
                .withValue(25);

        assertTrue(eventSentToClient.await(10, TimeUnit.SECONDS));
        verify(serviceEventHelper)
                .sendServiceEvent(componentBContext, updateEvent, BuiltInServiceDestinationCode.CONFIG_STORE,
                        ConfigStoreServiceOpCodes.KEY_CHANGED.ordinal(), ConfigStoreImpl.API_VERSION);
    }

    @Test
    void GIVEN_subscribe_to_config_update_request_WHEN_requests_leaf_node_THEN_self_update_triggers_event()
            throws Exception {
        Topics componentAConfiguration =
                configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A);
        when(kernel.findServiceTopic(TEST_COMPONENT_A)).thenReturn(componentAConfiguration);
        SubscribeToConfigurationUpdateRequest request =
                SubscribeToConfigurationUpdateRequest.builder().componentName(TEST_COMPONENT_A)
                        .keyPath(Collections.singletonList(TEST_CONFIG_KEY_1)).build();
        SubscribeToConfigurationUpdateResponse response = agent.subscribeToConfigUpdate(request, componentBContext);
        assertEquals(ConfigStoreResponseStatus.Success, response.getStatus());

        ConfigurationUpdateEvent updateEvent = ConfigurationUpdateEvent.builder().componentName(TEST_COMPONENT_A)
                .changedKeyPath(Collections.singletonList(TEST_CONFIG_KEY_1)).build();
        CountDownLatch eventSentToClient = new CountDownLatch(1);
        when(serviceEventHelper.sendServiceEvent(any(ConnectionContext.class), any(ConfigurationUpdateEvent.class),
                any(BuiltInServiceDestinationCode.class), anyInt(), anyInt())).thenAnswer(invocationOnMock -> {
            eventSentToClient.countDown();
            return null;
        });

        configuration.getRoot()
                .lookup(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A, PARAMETERS_CONFIG_KEY, TEST_CONFIG_KEY_1)
                .withValue(25);

        assertTrue(eventSentToClient.await(10, TimeUnit.SECONDS));
        verify(serviceEventHelper)
                .sendServiceEvent(componentBContext, updateEvent, BuiltInServiceDestinationCode.CONFIG_STORE,
                        ConfigStoreServiceOpCodes.KEY_CHANGED.ordinal(), ConfigStoreImpl.API_VERSION);
    }

    @Test
    void GIVEN_subscribe_to_config_update_request_WHEN_requests_container_node_THEN_next_child_update_triggers_event()
            throws Exception {
        Topics componentAConfiguration =
                configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A);
        componentAConfiguration.lookup(PARAMETERS_CONFIG_KEY, "SomeContainerNode", "SomeLeafNode").withValue("SomeValue");
        configuration.context.waitForPublishQueueToClear();
        when(kernel.findServiceTopic(TEST_COMPONENT_A)).thenReturn(componentAConfiguration);
        SubscribeToConfigurationUpdateRequest request =
                SubscribeToConfigurationUpdateRequest.builder().componentName(TEST_COMPONENT_A)
                        .keyPath(Collections.singletonList("SomeContainerNode")).build();
        SubscribeToConfigurationUpdateResponse response = agent.subscribeToConfigUpdate(request, componentBContext);
        assertEquals(ConfigStoreResponseStatus.Success, response.getStatus());

        ConfigurationUpdateEvent updateEvent = ConfigurationUpdateEvent.builder().componentName(TEST_COMPONENT_A)
                .changedKeyPath(Arrays.asList("SomeContainerNode", "SomeLeafNode")).build();
        CountDownLatch eventSentToClient = new CountDownLatch(1);
        when(serviceEventHelper.sendServiceEvent(any(ConnectionContext.class), any(ConfigurationUpdateEvent.class),
                any(BuiltInServiceDestinationCode.class), anyInt(), anyInt())).thenAnswer(invocationOnMock -> {
            eventSentToClient.countDown();
            return null;
        });

        configuration.getRoot()
                .lookup(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A, PARAMETERS_CONFIG_KEY, "SomeContainerNode",
                        "SomeLeafNode").withValue("SomeNewValue");

        assertTrue(eventSentToClient.await(10, TimeUnit.SECONDS));
        verify(serviceEventHelper)
                .sendServiceEvent(componentBContext, updateEvent, BuiltInServiceDestinationCode.CONFIG_STORE,
                        ConfigStoreServiceOpCodes.KEY_CHANGED.ordinal(), ConfigStoreImpl.API_VERSION);
    }

    @Test
    void GIVEN_subscribe_to_config_update_request_WHEN_requests_nested_leaf_node_THEN_self_update_triggers_event()
            throws Exception {
        Topics componentAConfiguration =
                configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A);
        componentAConfiguration.lookup(PARAMETERS_CONFIG_KEY, "SomeContainerNode", "SomeLeafNode").withValue("SomeValue");
        configuration.context.waitForPublishQueueToClear();
        when(kernel.findServiceTopic(TEST_COMPONENT_A)).thenReturn(componentAConfiguration);
        SubscribeToConfigurationUpdateRequest request =
                SubscribeToConfigurationUpdateRequest.builder().componentName(TEST_COMPONENT_A)
                        .keyPath(Arrays.asList("SomeContainerNode", "SomeLeafNode")).build();
        SubscribeToConfigurationUpdateResponse response = agent.subscribeToConfigUpdate(request, componentBContext);
        assertEquals(ConfigStoreResponseStatus.Success, response.getStatus());

        ConfigurationUpdateEvent updateEvent = ConfigurationUpdateEvent.builder().componentName(TEST_COMPONENT_A)
                .changedKeyPath(Arrays.asList("SomeContainerNode", "SomeLeafNode")).build();
        CountDownLatch eventSentToClient = new CountDownLatch(1);
        when(serviceEventHelper.sendServiceEvent(any(ConnectionContext.class), any(ConfigurationUpdateEvent.class),
                any(BuiltInServiceDestinationCode.class), anyInt(), anyInt())).thenAnswer(invocationOnMock -> {
            eventSentToClient.countDown();
            return null;
        });

        configuration.getRoot()
                .lookup(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A, PARAMETERS_CONFIG_KEY, "SomeContainerNode",
                        "SomeLeafNode").withValue("SomeNewValue");

        assertTrue(eventSentToClient.await(10, TimeUnit.SECONDS));
        verify(serviceEventHelper)
                .sendServiceEvent(componentBContext, updateEvent, BuiltInServiceDestinationCode.CONFIG_STORE,
                        ConfigStoreServiceOpCodes.KEY_CHANGED.ordinal(), ConfigStoreImpl.API_VERSION);
    }

    @Test
    void GIVEN_subscribe_to_config_update_request_WHEN_requests_nested_container_node_THEN_child_update_triggers_event()
            throws Exception {
        Topics componentAConfiguration =
                configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A);
        componentAConfiguration
                .lookup(PARAMETERS_CONFIG_KEY, "Level1ContainerNode", "Level2ContainerNode", "SomeLeafNode")
                .withValue("SomeValue");
        configuration.context.waitForPublishQueueToClear();
        when(kernel.findServiceTopic(TEST_COMPONENT_A)).thenReturn(componentAConfiguration);
        SubscribeToConfigurationUpdateRequest request =
                SubscribeToConfigurationUpdateRequest.builder().componentName(TEST_COMPONENT_A)
                        .keyPath(Arrays.asList("Level1ContainerNode", "Level2ContainerNode")).build();
        SubscribeToConfigurationUpdateResponse response = agent.subscribeToConfigUpdate(request, componentBContext);
        assertEquals(ConfigStoreResponseStatus.Success, response.getStatus());

        ConfigurationUpdateEvent updateEvent = ConfigurationUpdateEvent.builder().componentName(TEST_COMPONENT_A)
                .changedKeyPath(Arrays.asList("Level1ContainerNode", "Level2ContainerNode", "SomeLeafNode")).build();
        CountDownLatch eventSentToClient = new CountDownLatch(1);
        when(serviceEventHelper.sendServiceEvent(any(ConnectionContext.class), any(ConfigurationUpdateEvent.class),
                any(BuiltInServiceDestinationCode.class), anyInt(), anyInt())).thenAnswer(invocationOnMock -> {
            eventSentToClient.countDown();
            return null;
        });

        configuration.getRoot()
                .lookup(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A, PARAMETERS_CONFIG_KEY, "Level1ContainerNode",
                        "Level2ContainerNode", "SomeLeafNode").withValue("SomeNewValue");

        assertTrue(eventSentToClient.await(10, TimeUnit.SECONDS));
        verify(serviceEventHelper)
                .sendServiceEvent(componentBContext, updateEvent, BuiltInServiceDestinationCode.CONFIG_STORE,
                        ConfigStoreServiceOpCodes.KEY_CHANGED.ordinal(), ConfigStoreImpl.API_VERSION);
    }

    @Test
    void GIVEN_agent_running_WHEN_subscribe_to_validate_config_request_THEN_validation_event_can_be_triggered()
            throws Exception {
        assertEquals(ConfigStoreResponseStatus.Success,
                agent.subscribeToConfigValidation(componentAContext).getStatus());

        Map<String, Object> configToValidate = new HashMap<>();
        configToValidate.put(TEST_CONFIG_KEY_1, 0);
        configToValidate.put(TEST_CONFIG_KEY_2, 100);

        assertTrue(agent.validateConfiguration(TEST_COMPONENT_A, configToValidate, new CompletableFuture<>()));
        verify(serviceEventHelper).sendServiceEvent(componentAContext,
                ValidateConfigurationUpdateEvent.builder().configuration(configToValidate).build(),
                BuiltInServiceDestinationCode.CONFIG_STORE, ConfigStoreServiceOpCodes.VALIDATION_EVENT.ordinal(),
                ConfigStoreImpl.API_VERSION);
    }

    @Test
    void GIVEN_waiting_for_validation_response_WHEN_abandon_validation_event_THEN_succeed() throws Exception {
        assertEquals(ConfigStoreResponseStatus.Success,
                agent.subscribeToConfigValidation(componentAContext).getStatus());

        Map<String, Object> configToValidate = new HashMap<>();
        configToValidate.put(TEST_CONFIG_KEY_1, 0);
        configToValidate.put(TEST_CONFIG_KEY_2, 100);

        CompletableFuture validationTracker = new CompletableFuture<>();
        assertTrue(agent.validateConfiguration(TEST_COMPONENT_A, configToValidate, validationTracker));

        assertTrue(agent.discardValidationReportTracker(TEST_COMPONENT_A, validationTracker));
    }

    @Test
    void GIVEN_validation_event_being_tracked_WHEN_send_config_validity_report_request_THEN_notify_validation_requester()
            throws Exception {
        assertEquals(ConfigStoreResponseStatus.Success,
                agent.subscribeToConfigValidation(componentAContext).getStatus());

        Map<String, Object> configToValidate = new HashMap<>();
        configToValidate.put(TEST_CONFIG_KEY_1, 0);
        configToValidate.put(TEST_CONFIG_KEY_2, 100);

        CompletableFuture<ConfigurationValidityReport> validationTracker = new CompletableFuture<>();
        assertTrue(agent.validateConfiguration(TEST_COMPONENT_A, configToValidate, validationTracker));

        SendConfigurationValidityReportRequest reportRequest = SendConfigurationValidityReportRequest.builder()
                .configurationValidityReport(
                        ConfigurationValidityReport.builder().status(ConfigurationValidityStatus.VALID).build())
                .build();
        assertEquals(ConfigStoreResponseStatus.Success,
                agent.handleConfigValidityReport(reportRequest, componentAContext).getStatus());

        assertTrue(validationTracker.isDone());
        assertEquals(ConfigurationValidityStatus.VALID, validationTracker.get().getStatus());
    }

    @Test
    void GIVEN_no_validation_event_is_tracked_WHEN_send_config_validity_report_request_THEN_fail() {
        SendConfigurationValidityReportRequest request = SendConfigurationValidityReportRequest.builder()
                .configurationValidityReport(
                        ConfigurationValidityReport.builder().status(ConfigurationValidityStatus.VALID).build())
                .build();
        SendConfigurationValidityReportResponse response = agent.handleConfigValidityReport(request, componentAContext);
        assertEquals(ConfigStoreResponseStatus.InvalidRequest, response.getStatus());
        assertEquals("Validation request either timed out or was never made", response.getErrorMessage());
    }
}
