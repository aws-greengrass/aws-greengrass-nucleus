/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.greengrass.integrationtests.ipc;

import com.aws.greengrass.builtin.services.configstore.ConfigStoreIPCAgent;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.ipc.IPCClient;
import com.aws.greengrass.ipc.IPCClientImpl;
import com.aws.greengrass.ipc.config.KernelIPCClientConfig;
import com.aws.greengrass.ipc.services.configstore.ConfigStore;
import com.aws.greengrass.ipc.services.configstore.ConfigStoreImpl;
import com.aws.greengrass.ipc.services.configstore.ConfigurationValidityReport;
import com.aws.greengrass.ipc.services.configstore.ConfigurationValidityStatus;
import com.aws.greengrass.ipc.services.lifecycle.Lifecycle;
import com.aws.greengrass.ipc.services.lifecycle.LifecycleImpl;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
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

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(GGExtension.class)
class IPCServicesTest {

    private static int TIMEOUT_FOR_CONFIG_STORE_SECONDS = 2;

    @TempDir
    static Path tempRootDir;

    private static Kernel kernel;
    private IPCClient client;

    @BeforeAll
    static void beforeAll() throws InterruptedException {
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());
        kernel = prepareKernelFromConfigFile("ipc.yaml", IPCServicesTest.class, TEST_SERVICE_NAME);
    }

    @AfterAll
    static void afterAll() throws InterruptedException {
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
        client.disconnect();
    }


    @Test
    void GIVEN_ConfigStoreClient_WHEN_subscribe_THEN_key_sent_when_changed(ExtensionContext context) throws Exception {
        KernelIPCClientConfig config = getIPCConfigForService("ServiceName", kernel);
        client = new IPCClientImpl(config);
        ConfigStore c = new ConfigStoreImpl(client);

        Topics configuration = kernel.findServiceTopic("ServiceName").createInteriorChild(PARAMETERS_CONFIG_KEY);
        configuration.createLeafChild("abc").withValue("pqr");
        configuration.createLeafChild("DDF").withValue("xyz");
        kernel.getContext().runOnPublishQueueAndWait(() -> {});

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
        CompletableFuture<ConfigurationValidityReport> validateResultFuture = new CompletableFuture<>();
        try {
            agent.validateConfiguration("ServiceName", Collections.singletonMap("keyToValidate", "valueToValidate"),
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

        CompletableFuture<ConfigurationValidityReport> responseTracker = new CompletableFuture<>();
        ConfigStoreIPCAgent agent = kernel.getContext().get(ConfigStoreIPCAgent.class);
        agent.validateConfiguration("ServiceName",
                Collections.singletonMap("keyToValidate", "valueToValidate"), responseTracker);
        cb.getLeft().get(TIMEOUT_FOR_CONFIG_STORE_SECONDS, TimeUnit.SECONDS);

        c.sendConfigurationValidityReport(ConfigurationValidityStatus.VALID, null);
        assertEquals(ConfigurationValidityStatus.VALID, responseTracker.get().getStatus());
    }

    @Test
    void GIVEN_ConfigStoreClient_WHEN_update_config_request_THEN_config_is_updated() throws Exception {
        KernelIPCClientConfig config = getIPCConfigForService("ServiceName", kernel);
        client = new IPCClientImpl(config);
        ConfigStore c = new ConfigStoreImpl(client);

        Topics configuration = kernel.findServiceTopic("ServiceName").createInteriorChild(PARAMETERS_CONFIG_KEY);
        Topic configToUpdate = configuration.lookup("SomeKeyToUpdate").withNewerValue(0, "InitialValue");

        CountDownLatch configUpdated = new CountDownLatch(1);
        configToUpdate.subscribe((what, node) -> configUpdated.countDown());

        c.updateConfiguration("ServiceName", Collections.singletonList("SomeKeyToUpdate"), "SomeValueToUpdate",
                System.currentTimeMillis(), null);

        assertTrue(configUpdated.await(TIMEOUT_FOR_CONFIG_STORE_SECONDS, TimeUnit.SECONDS));
        assertEquals("SomeValueToUpdate", configToUpdate.getOnce());
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

    @Test
    void GIVEN_LifeCycleClient_WHEN_update_state_THEN_service_state_changes() throws Exception {
        KernelIPCClientConfig config = getIPCConfigForService(TEST_SERVICE_NAME, kernel);
        client = new IPCClientImpl(config);
        CountDownLatch cdl = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState ) ->{

            if(TEST_SERVICE_NAME.equals(service.getName())){
                if(newState.equals(State.ERRORED) && oldState.equals(State.RUNNING)){
                    cdl.countDown();
                }
            }
        });
        Lifecycle lifecycle = new LifecycleImpl(client);
        lifecycle.updateState("ERRORED");
        assertTrue(cdl.await(TIMEOUT_FOR_CONFIG_STORE_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void GIVEN_LifecycleClient_WHEN_update_state_and_service_dies_THEN_service_errored() throws Exception {
        KernelIPCClientConfig config = getIPCConfigForService("StartupService", kernel);
        client = new IPCClientImpl(config);
        CountDownLatch cdl = new CountDownLatch(2);
        CountDownLatch started = new CountDownLatch(1);

        GreengrassService startupService = kernel.locate("StartupService");
        try {
            startupService.requestStart();

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
            assertTrue(started.await(10, TimeUnit.SECONDS));
            Lifecycle lifecycle = new LifecycleImpl(client);
            lifecycle.updateState("RUNNING");
            assertTrue(cdl.await(10, TimeUnit.SECONDS));
        } finally {
            startupService.close().get();
        }
    }

}
