/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.integrationtests.ipc;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.ipc.IPCClient;
import com.aws.iot.evergreen.ipc.IPCClientImpl;
import com.aws.iot.evergreen.ipc.config.KernelIPCClientConfig;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStore;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStoreImpl;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleImpl;
import com.aws.iot.evergreen.ipc.services.pubsub.PubSub;
import com.aws.iot.evergreen.ipc.services.pubsub.PubSubImpl;
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
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.testcommons.testutilities.TestUtils;
import com.aws.iot.evergreen.util.Pair;
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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static com.aws.iot.evergreen.ipc.AuthHandler.SERVICE_UNIQUE_ID_KEY;
import static com.aws.iot.evergreen.ipc.IPCService.KERNEL_URI_ENV_VARIABLE_NAME;
import static com.aws.iot.evergreen.kernel.EvergreenService.SETENV_CONFIG_NAMESPACE;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
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

    private static int port;
    private static String address;
    private static Kernel kernel;
    private IPCClient client;

    @BeforeEach
    void beforeEach(ExtensionContext context) {
        ignoreExceptionWithMessage(context, "Connection reset by peer");
        // Ignore if IPC can't send us more lifecycle updates because the test is already done.
        ignoreExceptionUltimateCauseWithMessage(context, "Channel not found for given connection context");
    }

    @BeforeAll
    static void startKernel() throws Exception {
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());

        kernel = new Kernel();
        kernel.parseArgs("-i", IPCServicesTest.class.getResource("ipc.yaml").toString());

        // ensure awaitIpcServiceLatch starts
        CountDownLatch awaitIpcServiceLatch = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("ServiceName") && newState.equals(State.RUNNING)) {
                awaitIpcServiceLatch.countDown();
            }
        });

        kernel.launch();

        assertTrue(awaitIpcServiceLatch.await(10, TimeUnit.SECONDS));

        Topic kernelUri = kernel.getConfig().getRoot().lookup(SETENV_CONFIG_NAMESPACE, KERNEL_URI_ENV_VARIABLE_NAME);
        URI serverUri = new URI((String) kernelUri.getOnce());
        port = serverUri.getPort();
        address = serverUri.getHost();
    }

    @AfterAll
    static void teardown() {
        kernel.shutdown();
    }

    @AfterEach
    void afterEach() throws IOException {
        client.disconnect();
    }

    @Test
    void registerResourceTest() throws Exception {
        KernelIPCClientConfig config = getIPCConfigForService("mqtt");
        client = new IPCClientImpl(config);
        ServiceDiscovery c = new ServiceDiscoveryImpl(client);

        KernelIPCClientConfig config2 = getIPCConfigForService("ServiceName");
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
        KernelIPCClientConfig config = getIPCConfigForService("ServiceName");
        client = new IPCClientImpl(config);
        ServiceDiscovery c = new ServiceDiscoveryImpl(client);

        RegisterResourceRequest req = RegisterResourceRequest.builder()
                .resource(Resource.builder().name("evergreen_1" + "._mqtt") // Claimed by mqtt (which our client is not)
                        .build()).build();

        assertThrows(ResourceNotOwnedException.class, () -> c.registerResource(req));
    }

    @Test
    void lifecycleTest() throws Exception {
        KernelIPCClientConfig config = getIPCConfigForService("ServiceName");
        client = new IPCClientImpl(config);
        LifecycleImpl c = new LifecycleImpl(client);

        Pair<CompletableFuture<Void>, BiConsumer<String, String>> p = TestUtils.asyncAssertOnBiConsumer((a, b) -> {
            assertEquals(State.RUNNING.toString(), a);
            assertEquals(State.ERRORED.toString(), b);
        });

        c.listenToStateChanges("ServiceName", p.getRight());
        c.reportState("ERRORED");
        p.getLeft().get(500, TimeUnit.MILLISECONDS);
    }

    @Test
    void GIVEN_ConfigStoreClient_WHEN_subscribe_THEN_key_sent_when_changed() throws Exception {
        KernelIPCClientConfig config = getIPCConfigForService("ServiceName");
        client = new IPCClientImpl(config);
        ConfigStore c = new ConfigStoreImpl(client);

        Topics custom = kernel.findServiceTopic("ServiceName").createInteriorChild(PARAMETERS_CONFIG_KEY);

        AtomicInteger numCalls = new AtomicInteger();
        Pair<CompletableFuture<Void>, Consumer<String>> p = asyncAssertOnConsumer((a) -> {
            int callNum = numCalls.incrementAndGet();

            if (callNum == 1) {
                assertThat(a, is("abc"));
            } else if (callNum == 2) {
                assertThat(a, is("DDF"));
            }
        }, 2);

        c.subscribe(p.getRight());
        custom.createLeafChild("abc").withValue("ABC");
        custom.createLeafChild("DDF").withValue("ddf");

        try {
            p.getLeft().get(1, TimeUnit.SECONDS);
        } finally {
            custom.remove();
        }
    }

    @Test
    void GIVEN_ConfigStoreClient_WHEN_read_THEN_value_returned() throws Exception {
        KernelIPCClientConfig config = getIPCConfigForService("ServiceName");
        client = new IPCClientImpl(config);
        ConfigStore c = new ConfigStoreImpl(client);

        Topics custom = kernel.findServiceTopic("ServiceName").createInteriorChild(PARAMETERS_CONFIG_KEY);
        custom.createLeafChild("abc").withValue("ABC");
        custom.createInteriorChild("DDF").createLeafChild("A").withValue("C");

        try {
            // Can read individual value
            assertEquals("ABC", c.read("abc"));

            // Can read nested values
            Map<String, Object> val = (Map<String, Object>) c.read("DDF");
            assertThat(val, aMapWithSize(1));
            assertThat(val, IsMapContaining.hasKey("A"));
            assertThat(val.get("A"), is("C"));
        } finally {
            custom.remove();
        }
    }

    @Test
    void GIVEN_pubsubclient_WHEN_subscribe_and_publish_THEN_called_with_message() throws Exception {
        KernelIPCClientConfig config = getIPCConfigForService("ServiceName");
        client = new IPCClientImpl(config);
        PubSub c = new PubSubImpl(client);
        IPCClientImpl client2 = new IPCClientImpl(config);
        try {
            PubSub c2 = new PubSubImpl(client2);

            Pair<CompletableFuture<Void>, Consumer<byte[]>> cb = asyncAssertOnConsumer((m) -> {
                assertEquals("some message", new String(m, StandardCharsets.UTF_8));
            });
            c.subscribeToTopic("a", cb.getRight());
            c.publishToTopic("a", "some message".getBytes(StandardCharsets.UTF_8));
            cb.getLeft().get(2, TimeUnit.SECONDS);

            // Now unsubscribe and make sure that we only got the first message in the first client
            c.unsubscribeFromTopic("a");
            Pair<CompletableFuture<Void>, Consumer<byte[]>> cb2 = asyncAssertOnConsumer((m) -> {
                assertEquals("second message", new String(m, StandardCharsets.UTF_8));
            });
            c2.subscribeToTopic("a", cb2.getRight());
            c2.publishToTopic("a", "second message".getBytes(StandardCharsets.UTF_8));
            cb2.getLeft().get(2, TimeUnit.SECONDS);
            cb.getLeft().get(2, TimeUnit.SECONDS);
        } finally {
            client2.disconnect();
        }
    }

    private KernelIPCClientConfig getIPCConfigForService(String serviceName) throws ServiceLoadException {
        return KernelIPCClientConfig.builder().hostAddress(address).port(port)
                .token((String) kernel.locate(serviceName).getRuntimeConfig().findLeafChild(SERVICE_UNIQUE_ID_KEY)
                        .getOnce()).build();
    }
}
