/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.ipc;

import com.aws.greengrass.builtin.services.configstore.ConfigStoreIPCEventStreamAgent;
import com.aws.greengrass.builtin.services.lifecycle.LifecycleIPCEventStreamAgent;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.logging.impl.Slf4jLogAdapter;
import com.aws.greengrass.logging.impl.config.LogConfig;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.testcommons.testutilities.UniqueRootPathExtension;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Pair;
import org.hamcrest.collection.IsMapContaining;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.event.Level;
import software.amazon.awssdk.aws.greengrass.GetConfigurationResponseHandler;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCClient;
import software.amazon.awssdk.aws.greengrass.SubscribeToComponentUpdatesResponseHandler;
import software.amazon.awssdk.aws.greengrass.model.ComponentUpdatePolicyEvents;
import software.amazon.awssdk.aws.greengrass.model.ConfigurationUpdateEvents;
import software.amazon.awssdk.aws.greengrass.model.ConfigurationValidityReport;
import software.amazon.awssdk.aws.greengrass.model.ConfigurationValidityStatus;
import software.amazon.awssdk.aws.greengrass.model.DeferComponentUpdateRequest;
import software.amazon.awssdk.aws.greengrass.model.GetConfigurationRequest;
import software.amazon.awssdk.aws.greengrass.model.GetConfigurationResponse;
import software.amazon.awssdk.aws.greengrass.model.PostComponentUpdateEvent;
import software.amazon.awssdk.aws.greengrass.model.PreComponentUpdateEvent;
import software.amazon.awssdk.aws.greengrass.model.ReportedLifecycleState;
import software.amazon.awssdk.aws.greengrass.model.SendConfigurationValidityReportRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToComponentUpdatesRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToComponentUpdatesResponse;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToConfigurationUpdateRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToConfigurationUpdateResponse;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToValidateConfigurationUpdatesRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToValidateConfigurationUpdatesResponse;
import software.amazon.awssdk.aws.greengrass.model.UpdateConfigurationRequest;
import software.amazon.awssdk.aws.greengrass.model.UpdateStateRequest;
import software.amazon.awssdk.aws.greengrass.model.ValidateConfigurationUpdateEvents;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;
import software.amazon.awssdk.eventstreamrpc.StreamResponseHandler;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.TEST_SERVICE_NAME;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.prepareKernelFromConfigFile;
import static com.aws.greengrass.ipc.IPCEventStreamService.NUCLEUS_DOMAIN_SOCKET_FILEPATH_FOR_COMPONENT;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SETENV_CONFIG_NAMESPACE;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static com.aws.greengrass.testcommons.testutilities.TestUtils.asyncAssertOnConsumer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.io.FileMatchers.anExistingFileOrDirectory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith({GGExtension.class, UniqueRootPathExtension.class})
class IPCServicesTest extends BaseITCase {
    private static final int TIMEOUT_FOR_CONFIG_STORE_SECONDS = 40;
    private static final int TIMEOUT_FOR_LIFECYCLE_SECONDS = 40;
    private static final int DEFAULT_TIMEOUT_IN_SEC = 10;
    private static final Logger logger = LogManager.getLogger(IPCServicesTest.class);
    private static Kernel kernel;
    private static EventStreamRPCConnection clientConnection;
    private static SocketOptions socketOptions;
    private static GreengrassCoreIPCClient greengrassCoreIPCClient;

    @BeforeAll
    static void beforeAll() throws InterruptedException, ExecutionException, IOException {
        kernel = prepareKernelFromConfigFile("ipc.yaml", IPCServicesTest.class, TEST_SERVICE_NAME);
        String authToken = IPCTestUtils.getAuthTokeForService(kernel, TEST_SERVICE_NAME);
        socketOptions = TestUtils.getSocketOptionsForIPC();
        clientConnection = IPCTestUtils.connectToGGCOverEventStreamIPC(socketOptions, authToken, kernel);
        greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);
    }

    @AfterAll
    static void afterAll() {
        if (clientConnection != null) {
            clientConnection.disconnect();
        }
        if (socketOptions != null) {
            socketOptions.close();
        }
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @AfterEach
    void afterEach() {
        LogConfig.getRootLogConfig().reset();
    }

    @BeforeEach
    void beforeEach(ExtensionContext context) {
        ignoreExceptionWithMessage(context, "Connection reset by peer");
        // Ignore if IPC can't send us more lifecycle updates because the test is already done.
        ignoreExceptionUltimateCauseWithMessage(context, "Channel not found for given connection context");
        ignoreExceptionOfType(context, InterruptedException.class);
    }

    @DisabledOnOs(OS.WINDOWS)
    @Test
    void Given_assign_path_for_ipcSocket_When_startUp_Then_ipcSocket_store_in_assigned_path() {
        DeviceConfiguration deviceConfiguration = kernel.getContext().get(DeviceConfiguration.class);
        String ipcPath = Coerce.toString(deviceConfiguration.getIpcSocketPath());
        assertThat(new File(ipcPath), is(anExistingFileOrDirectory()));
        assertEquals(ipcPath, Coerce.toString(kernel.getConfig().getRoot().lookup(SETENV_CONFIG_NAMESPACE, NUCLEUS_DOMAIN_SOCKET_FILEPATH_FOR_COMPONENT)));
    }

    @Test
    void GIVEN_ConfigStoreClient_WHEN_subscribe_THEN_key_sent_when_changed(ExtensionContext context) throws Exception {
        Topics configuration = kernel.findServiceTopic("ServiceName").createInteriorChild(CONFIGURATION_CONFIG_KEY);
        configuration.createLeafChild("abc").withValue("pqr");
        configuration.createLeafChild("DDF").withValue("xyz");
        kernel.getContext().runOnPublishQueueAndWait(() -> {
        });

        Pair<CompletableFuture<Void>, Consumer<ConfigurationUpdateEvents>> pAbcNew = asyncAssertOnConsumer((a) -> {
            assertThat(a.getConfigurationUpdateEvent().getKeyPath(), is(Collections.singletonList("abc")));
        });

        Pair<CompletableFuture<Void>, Consumer<ConfigurationUpdateEvents>> pDdfNew = asyncAssertOnConsumer((a) -> {
            assertThat(a.getConfigurationUpdateEvent().getKeyPath(), is(Collections.singletonList("DDF")));
        });

        SubscribeToConfigurationUpdateRequest request1 = new SubscribeToConfigurationUpdateRequest();
        request1.setComponentName("ServiceName");
        request1.setKeyPath(Collections.singletonList("abc"));
        greengrassCoreIPCClient.subscribeToConfigurationUpdate(request1, IPCTestUtils.getResponseHandler(pAbcNew.getRight(), logger))
                .getResponse().get(DEFAULT_TIMEOUT_IN_SEC, TimeUnit.SECONDS);

        SubscribeToConfigurationUpdateRequest request2 = new SubscribeToConfigurationUpdateRequest();
        request2.setComponentName("ServiceName");
        request2.setKeyPath(Collections.singletonList("DDF"));
        greengrassCoreIPCClient.subscribeToConfigurationUpdate(request2, IPCTestUtils.getResponseHandler(pDdfNew.getRight(), logger))
                .getResponse().get(DEFAULT_TIMEOUT_IN_SEC, TimeUnit.SECONDS);

        configuration.lookup("abc").withValue("ABC");
        configuration.lookup("DDF").withValue("ddf");
        try {
            pAbcNew.getLeft().get(TIMEOUT_FOR_CONFIG_STORE_SECONDS, TimeUnit.SECONDS);
            pDdfNew.getLeft().get(TIMEOUT_FOR_CONFIG_STORE_SECONDS, TimeUnit.SECONDS);
        } finally {
            configuration.remove();
        }
    }

    @SuppressWarnings({"PMD.CloseResource", "PMD.AvoidCatchingGenericException"})
    @Test
    void GIVEN_ConfigStoreEventStreamClient_WHEN_report_config_validation_status_THEN_inform_validation_requester()
            throws Exception {
        LogConfig.getRootLogConfig().setLevel(Level.DEBUG);
        CountDownLatch cdl = new CountDownLatch(1);
        String authToken = IPCTestUtils.getAuthTokeForService(kernel, TEST_SERVICE_NAME);
        try (EventStreamRPCConnection clientConnection =
                     IPCTestUtils.connectToGGCOverEventStreamIPC(socketOptions, authToken, kernel)) {

            CountDownLatch subscriptionLatch = new CountDownLatch(1);
            Slf4jLogAdapter.addGlobalListener(m -> {
                if (m.getMessage().contains("Config IPC subscribe to config validation request")) {
                    subscriptionLatch.countDown();
                }
            });

            GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);

            SubscribeToValidateConfigurationUpdatesRequest subscribe = new SubscribeToValidateConfigurationUpdatesRequest();
            CompletableFuture<SubscribeToValidateConfigurationUpdatesResponse> fut =
                    greengrassCoreIPCClient.subscribeToValidateConfigurationUpdates(subscribe, Optional.of(new StreamResponseHandler<ValidateConfigurationUpdateEvents>() {

                        @Override
                        public void onStreamEvent(ValidateConfigurationUpdateEvents events) {
                            assertNotNull(events);
                            assertNotNull(events.getValidateConfigurationUpdateEvent());
                            assertNotNull(events.getValidateConfigurationUpdateEvent().getConfiguration());
                            assertThat(events.getValidateConfigurationUpdateEvent().getConfiguration(),
                                    IsMapContaining.hasEntry("keyToValidate", "valueToValidate"));
                            cdl.countDown();

                            SendConfigurationValidityReportRequest reportRequest =
                                    new SendConfigurationValidityReportRequest();
                            ConfigurationValidityReport report = new ConfigurationValidityReport();
                            report.setStatus(ConfigurationValidityStatus.ACCEPTED);
                            report.setDeploymentId(events.getValidateConfigurationUpdateEvent().getDeploymentId());
                            reportRequest.setConfigurationValidityReport(report);

                            greengrassCoreIPCClient.sendConfigurationValidityReport(reportRequest, Optional.empty());
                        }

                        @Override
                        public boolean onStreamError(Throwable error) {
                            logger.atError().log("Received stream error.", error);
                            return false;
                        }

                        @Override
                        public void onStreamClosed() {

                        }
                    })).getResponse();
            try {
                fut.get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.atError().setCause(e).log("Error when subscribing to component updates");
                fail("Caught exception when subscribing to component updates");
            }
            assertTrue(subscriptionLatch.await(20, TimeUnit.SECONDS));

            CompletableFuture<ConfigurationValidityReport> responseTracker = new CompletableFuture<>();
            ConfigStoreIPCEventStreamAgent agent = kernel.getContext().get(ConfigStoreIPCEventStreamAgent.class);
            agent.validateConfiguration("ServiceName",
                    "A",
                    Collections.singletonMap("keyToValidate", "valueToValidate"), responseTracker);
            assertTrue(cdl.await(20, TimeUnit.SECONDS));

            assertEquals(ConfigurationValidityStatus.ACCEPTED,
                    responseTracker.get(20, TimeUnit.SECONDS).getStatus());

            SendConfigurationValidityReportRequest reportRequest =
                    new SendConfigurationValidityReportRequest();
            ConfigurationValidityReport report = new ConfigurationValidityReport();
            report.setStatus(ConfigurationValidityStatus.ACCEPTED);
            reportRequest.setConfigurationValidityReport(report);
            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> greengrassCoreIPCClient.sendConfigurationValidityReport(reportRequest, Optional.empty())
                            .getResponse().get(5, TimeUnit.SECONDS));
            assertThat(ex.getCause().getMessage(),
                    containsString("was null"));
        }
    }

    @SuppressWarnings({"PMD.CloseResource", "PMD.AvoidCatchingGenericException"})
    @Test
    void GIVEN_ConfigStoreEventStreamClient_WHEN_update_config_request_THEN_config_is_updated() throws Exception {
        LogConfig.getRootLogConfig().setLevel(Level.DEBUG);
        Topics configuration = kernel.findServiceTopic("ServiceName").createInteriorChild(CONFIGURATION_CONFIG_KEY);
        Topic configToUpdate = configuration.lookup("SomeKeyToUpdate").withNewerValue(0, "InitialValue");
        CountDownLatch cdl = new CountDownLatch(1);
        CountDownLatch subscriptionLatch = new CountDownLatch(1);
        Slf4jLogAdapter.addGlobalListener(m -> {
            if (m.getMessage().contains("subscribed to configuration update")) {
                subscriptionLatch.countDown();
            }
        });
        SubscribeToConfigurationUpdateRequest subscribe = new SubscribeToConfigurationUpdateRequest();
        subscribe.setComponentName("ServiceName");
        subscribe.setKeyPath(Collections.singletonList("SomeKeyToUpdate"));
        CompletableFuture<SubscribeToConfigurationUpdateResponse> fut =
                greengrassCoreIPCClient.subscribeToConfigurationUpdate(subscribe, Optional.of(new StreamResponseHandler<ConfigurationUpdateEvents>() {
                    @Override
                    public void onStreamEvent(ConfigurationUpdateEvents event) {
                        assertNotNull(event.getConfigurationUpdateEvent());
                        assertEquals("ServiceName", event.getConfigurationUpdateEvent().getComponentName());
                        assertNotNull(event.getConfigurationUpdateEvent().getKeyPath());
                        cdl.countDown();
                    }

                    @Override
                    public boolean onStreamError(Throwable error) {
                        logger.atError().log("Received stream error.", error);
                        return false;
                    }

                    @Override
                    public void onStreamClosed() {

                    }
                })).getResponse();
        try {
            fut.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.atError().setCause(e).log("Error when subscribing to component updates");
            fail("Caught exception when subscribing to component updates");
        }

        assertTrue(subscriptionLatch.await(20, TimeUnit.SECONDS));

        CountDownLatch configUpdated = new CountDownLatch(1);
        configToUpdate.subscribe((what, node) -> configUpdated.countDown());

        Map<String, Object> map = new HashMap<>();
        map.put("SomeKeyToUpdate", "SomeValueToUpdate");
        UpdateConfigurationRequest updateConfigurationRequest = new UpdateConfigurationRequest();
        updateConfigurationRequest.setKeyPath(Collections.emptyList());
        updateConfigurationRequest.setValueToMerge(map);
        updateConfigurationRequest.setTimestamp(Instant.now().plusSeconds(5));
        greengrassCoreIPCClient.updateConfiguration(updateConfigurationRequest, Optional.empty()).getResponse().get(50, TimeUnit.SECONDS);
        assertTrue(configUpdated.await(TIMEOUT_FOR_CONFIG_STORE_SECONDS, TimeUnit.SECONDS));
        assertTrue(cdl.await(TIMEOUT_FOR_CONFIG_STORE_SECONDS, TimeUnit.SECONDS));
        assertEquals("SomeValueToUpdate", configToUpdate.getOnce());

    }

    @SuppressWarnings({"PMD.CloseResource", "PMD.AvoidCatchingGenericException"})
    @Test
    void GIVEN_ConfigStoreEventStreamClient_WHEN_update_leaf_node_to_container_node_THEN_config_is_updated2() throws Exception {
        LogConfig.getRootLogConfig().setLevel(Level.DEBUG);
        Topics configuration = kernel.findServiceTopic("ServiceName").createInteriorChild(CONFIGURATION_CONFIG_KEY);
        Topic configToUpdate = configuration.lookup("SomeKeyToUpdate").withNewerValue(0, "InitialValue");
        CountDownLatch cdl = new CountDownLatch(1);
        CountDownLatch subscriptionLatch = new CountDownLatch(1);
        Slf4jLogAdapter.addGlobalListener(m -> {
            if (m.getMessage().contains("subscribed to configuration update")) {
                subscriptionLatch.countDown();
            }
        });
        SubscribeToConfigurationUpdateRequest subscribe = new SubscribeToConfigurationUpdateRequest();
        subscribe.setComponentName("ServiceName");
        subscribe.setKeyPath(Collections.singletonList("SomeKeyToUpdate"));
        CompletableFuture<SubscribeToConfigurationUpdateResponse> fut =
                greengrassCoreIPCClient.subscribeToConfigurationUpdate(subscribe, Optional.of(new StreamResponseHandler<ConfigurationUpdateEvents>() {
                    @Override
                    public void onStreamEvent(ConfigurationUpdateEvents event) {
                        assertNotNull(event.getConfigurationUpdateEvent());
                        assertEquals("ServiceName", event.getConfigurationUpdateEvent().getComponentName());
                        assertNotNull(event.getConfigurationUpdateEvent().getKeyPath());
                        cdl.countDown();
                    }

                    @Override
                    public boolean onStreamError(Throwable error) {
                        logger.atError().log("Received stream error.", error);
                        return false;
                    }

                    @Override
                    public void onStreamClosed() {

                    }
                })).getResponse();
        try {
            fut.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.atError().setCause(e).log("Error when subscribing to component updates");
            fail("Caught exception when subscribing to component updates");
        }

        assertTrue(subscriptionLatch.await(20, TimeUnit.SECONDS));

        CountDownLatch configUpdated = new CountDownLatch(1);
        configToUpdate.subscribe((what, node) -> configUpdated.countDown());

        Map<String, Object> map = new HashMap<>();
        Map<String, Object> map2 = new HashMap<>();
        map2.put("SomeChild", "SomeValueToUpdate");
        map.put("SomeKeyToUpdate", map2);
        List<String> l = new ArrayList<>();
        l.add("SomeKeyToUpdate");
        Instant now = Instant.now().plusSeconds(5);
        UpdateConfigurationRequest updateConfigurationRequest = new UpdateConfigurationRequest();
        updateConfigurationRequest.setKeyPath(l);
        updateConfigurationRequest.setValueToMerge(map2);
        updateConfigurationRequest.setTimestamp(now);
        greengrassCoreIPCClient.updateConfiguration(updateConfigurationRequest, Optional.empty()).getResponse().get(50, TimeUnit.SECONDS);
        assertTrue(configUpdated.await(TIMEOUT_FOR_CONFIG_STORE_SECONDS, TimeUnit.SECONDS));
        assertTrue(cdl.await(TIMEOUT_FOR_CONFIG_STORE_SECONDS, TimeUnit.SECONDS));
        Topic topic = (Topic) configuration.lookupTopics("SomeKeyToUpdate").getChild("SomeChild");
        assertEquals("SomeValueToUpdate", topic.getOnce());
    }

    @SuppressWarnings({"PMD.CloseResource", "PMD.AvoidCatchingGenericException"})
    @Test
    void GIVEN_ConfigStoreEventStreamClient_WHEN_adding_new_leaf_node_to_existing_container_node_THEN_config_is_updated3()
            throws Exception {
        LogConfig.getRootLogConfig().setLevel(Level.DEBUG);
        Topics configuration = kernel.findServiceTopic("ServiceName").createInteriorChild(CONFIGURATION_CONFIG_KEY);
        configuration.createInteriorChild("SomeContainerKeyToUpdate").createLeafChild("SomeContainerValue")
                .withValue("InitialValue");
        Topics configToUpdate = configuration.lookupTopics("SomeContainerKeyToUpdate");
        CountDownLatch cdl = new CountDownLatch(1);
        CountDownLatch subscriptionLatch = new CountDownLatch(1);
        try (AutoCloseable c = TestUtils.createCloseableLogListener(m -> {
            if (m.getMessage().contains("subscribed to configuration update")) {
                subscriptionLatch.countDown();
            }
        })) {
            SubscribeToConfigurationUpdateRequest subscribe = new SubscribeToConfigurationUpdateRequest();
            subscribe.setComponentName("ServiceName");
            subscribe.setKeyPath(Collections.singletonList("SomeContainerKeyToUpdate"));
            CompletableFuture<SubscribeToConfigurationUpdateResponse> fut =
                    greengrassCoreIPCClient.subscribeToConfigurationUpdate(subscribe,
                            Optional.of(new StreamResponseHandler<ConfigurationUpdateEvents>() {
                                @Override
                                public void onStreamEvent(ConfigurationUpdateEvents event) {
                                    assertNotNull(event.getConfigurationUpdateEvent());
                                    assertEquals("ServiceName",
                                            event.getConfigurationUpdateEvent().getComponentName());
                                    assertNotNull(event.getConfigurationUpdateEvent().getKeyPath());
                                    cdl.countDown();
                                }

                                @Override
                                public boolean onStreamError(Throwable error) {
                                    logger.atError().log("Received stream error.", error);
                                    return false;
                                }

                                @Override
                                public void onStreamClosed() {

                                }
                            })).getResponse();
            fut.get(3, TimeUnit.SECONDS);

            assertTrue(subscriptionLatch.await(20, TimeUnit.SECONDS));

            // count down 1 is during the call to subscribe
            CountDownLatch configUpdated = new CountDownLatch(2);
            configToUpdate.subscribe((what, node) -> configUpdated.countDown());
            kernel.getContext().waitForPublishQueueToClear();

            Map<String, Object> map2 = new HashMap<>();
            map2.put("SomeNewChild", "NewValue");
            List<String> l = new ArrayList<>();
            l.add("SomeContainerKeyToUpdate");
            Instant now = Instant.now().plusSeconds(5);
            UpdateConfigurationRequest updateConfigurationRequest = new UpdateConfigurationRequest();
            updateConfigurationRequest.setKeyPath(l);
            updateConfigurationRequest.setValueToMerge(map2);
            updateConfigurationRequest.setTimestamp(now);
            greengrassCoreIPCClient.updateConfiguration(updateConfigurationRequest, Optional.empty()).getResponse().get(50, TimeUnit.SECONDS);
            assertTrue(configUpdated.await(TIMEOUT_FOR_CONFIG_STORE_SECONDS, TimeUnit.SECONDS));
            assertTrue(cdl.await(TIMEOUT_FOR_CONFIG_STORE_SECONDS, TimeUnit.SECONDS));
            Topic topic = (Topic) configToUpdate.getChild("SomeNewChild");
            assertEquals("NewValue", topic.getOnce());
        }
    }


    @SuppressWarnings("PMD.CloseResource")
    @Test
    void GIVEN_ConfigStoreEventStreamClient_WHEN_read_THEN_value_returned() throws Exception {
        Topics custom = kernel.findServiceTopic("ServiceName").createInteriorChild(CONFIGURATION_CONFIG_KEY);
        custom.createLeafChild("abc").withValue("ABC");
        custom.createInteriorChild("DDF").createLeafChild("A").withValue("C");
        GetConfigurationRequest getConfigurationRequest = new GetConfigurationRequest();
        getConfigurationRequest.setComponentName("ServiceName");
        getConfigurationRequest.setKeyPath(Collections.singletonList("abc"));
        GetConfigurationResponseHandler handler =
                greengrassCoreIPCClient.getConfiguration(getConfigurationRequest, Optional.empty());
        GetConfigurationResponse getConfigurationResponse = handler.getResponse().get(10, TimeUnit.SECONDS);
        assertEquals("ServiceName", getConfigurationResponse.getComponentName());
        assertTrue(getConfigurationResponse.getValue().containsKey("abc"));

        GetConfigurationRequest getConfigurationRequest2 = new GetConfigurationRequest();
        getConfigurationRequest2.setComponentName("ServiceName");
        getConfigurationRequest2.setKeyPath(Collections.singletonList("DDF"));
        handler = greengrassCoreIPCClient.getConfiguration(getConfigurationRequest2, Optional.empty());
        getConfigurationResponse = handler.getResponse().get(10, TimeUnit.SECONDS);
        assertEquals("ServiceName", getConfigurationResponse.getComponentName());
        // Can read nested values
        assertThat(getConfigurationResponse.getValue(), aMapWithSize(1));
        assertThat(getConfigurationResponse.getValue(), IsMapContaining.hasKey("A"));
        assertThat(getConfigurationResponse.getValue().get("A"), is("C"));
    }

    @Test
    void GIVEN_ConfigStoreClient_WHEN_read_THEN_value_returned() throws Exception {
        Topics custom = kernel.findServiceTopic("ServiceName").createInteriorChild(CONFIGURATION_CONFIG_KEY);
        custom.createLeafChild("abc").withValue("ABC");
        custom.createInteriorChild("DDF").createLeafChild("A").withValue("C");

        try {
            GetConfigurationRequest getConfigurationRequest = new GetConfigurationRequest();
            getConfigurationRequest.setComponentName("ServiceName");
            getConfigurationRequest.setKeyPath(Collections.singletonList("abc"));
            GetConfigurationResponse getConfigurationResponse = greengrassCoreIPCClient
                    .getConfiguration(getConfigurationRequest, Optional.empty()).getResponse().get(DEFAULT_TIMEOUT_IN_SEC, TimeUnit.SECONDS);
            assertEquals("ABC", getConfigurationResponse.getValue().get("abc"));

            getConfigurationRequest = new GetConfigurationRequest();
            getConfigurationRequest.setComponentName("ServiceName");
            getConfigurationRequest.setKeyPath(Collections.singletonList("DDF"));
            getConfigurationResponse = greengrassCoreIPCClient
                    .getConfiguration(getConfigurationRequest, Optional.empty()).getResponse().get(DEFAULT_TIMEOUT_IN_SEC, TimeUnit.SECONDS);
            Map<String, Object> value = getConfigurationResponse.getValue();
            assertThat(value, aMapWithSize(1));
            assertThat(value, IsMapContaining.hasKey("A"));
            assertThat(value.get("A"), is("C"));
        } finally {
            custom.remove();
        }
    }


    @SuppressWarnings("PMD.CloseResource")
    @Test
    void GIVEN_LifecycleClient_WHEN_update_state_and_service_dies_THEN_service_errored() throws Exception {
        CountDownLatch cdl = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(2);
        GreengrassService startupService = kernel.locate("StartupService");
        EventStreamRPCConnection clientConnection = null;
        try {
            kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
                if ("StartupService".equals(service.getName())) {
                    if (newState.equals(State.STARTING)) {
                        started.countDown();
                    }
                    if (newState.equals(State.RUNNING) && oldState.equals(State.STARTING)) {
                        cdl.countDown();
                    }
                    if (newState.equals(State.ERRORED) && oldState.equals(State.RUNNING)) {
                        cdl.countDown();
                    }
                }
            });
            startupService.requestStart();
            assertTrue(started.await(TIMEOUT_FOR_LIFECYCLE_SECONDS, TimeUnit.SECONDS));
            String authToken = IPCTestUtils.getAuthTokeForService(kernel, "StartupService");
            clientConnection = IPCTestUtils.connectToGGCOverEventStreamIPC(socketOptions, authToken, kernel);
            UpdateStateRequest updateStateRequest = new UpdateStateRequest();
            updateStateRequest.setState(ReportedLifecycleState.RUNNING);
            GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);
            greengrassCoreIPCClient.updateState(updateStateRequest, Optional.empty()).getResponse().get(5, TimeUnit.SECONDS);
            assertTrue(cdl.await(TIMEOUT_FOR_LIFECYCLE_SECONDS, TimeUnit.SECONDS));
        } finally {
            if (clientConnection != null) {
                clientConnection.close();
            }
            startupService.close().get();
        }
    }

    @SuppressWarnings("PMD.CloseResource")
    @Test
    void GIVEN_LifeCycleEventStreamClient_WHEN_update_state_THEN_service_state_changes() throws Exception {
        CountDownLatch cdl = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (TEST_SERVICE_NAME.equals(service.getName())) {
                if (newState.equals(State.ERRORED) && oldState.equals(State.RUNNING)) {
                    cdl.countDown();
                }
            }
        });
        UpdateStateRequest updateStateRequest = new UpdateStateRequest();
        updateStateRequest.setState(ReportedLifecycleState.ERRORED);
        GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);
        greengrassCoreIPCClient.updateState(updateStateRequest, Optional.empty()).getResponse().get();
        assertTrue(cdl.await(TIMEOUT_FOR_LIFECYCLE_SECONDS, TimeUnit.SECONDS));
    }


    @SuppressWarnings({"PMD.CloseResource", "PMD.AvoidCatchingGenericException"})
    @Test
    void GIVEN_LifeCycleEventStreamClient_WHEN_subscribe_to_component_update_THEN_service_receives_update_and_close_stream() throws Exception {
        LogConfig.getRootLogConfig().setLevel(Level.DEBUG);  // debug log required for assertion
        SubscribeToComponentUpdatesRequest subscribeToComponentUpdatesRequest =
                new SubscribeToComponentUpdatesRequest();
        CountDownLatch cdl = new CountDownLatch(2);
        CountDownLatch subscriptionLatch = new CountDownLatch(1);
        Slf4jLogAdapter.addGlobalListener(m -> {
            m.getMessage().contains("subscribed to component update");
            subscriptionLatch.countDown();
        });
        CompletableFuture<Future> futureFuture = new CompletableFuture<>();
        GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);
        StreamResponseHandler<ComponentUpdatePolicyEvents> responseHandler = new StreamResponseHandler<ComponentUpdatePolicyEvents>() {
            @Override
            public void onStreamEvent(ComponentUpdatePolicyEvents streamEvent) {
                if (streamEvent.getPreUpdateEvent() != null) {
                    cdl.countDown();
                    DeferComponentUpdateRequest deferComponentUpdateRequest = new DeferComponentUpdateRequest();
                    deferComponentUpdateRequest.setRecheckAfterMs(Duration.ofSeconds(1).toMillis());
                    deferComponentUpdateRequest.setDeploymentId(streamEvent.getPreUpdateEvent()
                            .getDeploymentId());
                    deferComponentUpdateRequest.setMessage("Test");
                    futureFuture.complete(greengrassCoreIPCClient.deferComponentUpdate(
                            deferComponentUpdateRequest, Optional.empty()).getResponse());
                }
                if (streamEvent.getPostUpdateEvent() != null) {
                    cdl.countDown();
                }
            }

            @Override
            public boolean onStreamError(Throwable error) {
                logger.atError().setCause(error).log("Caught stream error");
                return false;
            }

            @Override
            public void onStreamClosed() {

            }
        };
        SubscribeToComponentUpdatesResponseHandler streamHandler =
                greengrassCoreIPCClient.subscribeToComponentUpdates(subscribeToComponentUpdatesRequest,
                        Optional.of(responseHandler));
        CompletableFuture<SubscribeToComponentUpdatesResponse> fut =
                streamHandler.getResponse();

        fut.get(3, TimeUnit.SECONDS);

        assertTrue(subscriptionLatch.await(5, TimeUnit.SECONDS));
        // GG_NEEDS_REVIEW: TODO: When Cli support safe update setting in local deployment, then create a local deployment here to
        //  trigger update
        LifecycleIPCEventStreamAgent lifecycleIPCEventStreamAgent =
                kernel.getContext().get(LifecycleIPCEventStreamAgent.class);
        PreComponentUpdateEvent event = new PreComponentUpdateEvent();
        event.setDeploymentId("abc");
        List<Future<DeferComponentUpdateRequest>> futureList =
                lifecycleIPCEventStreamAgent.sendPreComponentUpdateEvent(event);
        assertEquals(1, futureList.size());
        futureFuture.get(5, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS);
        futureList.get(0).get(5, TimeUnit.SECONDS);
        lifecycleIPCEventStreamAgent.sendPostComponentUpdateEvent(new PostComponentUpdateEvent());
        assertTrue(cdl.await(TIMEOUT_FOR_LIFECYCLE_SECONDS, TimeUnit.SECONDS));
        streamHandler.closeStream();
        // Checking if a request can be made on teh same connection after closing the stream
        UpdateStateRequest updateStateRequest = new UpdateStateRequest();
        updateStateRequest.setState(ReportedLifecycleState.RUNNING);
        greengrassCoreIPCClient.updateState(updateStateRequest, Optional.empty()).getResponse()
                .get(3, TimeUnit.SECONDS);
    }
}
