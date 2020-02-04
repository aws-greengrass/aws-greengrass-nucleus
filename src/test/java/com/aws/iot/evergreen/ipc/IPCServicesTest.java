package com.aws.iot.evergreen.ipc;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.ipc.config.KernelIPCClientConfig;
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
import com.aws.iot.evergreen.util.Pair;
import com.aws.iot.evergreen.util.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static com.aws.iot.evergreen.ipc.IPCService.KERNEL_URI_ENV_VARIABLE_NAME;
import static com.aws.iot.evergreen.ipc.handler.AuthHandler.SERVICE_UNIQUE_ID_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("Integration")
public class IPCServicesTest {

    public static int port;
    public static String address;
    public static Kernel kernel;

    @BeforeAll
    public static void setup() throws Exception {
        // starting daemon
        CountDownLatch OK = new CountDownLatch(1);
        String tdir = System.getProperty("user.home") + "/gg2ktest";
        System.out.println("tdir = " + tdir);
        kernel = new Kernel();

        kernel.setLogWatcher(logline -> {
            if (logline.args.length == 1 && logline.args[0].equals("Run called for IPC service")) {
                OK.countDown();
            }
        });

        kernel.parseArgs("-r", tdir, "-log", "stdout", "-i", IPCServicesTest.class.getResource("ipc.yaml").toString());
        kernel.launch();
        OK.await(10, TimeUnit.SECONDS);
        Topic kernelUri = kernel.lookup("setenv", KERNEL_URI_ENV_VARIABLE_NAME);
        URI serverUri = new URI((String) kernelUri.getOnce());
        port = serverUri.getPort();
        address = serverUri.getHost();
    }

    @AfterAll
    public static void teardown() {
        kernel.shutdown();
    }

    @Test
    public void registerResourceTest() throws Exception {
        KernelIPCClientConfig config =
                KernelIPCClientConfig.builder().hostAddress(address).port(port).token((String) kernel.find("mqtt",
                        SERVICE_UNIQUE_ID_KEY).getOnce()).build();
        IPCClient client = new IPCClientImpl(config);
        ServiceDiscovery c = new ServiceDiscoveryImpl(client);

        KernelIPCClientConfig config2 =
                KernelIPCClientConfig.builder().hostAddress(address).port(port).token((String) kernel.find(
                        "ServiceName", SERVICE_UNIQUE_ID_KEY).getOnce()).build();
        IPCClient client2 = new IPCClientImpl(config2);
        ServiceDiscovery c2 = new ServiceDiscoveryImpl(client2);

        Resource resource = Resource.builder().name("evergreen_1").serviceType("_mqtt").domain("local").build();
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
        lookupResults = c.lookupResources(lookup);
        assertEquals(1, lookupResults.size());
        assertEquals(resource, lookupResults.get(0));

        // Try updating the resource
        UpdateResourceRequest updateRequest =
                UpdateResourceRequest.builder().resource(resource.toBuilder().uri(new URI("file://ABC.txt")).build()).build();
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

        client.disconnect();
        client2.disconnect();
    }

    @Test
    public void registerResourcePermissionTest() throws Exception {
        KernelIPCClientConfig config =
                KernelIPCClientConfig.builder().hostAddress(address).port(port).token((String) kernel.find(
                        "ServiceName", SERVICE_UNIQUE_ID_KEY).getOnce()).build();
        IPCClient client = new IPCClientImpl(config);
        ServiceDiscovery c = new ServiceDiscoveryImpl(client);

        RegisterResourceRequest req = RegisterResourceRequest.builder().resource(Resource.builder().name("evergreen_1" +
                "._mqtt") // Claimed by mqtt (which our client is not)
                .build()).build();

        assertThrows(ResourceNotOwnedException.class, () -> c.registerResource(req));
        client.disconnect();
    }

    @Test
    public void lifecycleTest() throws Exception {
        KernelIPCClientConfig config = KernelIPCClientConfig.builder().hostAddress(address).port(port)
                .token((String) kernel.find("ServiceName", "_UID").getOnce()).build();
        IPCClient client = new IPCClientImpl(config);
        LifecycleImpl c = new LifecycleImpl(client);

        Pair<CompletableFuture<Void>, BiConsumer<String, String>> p = TestUtils.asyncAssertOnBiConsumer((a, b) -> {
            assertEquals(State.Finished.toString(), a);
            assertEquals(State.Errored.toString(), b);
        });

        c.listenToStateChanges("ServiceName", p.getRight());
        c.reportState("Errored");
        p.getLeft().get(500, TimeUnit.MILLISECONDS);
        client.disconnect();
    }
}
