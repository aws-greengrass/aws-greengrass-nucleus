/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.integrationtests.ipc;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.ipc.IPCClient;
import com.aws.iot.evergreen.ipc.IPCClientImpl;
import com.aws.iot.evergreen.ipc.config.KernelIPCClientConfig;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStore;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigStoreImpl;
import com.aws.iot.evergreen.ipc.services.lifecycle.LifecycleImpl;
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
import com.aws.iot.evergreen.testcommons.testutilities.TestUtils;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
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

    private static Kernel kernel;
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
    void GIVEN_ConfigStoreClient_WHEN_subscribe_THEN_key_sent_when_changed() throws Exception {
        KernelIPCClientConfig config = getIPCConfigForService("ServiceName", kernel);
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
        KernelIPCClientConfig config = getIPCConfigForService("ServiceName", kernel);
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
}
