/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.ipc;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentStatusKeeper;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.ipc.IPCEventStreamService;
import com.aws.greengrass.ipc.config.KernelIPCClientConfig;
import com.aws.greengrass.ipc.services.cli.models.DeploymentStatus;
import com.aws.greengrass.lifecyclemanager.GlobalStateChangeListener;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.util.Coerce;
import software.amazon.awssdk.crt.io.ClientBootstrap;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnectionConfig;
import software.amazon.awssdk.eventstreamrpc.GreengrassConnectMessageSupplier;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_ID_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_KEY_NAME;
import static com.aws.greengrass.ipc.AuthenticationHandler.SERVICE_UNIQUE_ID_KEY;
import static com.aws.greengrass.ipc.IPCEventStreamService.DEFAULT_PORT_NUMBER;
import static com.aws.greengrass.ipc.IPCService.KERNEL_URI_ENV_VARIABLE_NAME;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.PRIVATE_STORE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SETENV_CONFIG_NAMESPACE;
import static org.junit.jupiter.api.Assertions.assertTrue;


public final class IPCTestUtils {

    public static String TEST_SERVICE_NAME = "ServiceName";
    private IPCTestUtils() {

    }

    public static KernelIPCClientConfig getIPCConfigForService(String serviceName, Kernel kernel) throws ServiceLoadException, URISyntaxException {
        Topic kernelUri = kernel.getConfig().getRoot().lookup(SETENV_CONFIG_NAMESPACE, KERNEL_URI_ENV_VARIABLE_NAME);
        URI serverUri = null;
        serverUri = new URI((String) kernelUri.getOnce());

        int port = serverUri.getPort();
        String address = serverUri.getHost();

        return KernelIPCClientConfig.builder().hostAddress(address).port(port)
                .token(Coerce.toString(kernel.locate(serviceName).getPrivateConfig().findLeafChild(SERVICE_UNIQUE_ID_KEY)
                        .getOnce())).build();
    }

    public static Kernel prepareKernelFromConfigFile(String configFile, Class testClass, String... serviceNames) throws InterruptedException {
        Kernel kernel = new Kernel();
        kernel.parseArgs("-i", testClass.getResource(configFile).toString());

        // ensure awaitIpcServiceLatch starts
        CountDownLatch awaitIpcServiceLatch = new CountDownLatch(serviceNames.length);
        GlobalStateChangeListener listener = getListenerForServiceRunning(awaitIpcServiceLatch, serviceNames);
        kernel.getContext().addGlobalStateChangeListener(listener);

        kernel.launch();
        assertTrue(awaitIpcServiceLatch.await(10, TimeUnit.SECONDS));
        kernel.getContext().removeGlobalStateChangeListener(listener);
        return kernel;
    }

    public static GlobalStateChangeListener getListenerForServiceRunning(CountDownLatch countDownLatch,
                                                                         String... serviceNames) {
        return (service, oldState, newState) -> {
            if (serviceNames != null && serviceNames.length != 0) {
                for (String serviceName:serviceNames) {
                    if (service.getName().equals(serviceName) && newState.equals(State.RUNNING)) {
                        countDownLatch.countDown();
                        break;
                    }
                }
            }
        };
    }

    public static CountDownLatch waitForDeploymentToBeSuccessful(String deploymentId, Kernel kernel) {
        CountDownLatch deploymentLatch = new CountDownLatch(1);
        DeploymentStatusKeeper deploymentStatusKeeper = kernel.getContext().get(DeploymentStatusKeeper.class);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(Deployment.DeploymentType.LOCAL, (deploymentDetails) ->
        {
            String receivedDeploymentId =
                    deploymentDetails.get(DEPLOYMENT_ID_KEY_NAME).toString();
            if (receivedDeploymentId.equals(deploymentId)) {
                DeploymentStatus status = DeploymentStatus.valueOf(deploymentDetails
                        .get(DEPLOYMENT_STATUS_KEY_NAME).toString());
                if (status == DeploymentStatus.SUCCEEDED) {
                    deploymentLatch.countDown();
                }
            }
            return true;
        }, deploymentId);
        return deploymentLatch;
    }

    public static CountDownLatch waitForServiceToComeInState(String serviceName, State state, Kernel kernel) {
        // wait for service to come up
        CountDownLatch awaitServiceLatch = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals(serviceName) && newState.equals(state)) {
                awaitServiceLatch.countDown();
            }
        });
        return awaitServiceLatch;
    }


    @SuppressWarnings("PMD.CloseResource")
    public static EventStreamRPCConnection connectToGGCOverEventStreamIPC(SocketOptions socketOptions,
                                                                          String authToken,
                                                                          Kernel kernel) throws ExecutionException,
            InterruptedException {

        try (EventLoopGroup elGroup = new EventLoopGroup(1); ClientBootstrap clientBootstrap = new ClientBootstrap(elGroup, null)) {

            String ipcServerSocketPath = kernel.getContext().get(IPCEventStreamService.class).getIpcServerSocketAbsolutePath();
            final EventStreamRPCConnectionConfig config = new EventStreamRPCConnectionConfig(clientBootstrap, elGroup,
                    socketOptions, null, ipcServerSocketPath, DEFAULT_PORT_NUMBER,
                    GreengrassConnectMessageSupplier.connectMessageSupplier(authToken));
            final CompletableFuture<Void> connected = new CompletableFuture<>();
            final EventStreamRPCConnection connection = new EventStreamRPCConnection(config);
            final boolean disconnected[] = {false};
            final int disconnectedCode[] = {-1};
            //this is a bit cumbersome but does not prevent a convenience wrapper from exposing a sync
            //connect() or a connect() that returns a CompletableFuture that errors
            //this could be wrapped by utility methods to provide a more
            connection.connect(new EventStreamRPCConnection.LifecycleHandler() {
                //only called on successful connection. That is full on Connect -> ConnectAck(ConnectionAccepted=true)
                @Override
                public void onConnect() {
                    connected.complete(null);
                }

                @Override
                public void onDisconnect(int errorCode) {
                    disconnected[0] = true;
                    disconnectedCode[0] = errorCode;
                }

                //This on error is for any errors that is connection level, including problems during connect()
                @Override
                public boolean onError(Throwable t) {
                    connected.completeExceptionally(t);
                    return true;    //hints at handler to disconnect due to this error
                }
            });
            connected.get();
            return connection;
        }
    }

    public static String getAuthTokeForService(Kernel kernel, String serviceName) {
        Topics servicePrivateConfig = kernel.getConfig().findTopics(SERVICES_NAMESPACE_TOPIC, serviceName,
                PRIVATE_STORE_NAMESPACE_TOPIC);
        return  Coerce.toString(servicePrivateConfig.find(SERVICE_UNIQUE_ID_KEY));
    }
}
