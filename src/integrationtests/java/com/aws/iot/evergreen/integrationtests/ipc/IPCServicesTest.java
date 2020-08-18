/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.integrationtests.ipc;

import com.aws.iot.evergreen.builtin.services.configstore.ConfigStoreIPCAgent;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.ipc.IPCClient;
import com.aws.iot.evergreen.ipc.IPCClientImpl;
import com.aws.iot.evergreen.ipc.config.KernelIPCClientConfig;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStore;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStoreImpl;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigurationValidityReport;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigurationValidityStatus;
import com.aws.iot.evergreen.ipc.services.servicediscovery.LookupResourceRequest;
import com.aws.iot.evergreen.ipc.services.servicediscovery.RegisterResourceRequest;
import com.aws.iot.evergreen.ipc.services.servicediscovery.RemoveResourceRequest;
import com.aws.iot.evergreen.ipc.services.servicediscovery.Resource;
import com.aws.iot.evergreen.ipc.services.servicediscovery.ServiceDiscovery;
import com.aws.iot.evergreen.ipc.services.servicediscovery.ServiceDiscoveryImpl;
import com.aws.iot.evergreen.ipc.services.servicediscovery.UpdateResourceRequest;
import com.aws.iot.evergreen.ipc.services.servicediscovery.exceptions.ResourceNotFoundException;
import com.aws.iot.evergreen.ipc.services.servicediscovery.exceptions.ResourceNotOwnedException;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.util.Pair;
import org.hamcrest.collection.IsMapContaining;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static com.aws.iot.evergreen.integrationtests.ipc.IPCTestUtils.TEST_SERVICE_NAME;
import static com.aws.iot.evergreen.integrationtests.ipc.IPCTestUtils.getIPCConfigForService;
import static com.aws.iot.evergreen.integrationtests.ipc.IPCTestUtils.prepareKernelFromConfigFile;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static com.aws.iot.evergreen.testcommons.testutilities.TestUtils.asyncAssertOnConsumer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(EGExtension.class)
class IPCServicesTest {

    @TempDir
    static Path tempRootDir;

    private Kernel kernel;
    private IPCClient client;

    @BeforeEach
    void beforeEach(ExtensionContext context) throws InterruptedException {
        ignoreExceptionWithMessage(context, "Connection reset by peer");
        // Ignore if IPC can't send us more lifecycle updates because the test is already done.
        ignoreExceptionUltimateCauseWithMessage(context, "Channel not found for given connection context");
        kernel = prepareKernelFromConfigFile("ipc.yaml", TEST_SERVICE_NAME, this.getClass());
    }

    @AfterEach
    void afterEach() throws IOException {
        client.disconnect();
        kernel.shutdown();
    }

    @Test
    void registerResourceTest() throws Exception {
        KernelIPCClientConfig config = getIPCConfigForService("mqtt", kernel);
        client = new IPCClientImpl(config);
        ServiceDiscovery c = new ServiceDiscoveryImpl(client);

        KernelIPCClientConfig config2 = getIPCConfigForService(TEST_SERVICE_NAME, kernel);
        IPCClient client2 = new IPCClientImpl(config2);
        try {
            ServiceDiscovery c2 = new ServiceDiscoveryImpl(client2);

            Resource resource = Resource.builder().name("evergreen_2").serviceType("_mqtt").domain("local").build();
            RegisterResourceRequest req = RegisterResourceRequest.builder().resource(resource).build();

            LookupResourceRequest lookup = LookupResourceRequest.builder().resource(resource).build();

            // Register, then lookup
            assertEquals(resource, c.registerResource(req));
            List<Resource> lookupResults = c.lookupResources(lookup);
            assertEquals(1, lookupResults.size());
            assertEquals(resource, lookupResults.get(0));

            // Perform a fuzzy lookup by setting the name to null, so that
            // we're looking it up based on service type only
            LookupResourceRequest fuzzyLookup =
                    LookupResourceRequest.builder().resource(resource.toBuilder().name(null).build()).build();
            lookupResults = c.lookupResources(fuzzyLookup);
            assertEquals(1, lookupResults.size());
            assertEquals(resource, lookupResults.get(0));

            // Try updating the resource
            UpdateResourceRequest updateRequest = UpdateResourceRequest.builder()
                    .resource(resource.toBuilder().uri(new URI("file://ABC.txt")).build()).build();
            c.updateResource(updateRequest);
            assertEquals(updateRequest.getResource().getUri(), c.lookupResources(lookup).get(0).getUri());

            // Try updating the resource (as a different service which isn't allowed)
            assertThrows(ResourceNotOwnedException.class, () -> c2.updateResource(updateRequest));

            // Try removing it (as a different service which isn't allowed)
            RemoveResourceRequest removeRequest = RemoveResourceRequest.builder().resource(resource).build();
            assertThrows(ResourceNotOwnedException.class, () -> c2.removeResource(removeRequest));

            // Try removing a service that doesn't exist
            RemoveResourceRequest removeRequest2 =
                    RemoveResourceRequest.builder().resource(Resource.builder().name("ABC").build()).build();
            assertThrows(ResourceNotFoundException.class, () -> c.removeResource(removeRequest2));

            // Now remove the service properly and check that it is gone
            c.removeResource(removeRequest);
            assertTrue(c.lookupResources(lookup).isEmpty());
        } finally {
            client2.disconnect();
        }
    }

    @Test
    void registerResourcePermissionTest() throws Exception {
        KernelIPCClientConfig config = getIPCConfigForService("ServiceName", kernel);
        client = new IPCClientImpl(config);
        ServiceDiscovery c = new ServiceDiscoveryImpl(client);

        RegisterResourceRequest req = RegisterResourceRequest.builder()
                .resource(Resource.builder().name("evergreen_1" + "._mqtt") // Claimed by mqtt (which our client is not)
                        .build()).build();

        assertThrows(ResourceNotOwnedException.class, () -> c.registerResource(req));
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
            pAbc.getLeft().get(5, TimeUnit.SECONDS);
            pDdf.getLeft().get(5, TimeUnit.SECONDS);
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
        agent.validateConfiguration("ServiceName", Collections.singletonMap("keyToValidate", "valueToValidate"), responseTracker);
        cb.getLeft().get(2, TimeUnit.SECONDS);

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

        assertTrue(configUpdated.await(5, TimeUnit.SECONDS));
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
}
