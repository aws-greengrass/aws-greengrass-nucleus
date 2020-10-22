/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.greengrass.integrationtests.ipc;

import com.aws.greengrass.builtin.services.configstore.ConfigStoreIPCAgent;
import com.aws.greengrass.builtin.services.configstore.ConfigStoreIPCEventStreamAgent;
import com.aws.greengrass.builtin.services.lifecycle.DeferUpdateRequest;
import com.aws.greengrass.builtin.services.lifecycle.LifecycleIPCEventStreamAgent;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.ipc.IPCClient;
import com.aws.greengrass.ipc.IPCClientImpl;
import com.aws.greengrass.ipc.config.KernelIPCClientConfig;
import com.aws.greengrass.ipc.services.configstore.ConfigStore;
import com.aws.greengrass.ipc.services.configstore.ConfigStoreImpl;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.logging.impl.Slf4jLogAdapter;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.util.Pair;
import org.hamcrest.collection.IsMapContaining;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.aws.greengrass.GetConfigurationResponseHandler;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCClient;
import software.amazon.awssdk.aws.greengrass.model.ComponentUpdatePolicyEvents;
import software.amazon.awssdk.aws.greengrass.model.ConfigurationUpdateEvents;
import software.amazon.awssdk.aws.greengrass.model.ConfigurationValidityReport;
import software.amazon.awssdk.aws.greengrass.model.ConfigurationValidityStatus;
import software.amazon.awssdk.aws.greengrass.model.DeferComponentUpdateRequest;
import software.amazon.awssdk.aws.greengrass.model.GetConfigurationRequest;
import software.amazon.awssdk.aws.greengrass.model.GetConfigurationResponse;
import software.amazon.awssdk.aws.greengrass.model.LifecycleState;
import software.amazon.awssdk.aws.greengrass.model.PostComponentUpdateEvent;
import software.amazon.awssdk.aws.greengrass.model.PreComponentUpdateEvent;
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

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.TEST_SERVICE_NAME;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.getIPCConfigForService;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.prepareKernelFromConfigFile;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static com.aws.greengrass.testcommons.testutilities.TestUtils.asyncAssertOnConsumer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith(GGExtension.class)
class IPCServicesTest {

    private final static Logger log = LogManager.getLogger(IPCServicesTest.class);
    private static int TIMEOUT_FOR_CONFIG_STORE_SECONDS = 20;
    private static int TIMEOUT_FOR_LIFECYCLE_SECONDS = 20;
    private static Logger logger = LogManager.getLogger(IPCServicesTest.class);

    @TempDir
    static Path tempRootDir;

    private static Kernel kernel;
    private static EventStreamRPCConnection clientConnection;
    private IPCClient client;
    private static SocketOptions socketOptions;

    @BeforeAll
    static void beforeAll() throws InterruptedException, IOException, ExecutionException {
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());
        kernel = prepareKernelFromConfigFile("ipc.yaml", IPCServicesTest.class, TEST_SERVICE_NAME);
        String authToken = IPCTestUtils.getAuthTokeForService(kernel, TEST_SERVICE_NAME);
        socketOptions = TestUtils.getSocketOptionsForIPC();
        clientConnection = IPCTestUtils.connectToGGCOverEventStreamIPC(socketOptions, authToken, kernel);
    }

    @AfterAll
    static void afterAll() throws InterruptedException {
        if (clientConnection != null) {
            clientConnection.disconnect();
        }
        if (socketOptions != null) {
            socketOptions.close();
        }
        kernel.shutdown();
    }

    @BeforeEach
    void beforeEach(ExtensionContext context) {

        ignoreExceptionWithMessage(context, "Connection reset by peer");
        // Ignore if IPC can't send us more lifecycle updates because the test is already done.
        ignoreExceptionUltimateCauseWithMessage(context, "Channel not found for given connection context");
        ignoreExceptionOfType(context, InterruptedException.class);
    }

    @AfterEach
    void afterEach() throws IOException {
        if (client != null) {
            client.disconnect();
        }
    }


    @Test
    void GIVEN_ConfigStoreClient_WHEN_subscribe_THEN_key_sent_when_changed(ExtensionContext context) throws Exception {
        KernelIPCClientConfig config = getIPCConfigForService("ServiceName", kernel);
        client = new IPCClientImpl(config);
        ConfigStore c = new ConfigStoreImpl(client);

        Topics configuration = kernel.findServiceTopic("ServiceName").createInteriorChild(PARAMETERS_CONFIG_KEY);
        configuration.createLeafChild("abc").withValue("pqr");
        configuration.createLeafChild("DDF").withValue("xyz");
        kernel.getContext().runOnPublishQueueAndWait(() -> {
        });

        Pair<CompletableFuture<Void>, Consumer<List<String>>> pAbc = asyncAssertOnConsumer((a) -> {
            assertThat(a, is(Collections.singletonList("abc")));
        });
        Pair<CompletableFuture<Void>, Consumer<List<String>>> pDdf = asyncAssertOnConsumer((a) -> {
            assertThat(a, is(Collections.singletonList("DDF")));
        });

        ignoreExceptionOfType(context, TimeoutException.class);

        c.subscribeToConfigurationUpdate("ServiceName", Collections.singletonList("abc"), pAbc.getRight());
        c.subscribeToConfigurationUpdate("ServiceName", Collections.singletonList("DDF"), pDdf.getRight());
        configuration.lookup("abc").withValue("ABC");
        configuration.lookup("DDF").withValue("ddf");

        try {
            pAbc.getLeft().get(TIMEOUT_FOR_CONFIG_STORE_SECONDS, TimeUnit.SECONDS);
            pDdf.getLeft().get(TIMEOUT_FOR_CONFIG_STORE_SECONDS, TimeUnit.SECONDS);
        } finally {
            configuration.remove();
        }
    }

    @Test
    void GIVEN_ConfigStoreClient_WHEN_subscribe_to_validate_config_THEN_validate_event_can_be_sent_to_client()
            throws Exception {
        KernelIPCClientConfig config = getIPCConfigForService("ServiceName", kernel);
        client = new IPCClientImpl(config);
        ConfigStore c = new ConfigStoreImpl(client);

        CountDownLatch eventReceivedByClient = new CountDownLatch(1);
        c.subscribeToValidateConfiguration((configMap) -> {
            assertThat(configMap, IsMapContaining.hasEntry("keyToValidate", "valueToValidate"));
            eventReceivedByClient.countDown();
        });

        ConfigStoreIPCAgent agent = kernel.getContext().get(ConfigStoreIPCAgent.class);
        CompletableFuture<com.aws.greengrass.ipc.services.configstore.ConfigurationValidityReport> validateResultFuture =
                new CompletableFuture<>();
        try {
            agent.validateConfiguration("ServiceName",
                    Collections.singletonMap("keyToValidate", "valueToValidate"),
                    validateResultFuture);
            assertTrue(eventReceivedByClient.await(500, TimeUnit.MILLISECONDS));
        } finally {
            agent.discardValidationReportTracker("ServiceName", validateResultFuture);
        }
    }

    @Test
    void GIVEN_ConfigStoreClient_WHEN_report_config_validation_status_THEN_inform_validation_requester()
            throws Exception {
        KernelIPCClientConfig config = getIPCConfigForService("ServiceName", kernel);
        client = new IPCClientImpl(config);
        ConfigStore c = new ConfigStoreImpl(client);

        Pair<CompletableFuture<Void>, Consumer<Map<String, Object>>> cb = asyncAssertOnConsumer((configMap) -> {
            assertThat(configMap, IsMapContaining.hasEntry("keyToValidate", "valueToValidate"));
        });
        c.subscribeToValidateConfiguration(cb.getRight());

        CompletableFuture<com.aws.greengrass.ipc.services.configstore.ConfigurationValidityReport> responseTracker =
                new CompletableFuture<>();
        ConfigStoreIPCAgent agent = kernel.getContext().get(ConfigStoreIPCAgent.class);
        agent.validateConfiguration("ServiceName",
                Collections.singletonMap("keyToValidate", "valueToValidate"), responseTracker);
        cb.getLeft().get(TIMEOUT_FOR_CONFIG_STORE_SECONDS, TimeUnit.SECONDS);

        c.sendConfigurationValidityReport(com.aws.greengrass.ipc.services.configstore.ConfigurationValidityStatus.VALID,
                null);
        assertEquals(com.aws.greengrass.ipc.services.configstore.ConfigurationValidityStatus.VALID,
                responseTracker.get().getStatus());
    }


    @SuppressWarnings({"PMD.CloseResource", "PMD.AvoidCatchingGenericException"})
    @Test
    void GIVEN_ConfigStoreEventStreamClient_WHEN_report_config_validation_status_THEN_inform_validation_requester()
            throws Exception {
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
                            reportRequest.setConfigurationValidityReport(report);

                            try {
                                greengrassCoreIPCClient.sendConfigurationValidityReport(reportRequest, Optional.empty()).getResponse()
                                        .get(10, TimeUnit.SECONDS);
                            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                                fail("received invalid update validate configuration event", e);
                            }
                        }

                        @Override
                        public boolean onStreamError(Throwable error) {
                            log.atError().log("Received stream error.", error);
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
                    Collections.singletonMap("keyToValidate", "valueToValidate"), responseTracker);
            assertTrue(cdl.await(20, TimeUnit.SECONDS));

            assertEquals(ConfigurationValidityStatus.ACCEPTED, responseTracker.get().getStatus());
        }
    }

    @SuppressWarnings({"PMD.CloseResource", "PMD.AvoidCatchingGenericException"})
    @Test
    void GIVEN_ConfigStoreEventStreamClient_WHEN_update_config_request_THEN_config_is_updated() throws Exception {
        Topics configuration = kernel.findServiceTopic("ServiceName").createInteriorChild(PARAMETERS_CONFIG_KEY);
        Topic configToUpdate = configuration.lookup("SomeKeyToUpdate").withNewerValue(0, "InitialValue");
        CountDownLatch cdl = new CountDownLatch(1);
        CountDownLatch subscriptionLatch = new CountDownLatch(1);
        Slf4jLogAdapter.addGlobalListener(m -> {
            if (m.getMessage().contains("subscribed to configuration update")) {
                subscriptionLatch.countDown();
            }
        });
        GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);

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
                        log.atError().log("Received stream error.", error);
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
        updateConfigurationRequest.setComponentName("ServiceName");
        updateConfigurationRequest.setKeyPath(Collections.singletonList("SomeKeyToUpdate"));
        updateConfigurationRequest.setNewValue(map);
        updateConfigurationRequest.setTimestamp(Instant.now());
        greengrassCoreIPCClient.updateConfiguration(updateConfigurationRequest, Optional.empty()).getResponse().get(50, TimeUnit.SECONDS);
        assertTrue(configUpdated.await(TIMEOUT_FOR_CONFIG_STORE_SECONDS, TimeUnit.SECONDS));
        assertTrue(cdl.await(TIMEOUT_FOR_CONFIG_STORE_SECONDS, TimeUnit.SECONDS));
        assertEquals("SomeValueToUpdate", configToUpdate.getOnce());

    }


    @SuppressWarnings("PMD.CloseResource")
    @Test
    void GIVEN_ConfigStoreEventStreamClient_WHEN_read_THEN_value_returned() throws Exception {
        Topics custom = kernel.findServiceTopic("ServiceName").createInteriorChild(PARAMETERS_CONFIG_KEY);
        custom.createLeafChild("abc").withValue("ABC");
        custom.createInteriorChild("DDF").createLeafChild("A").withValue("C");
        GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);
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
        KernelIPCClientConfig config = getIPCConfigForService("ServiceName", kernel);
        client = new IPCClientImpl(config);
        ConfigStore c = new ConfigStoreImpl(client);

        Topics custom = kernel.findServiceTopic("ServiceName").createInteriorChild(PARAMETERS_CONFIG_KEY);
        custom.createLeafChild("abc").withValue("ABC");
        custom.createInteriorChild("DDF").createLeafChild("A").withValue("C");

        try {
            // Can read individual value
            assertEquals("ABC", c.getConfiguration("ServiceName", Collections.singletonList("abc")));

            // Can read nested values
            Map<String, Object> val = (Map<String, Object>) c.getConfiguration("ServiceName",
                    Collections.singletonList("DDF"));
            assertThat(val, aMapWithSize(1));
            assertThat(val, IsMapContaining.hasKey("A"));
            assertThat(val.get("A"), is("C"));
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
            assertTrue(started.await(10, TimeUnit.SECONDS));
            String authToken = IPCTestUtils.getAuthTokeForService(kernel, TEST_SERVICE_NAME);
            clientConnection = IPCTestUtils.connectToGGCOverEventStreamIPC(socketOptions, authToken, kernel);
            UpdateStateRequest updateStateRequest = new UpdateStateRequest();
            updateStateRequest.setServiceName("StartupService");
            updateStateRequest.setState(LifecycleState.RUNNING);
            GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);
            greengrassCoreIPCClient.updateState(updateStateRequest, Optional.empty());
            assertTrue(cdl.await(TIMEOUT_FOR_LIFECYCLE_SECONDS, TimeUnit.SECONDS));

        } finally {
            clientConnection.close();
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
        updateStateRequest.setState(LifecycleState.ERRORED);
        GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);
        greengrassCoreIPCClient.updateState(updateStateRequest, Optional.empty()).getResponse().get();
        assertTrue(cdl.await(TIMEOUT_FOR_LIFECYCLE_SECONDS, TimeUnit.SECONDS));
    }

    @SuppressWarnings({"PMD.CloseResource", "PMD.AvoidCatchingGenericException"})
    @Test
    void GIVEN_LifeCycleEventStreamClient_WHEN_subscribe_to_component_update_THEN_service_receives_update() throws Exception {

        SubscribeToComponentUpdatesRequest subscribeToComponentUpdatesRequest =
                new SubscribeToComponentUpdatesRequest();
        CountDownLatch cdl = new CountDownLatch(2);
        CountDownLatch subscriptionLatch = new CountDownLatch(1);
        Slf4jLogAdapter.addGlobalListener(m -> {
            m.getMessage().contains("subscribed to component update");
            subscriptionLatch.countDown();
        });
        GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);
        CompletableFuture<SubscribeToComponentUpdatesResponse> fut =
                greengrassCoreIPCClient.subscribeToComponentUpdates(subscribeToComponentUpdatesRequest,
                        Optional.of(new StreamResponseHandler<ComponentUpdatePolicyEvents>() {
                            @Override
                            public void onStreamEvent(ComponentUpdatePolicyEvents streamEvent) {
                                if (streamEvent.getPreUpdateEvent() != null) {
                                    cdl.countDown();
                                    DeferComponentUpdateRequest deferComponentUpdateRequest = new DeferComponentUpdateRequest();
                                    deferComponentUpdateRequest.setRecheckAfterMs(Duration.ofSeconds(1).toMillis());
                                    deferComponentUpdateRequest.setMessage("Test");
                                    try {
                                        greengrassCoreIPCClient.deferComponentUpdate(deferComponentUpdateRequest, Optional.empty()).getResponse()
                                                .get(5, TimeUnit.SECONDS);
                                    } catch (Exception e) {
                                        fail("Failed to send defer component updated");
                                    }
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
                        })).getResponse();
        try {
            fut.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.atError().setCause(e).log("Error when subscribing to component updates");
            fail("Caught exception when subscribing to component updates");
        }

        assertTrue(subscriptionLatch.await(5, TimeUnit.SECONDS));
        // TODO: When Cli support safe update setting in local deployment, then create a local deployment here to
        //  trigger update
        LifecycleIPCEventStreamAgent lifecycleIPCEventStreamAgent =
                kernel.getContext().get(LifecycleIPCEventStreamAgent.class);
        List<Future<DeferUpdateRequest>> futureList =
                lifecycleIPCEventStreamAgent.sendPreComponentUpdateEvent(new PreComponentUpdateEvent());
        futureList.get(0).get(Duration.ofSeconds(2).toMillis(), TimeUnit.SECONDS);
        lifecycleIPCEventStreamAgent.sendPostComponentUpdateEvent(new PostComponentUpdateEvent());
        assertTrue(cdl.await(TIMEOUT_FOR_LIFECYCLE_SECONDS, TimeUnit.SECONDS));
    }
}
