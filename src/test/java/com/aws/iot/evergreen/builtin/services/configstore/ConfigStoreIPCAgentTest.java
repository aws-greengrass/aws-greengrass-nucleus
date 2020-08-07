/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.builtin.services.configstore;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.ipc.ConnectionContext;
import com.aws.iot.evergreen.ipc.common.BuiltInServiceDestinationCode;
import com.aws.iot.evergreen.ipc.common.FrameReader;
import com.aws.iot.evergreen.ipc.services.common.ApplicationMessage;
import com.aws.iot.evergreen.ipc.services.common.IPCUtil;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStoreResponseStatus;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStoreServiceOpCodes;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigurationUpdateEvent;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigurationValidityStatus;
import com.aws.iot.evergreen.ipc.services.configstore.GetConfigurationRequest;
import com.aws.iot.evergreen.ipc.services.configstore.GetConfigurationResponse;
import com.aws.iot.evergreen.ipc.services.configstore.SendConfigurationValidityReportRequest;
import com.aws.iot.evergreen.ipc.services.configstore.SendConfigurationValidityReportResponse;
import com.aws.iot.evergreen.ipc.services.configstore.SubscribeToConfigurationUpdateRequest;
import com.aws.iot.evergreen.ipc.services.configstore.SubscribeToConfigurationUpdateResponse;
import com.aws.iot.evergreen.ipc.services.configstore.UpdateConfigurationRequest;
import com.aws.iot.evergreen.ipc.services.configstore.UpdateConfigurationResponse;
import com.aws.iot.evergreen.ipc.services.configstore.ValidateConfigurationUpdateEvent;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import org.hamcrest.collection.IsMapContaining;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@ExtendWith({MockitoExtension.class, EGExtension.class})
public class ConfigStoreIPCAgentTest {
    private static final String TEST_COMPONENT_A = "Component_A";
    private static final String TEST_COMPONENT_B = "Component_B";
    private static final String TEST_CONFIG_KEY_1 = "temperature";
    private static final String TEST_CONFIG_KEY_2 = "humidity";

    @Mock
    private Kernel kernel;

    @Mock
    private ExecutorService executor;

    @Mock
    private ConnectionContext componentAContext;

    @Mock
    private ConnectionContext componentBContext;

    private ConfigStoreIPCAgent agent;

    private Configuration configuration;

    @BeforeEach
    void setup() {
        agent = new ConfigStoreIPCAgent(kernel, executor);

        configuration = new Configuration(new Context());
        Topics root = configuration.getRoot();
        root.lookup(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A, PARAMETERS_CONFIG_KEY, TEST_CONFIG_KEY_1).withValue(20);
        root.lookup(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A, PARAMETERS_CONFIG_KEY, TEST_CONFIG_KEY_2).withValue(15);
        root.lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_B);
        when(kernel.getConfig()).thenReturn(configuration);

        lenient().when(componentAContext.getServiceName()).thenReturn(TEST_COMPONENT_A);
        lenient().when(componentBContext.getServiceName()).thenReturn(TEST_COMPONENT_B);

        agent.postInject();
    }

    @Test
    public void GIVEN_agent_running_WHEN_get_config_request_for_own_config_THEN_return_config_value() {
        when(kernel.findServiceTopic(TEST_COMPONENT_A))
                .thenReturn(configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A));
        GetConfigurationRequest request =
                GetConfigurationRequest.builder().componentName(TEST_COMPONENT_A).key(TEST_CONFIG_KEY_1).build();
        GetConfigurationResponse response = agent.getConfig(request, componentAContext);
        assertEquals(ConfigStoreResponseStatus.Success, response.getStatus());
        assertEquals(20, response.getValue());
    }

    @Test
    public void GIVEN_agent_running_WHEN_get_config_request_for_cross_component_config_THEN_return_config_value() {
        when(kernel.findServiceTopic(TEST_COMPONENT_A))
                .thenReturn(configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A));
        GetConfigurationRequest request =
                GetConfigurationRequest.builder().componentName(TEST_COMPONENT_A).key(TEST_CONFIG_KEY_2).build();
        GetConfigurationResponse response = agent.getConfig(request, componentBContext);
        assertEquals(ConfigStoreResponseStatus.Success, response.getStatus());
        assertEquals(15, response.getValue());
    }

    @Test
    public void GIVEN_get_config_request_WHEN_key_does_not_exist_THEN_return_config_value() {
        when(kernel.findServiceTopic(TEST_COMPONENT_A))
                .thenReturn(configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A));
        GetConfigurationRequest request =
                GetConfigurationRequest.builder().componentName(TEST_COMPONENT_A).key("WrongKey").build();
        GetConfigurationResponse response = agent.getConfig(request, componentAContext);
        assertEquals(ConfigStoreResponseStatus.ResourceNotFoundError, response.getStatus());
        assertEquals("Key not found", response.getErrorMessage());
    }

    @Test
    public void GIVEN_get_config_request_WHEN_component_requested_does_not_exist_THEN_fail() {
        GetConfigurationRequest request =
                GetConfigurationRequest.builder().componentName("WrongComponent").key("AnyKey").build();
        GetConfigurationResponse response = agent.getConfig(request, componentAContext);
        assertEquals(ConfigStoreResponseStatus.ResourceNotFoundError, response.getStatus());
        assertEquals("Service not found", response.getErrorMessage());
    }

    @Test
    public void GIVEN_get_config_request_WHEN_component_requested_does_not_have_configuration_THEN_fail() {
        when(kernel.findServiceTopic(TEST_COMPONENT_B))
                .thenReturn(configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_B));
        GetConfigurationRequest request =
                GetConfigurationRequest.builder().componentName(TEST_COMPONENT_B).key("AnyKey").build();
        GetConfigurationResponse response = agent.getConfig(request, componentAContext);
        assertEquals(ConfigStoreResponseStatus.NoConfig, response.getStatus());
        assertEquals("Service has no dynamic config", response.getErrorMessage());
    }

    @Test
    public void GIVEN_agent_running_WHEN_update_config_request_THEN_update_config_value() {
        when(kernel.findServiceTopic(TEST_COMPONENT_A))
                .thenReturn(configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A));
        UpdateConfigurationRequest request =
                UpdateConfigurationRequest.builder().componentName(TEST_COMPONENT_A).key(TEST_CONFIG_KEY_2).newValue(30)
                        .timestamp(System.currentTimeMillis()).build();
        UpdateConfigurationResponse response = agent.updateConfig(request, componentAContext);
        assertEquals(ConfigStoreResponseStatus.Success, response.getStatus());
        assertEquals(30,
                kernel.findServiceTopic(TEST_COMPONENT_A).find(PARAMETERS_CONFIG_KEY, TEST_CONFIG_KEY_2).getOnce());
    }

    @Test
    public void GIVEN_update_config_request_WHEN_update_key_does_not_exist_THEN_create_key() {
        when(kernel.findServiceTopic(TEST_COMPONENT_A))
                .thenReturn(configuration.getRoot().lookupTopics(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A));
        UpdateConfigurationRequest request =
                UpdateConfigurationRequest.builder().componentName(TEST_COMPONENT_A).key("NewKey").newValue("SomeValue")
                        .timestamp(System.currentTimeMillis()).build();
        UpdateConfigurationResponse response = agent.updateConfig(request, componentAContext);
        Topic newConfigKeyTopic = kernel.findServiceTopic(TEST_COMPONENT_A).find(PARAMETERS_CONFIG_KEY, "NewKey");
        assertEquals(ConfigStoreResponseStatus.Success, response.getStatus());
        assertNotNull(newConfigKeyTopic);
        assertEquals("SomeValue", newConfigKeyTopic.getOnce());
    }

    @Test
    public void GIVEN_update_config_request_WHEN_requested_component_is_not_self_request_THEN_fail() {
        UpdateConfigurationRequest request =
                UpdateConfigurationRequest.builder().componentName(TEST_COMPONENT_A).key(TEST_CONFIG_KEY_1).newValue(20)
                        .timestamp(System.currentTimeMillis()).build();
        UpdateConfigurationResponse response = agent.updateConfig(request, componentBContext);
        assertEquals(ConfigStoreResponseStatus.InvalidRequest, response.getStatus());
        assertEquals("Cross component updates are not allowed", response.getErrorMessage());
    }

    @Test
    public void GIVEN_agent_running_WHEN_subscribe_to_config_update_request_THEN_next_config_update_triggers_event()
            throws InterruptedException, IOException {
        SubscribeToConfigurationUpdateRequest request =
                SubscribeToConfigurationUpdateRequest.builder().componentName(TEST_COMPONENT_A).build();
        SubscribeToConfigurationUpdateResponse response = agent.subscribeToConfigUpdate(request, componentBContext);
        assertEquals(ConfigStoreResponseStatus.Success, response.getStatus());

        CountDownLatch messagePublishedToClient = new CountDownLatch(1);
        when(componentBContext.serverPush(anyInt(), any(FrameReader.Message.class))).thenAnswer(invocationOnMock -> {
            messagePublishedToClient.countDown();
            return new CompletableFuture<>();
        });
        configuration.getRoot()
                .lookup(SERVICES_NAMESPACE_TOPIC, TEST_COMPONENT_A, PARAMETERS_CONFIG_KEY, TEST_CONFIG_KEY_1)
                .withValue(25);

        assertTrue(messagePublishedToClient.await(10, TimeUnit.SECONDS));
        ArgumentCaptor<FrameReader.Message> messageArgumentCaptor = ArgumentCaptor.forClass(FrameReader.Message.class);
        verify(componentBContext)
                .serverPush(eq(BuiltInServiceDestinationCode.CONFIG_STORE.getValue()), messageArgumentCaptor.capture());
        ApplicationMessage message = ApplicationMessage.fromBytes(messageArgumentCaptor.getValue().getPayload());
        assertEquals(ConfigStoreServiceOpCodes.KEY_CHANGED.ordinal(), message.getOpCode());
        ConfigurationUpdateEvent event = IPCUtil.decode(message.getPayload(), ConfigurationUpdateEvent.class);
        assertEquals(TEST_COMPONENT_A, event.getComponentName());
        assertEquals(TEST_CONFIG_KEY_1, event.getChangedKey());
    }

    @Test
    public void GIVEN_agent_running_WHEN_subscribe_to_validate_config_request_THEN_validation_event_can_be_triggered()
            throws IOException {
        assertEquals(ConfigStoreResponseStatus.Success,
                agent.subscribeToConfigValidation(componentAContext).getStatus());

        Map<String, Object> configToValidate = new HashMap<>();
        configToValidate.put(TEST_CONFIG_KEY_1, 0);
        configToValidate.put(TEST_CONFIG_KEY_2, 100);

        assertTrue(agent.validateConfiguration(TEST_COMPONENT_A, configToValidate, new CompletableFuture<>()));
        ArgumentCaptor<FrameReader.Message> messageArgumentCaptor = ArgumentCaptor.forClass(FrameReader.Message.class);
        verify(componentAContext)
                .serverPush(eq(BuiltInServiceDestinationCode.CONFIG_STORE.getValue()), messageArgumentCaptor.capture());
        ApplicationMessage message = ApplicationMessage.fromBytes(messageArgumentCaptor.getValue().getPayload());
        assertEquals(ConfigStoreServiceOpCodes.VALIDATION_EVENT.ordinal(), message.getOpCode());
        ValidateConfigurationUpdateEvent event =
                IPCUtil.decode(message.getPayload(), ValidateConfigurationUpdateEvent.class);
        assertThat(event.getConfiguration(), IsMapContaining.hasEntry(TEST_CONFIG_KEY_1, 0));
        assertThat(event.getConfiguration(), IsMapContaining.hasEntry(TEST_CONFIG_KEY_2, 100));

    }

    @Test
    public void GIVEN_waiting_for_validation_response_WHEN_abandon_validation_event_THEN_succeed() throws IOException {
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
    public void GIVEN_validation_event_being_tracked_WHEN_send_config_validity_report_request_THEN_notify_validation_requester()
            throws ExecutionException, InterruptedException {
        assertEquals(ConfigStoreResponseStatus.Success,
                agent.subscribeToConfigValidation(componentAContext).getStatus());

        Map<String, Object> configToValidate = new HashMap<>();
        configToValidate.put(TEST_CONFIG_KEY_1, 0);
        configToValidate.put(TEST_CONFIG_KEY_2, 100);

        CompletableFuture<ConfigStoreIPCAgent.ConfigurationValidityReport> validationTracker =
                new CompletableFuture<>();
        assertTrue(agent.validateConfiguration(TEST_COMPONENT_A, configToValidate, validationTracker));

        SendConfigurationValidityReportRequest reportRequest =
                SendConfigurationValidityReportRequest.builder().status(ConfigurationValidityStatus.VALID).build();
        assertEquals(ConfigStoreResponseStatus.Success,
                agent.handleConfigValidityReport(reportRequest, componentAContext).getStatus());

        assertTrue(validationTracker.isDone());
        assertEquals(ConfigurationValidityStatus.VALID, validationTracker.get().getStatus());
    }

    @Test
    public void GIVEN_no_validation_event_is_tracked_WHEN_send_config_validity_report_request_THEN_fail() {
        SendConfigurationValidityReportRequest request =
                SendConfigurationValidityReportRequest.builder().status(ConfigurationValidityStatus.VALID).build();
        SendConfigurationValidityReportResponse response = agent.handleConfigValidityReport(request, componentAContext);
        assertEquals(ConfigStoreResponseStatus.InvalidRequest, response.getStatus());
        assertEquals("Validation request either timed out or was never made", response.getErrorMessage());
    }
}
