package com.aws.iot.evergreen.ipc;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.ipc.config.KernelIPCClientConfig;
import com.aws.iot.evergreen.ipc.util.FutureObserver;
import com.aws.iot.evergreen.kernel.Kernel;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.aws.iot.evergreen.ipc.IPCService.KERNEL_URI_ENV_VARIABLE_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IPCTest {

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

        kernel.parseArgs("-r", tdir,
                "-log", "stdout",
                "-i", IPCTest.class.getResource("ipc.yaml").toString()
        );
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
        KernelIPCClientConfig config = KernelIPCClientConfig.builder().hostAddress(address).port(port)
                .token((String) kernel.find("mqtt", "_UID").getOnce()).build();
        IPCClientImpl client = new IPCClientImpl(config);
        ServiceDiscoveryGrpc.ServiceDiscoveryStub c = ServiceDiscoveryGrpc.newStub(client.getChannel());
        ServiceDiscoveryGrpc.ServiceDiscoveryBlockingStub blocking = ServiceDiscoveryGrpc.newBlockingStub(client.getChannel());

        KernelIPCClientConfig config2 = KernelIPCClientConfig.builder().hostAddress(address).port(port)
                .token((String) kernel.find("ServiceName", "_UID").getOnce()).build();
        IPCClientImpl client2 = new IPCClientImpl(config2);
        ServiceDiscoveryGrpc.ServiceDiscoveryBlockingStub c2 = ServiceDiscoveryGrpc.newBlockingStub(client2.getChannel());

        Ipc.Resource resource = Ipc.Resource.newBuilder()
                .setName("evergreen_1")
                .setServiceType("_mqtt")
                .setDomain("local").build();
        Ipc.RegisterResourceRequest req = Ipc.RegisterResourceRequest.newBuilder()
                .setResource(resource)
                .build();

        Ipc.LookupResourcesRequest lookup = Ipc.LookupResourcesRequest.newBuilder()
                .setResource(resource).build();

        // Register, then lookup
        assertEquals(resource, blocking.registerResource(req));

        FutureObserver<Ipc.Resource> observer = new FutureObserver<>();
        c.lookupResources(lookup, observer);
        List<Ipc.Resource> lookupResults = observer.get();
        assertEquals(1, lookupResults.size());
        assertEquals(resource, lookupResults.get(0));

        // Perform a fuzzy lookup by setting the name to null, so that
        // we're looking it up based on service type only
        Ipc.LookupResourcesRequest fuzzyLookup = Ipc.LookupResourcesRequest.newBuilder()
                .setResource(resource.toBuilder().clearName().build()).build();
        observer = new FutureObserver<>();
        c.lookupResources(fuzzyLookup, observer);
        lookupResults = observer.get();
        assertEquals(1, lookupResults.size());
        assertEquals(resource, lookupResults.get(0));

        // Try updating the resource
        Ipc.UpdateResourceRequest updateRequest = Ipc.UpdateResourceRequest.newBuilder()
                .setResource(resource.toBuilder().setUri("file://ABC.txt").build()).build();
        blocking.updateResource(updateRequest);
        observer = new FutureObserver<>();
        c.lookupResources(lookup, observer);
        lookupResults = observer.get();
        assertEquals(updateRequest.getResource().getUri(), lookupResults.get(0).getUri());

        // Try updating the resource (as a different service which isn't allowed)
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, () -> c2.updateResource(updateRequest));
        assertEquals("Service ServiceName is not allowed to update evergreen_1._mqtt._tcp.local", ex.getStatus().getDescription());

        // Try removing it (as a different service which isn't allowed)
        Ipc.RemoveResourceRequest removeRequest = Ipc.RemoveResourceRequest.newBuilder()
                .setResource(resource).build();
        ex = assertThrows(StatusRuntimeException.class, () -> c2.removeResource(removeRequest));
        assertEquals("Service ServiceName is not allowed to remove evergreen_1._mqtt._tcp.local", ex.getStatus().getDescription());

        // Try removing a service that doesn't exist
        Ipc.RemoveResourceRequest removeRequest2 = Ipc.RemoveResourceRequest.newBuilder()
                .setResource(Ipc.Resource.newBuilder().setName("ABC").build()).build();
        ex = assertThrows(StatusRuntimeException.class, () -> blocking.removeResource(removeRequest2));
        assertEquals("ABC._tcp was not found", ex.getStatus().getDescription());

        // Now remove the service properly and check that it is gone
        blocking.removeResource(removeRequest);
        observer = new FutureObserver<>();
        c.lookupResources(lookup, observer);
        lookupResults = observer.get();
        assertTrue(lookupResults.isEmpty());
    }

    @Test
    public void registerResourcePermissionTest() throws Exception {
        KernelIPCClientConfig config = KernelIPCClientConfig.builder().hostAddress(address).port(port)
                .token((String) kernel.find("ServiceName", "_UID").getOnce()).build();
        IPCClientImpl client = new IPCClientImpl(config);
        ServiceDiscoveryGrpc.ServiceDiscoveryBlockingStub c = ServiceDiscoveryGrpc.newBlockingStub(client.getChannel());

        Ipc.RegisterResourceRequest req = Ipc.RegisterResourceRequest.newBuilder()
                .setResource(Ipc.Resource.newBuilder()
                        .setName("evergreen_1._mqtt") // Claimed by mqtt (which our client is not)
                        .setDomain("local")
                        .setServiceProtocol(Ipc.Protocol.TCP)
                        .build())
                .build();

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, () -> c.registerResource(req));
        assertEquals("Service ServiceName is not allowed to register evergreen_1._mqtt._tcp.local", ex.getStatus().getDescription());
    }

    @Test
    public void lifecycleTest() throws Exception {
        KernelIPCClientConfig config = KernelIPCClientConfig.builder().hostAddress(address).port(port)
                .token((String) kernel.find("ServiceName", "_UID").getOnce()).build();
        IPCClientImpl client = new IPCClientImpl(config);
        LifecycleGrpc.LifecycleStub c = LifecycleGrpc.newStub(client.getChannel());
        LifecycleGrpc.LifecycleBlockingStub blocking = LifecycleGrpc.newBlockingStub(client.getChannel());

        CompletableFuture<Void> fut = new CompletableFuture<>();
        CountDownLatch cdl = new CountDownLatch(1);

        c.listenToStateChanges(Ipc.StateChangeListenRequest.newBuilder().setService("ServiceName").build(),
                new StreamObserver<Ipc.StateTransition>() {
                    @Override
                    public void onNext(Ipc.StateTransition stateTransition) {
                        if (stateTransition.getService().equals("")) {
                            cdl.countDown();
                        } else {
                            if (!fut.isDone()) {
                                try {
                                    assertEquals(State.Finished.toString(), stateTransition.getOldState());
                                    assertEquals(State.Errored.toString(), stateTransition.getNewState());
                                    assertEquals("ServiceName", stateTransition.getService());
                                    fut.complete(null);
                                } catch (Throwable e) {
                                    fut.completeExceptionally(e);
                                }
                            }
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        fut.completeExceptionally(throwable);
                    }

                    @Override
                    public void onCompleted() {

                    }
                });

        // Wait for the latch to tell us that we're actually subscribed
        assertTrue(cdl.await(100, TimeUnit.MILLISECONDS));
        blocking.requestStateChange(Ipc.StateChangeRequest.newBuilder().setNewState("Errored").build());
        fut.get(500, TimeUnit.MILLISECONDS);
    }
}
