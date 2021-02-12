/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.ipc;

import com.aws.greengrass.config.Subscriber;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentStatusKeeper;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.ipc.IPCEventStreamService;
import com.aws.greengrass.lifecyclemanager.GlobalStateChangeListener;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.testcommons.testutilities.NoOpPathOwnershipHandler;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.util.Coerce;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCClient;
import software.amazon.awssdk.aws.greengrass.model.BinaryMessage;
import software.amazon.awssdk.aws.greengrass.model.DeploymentStatus;
import software.amazon.awssdk.aws.greengrass.model.PublishMessage;
import software.amazon.awssdk.aws.greengrass.model.PublishToTopicRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToTopicRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscriptionResponseMessage;
import software.amazon.awssdk.crt.io.ClientBootstrap;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnectionConfig;
import software.amazon.awssdk.eventstreamrpc.GreengrassConnectMessageSupplier;
import software.amazon.awssdk.eventstreamrpc.StreamResponseHandler;

import java.io.IOException;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.concurrent.TimeoutException;

import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_ID_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_KEY_NAME;
import static com.aws.greengrass.ipc.AuthenticationHandler.SERVICE_UNIQUE_ID_KEY;
import static com.aws.greengrass.ipc.IPCEventStreamService.DEFAULT_PORT_NUMBER;
import static com.aws.greengrass.ipc.IPCEventStreamService.NUCLEUS_DOMAIN_SOCKET_FILEPATH_FOR_COMPONENT;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.*;
import static org.junit.jupiter.api.Assertions.assertTrue;


public final class IPCTestUtils {
    private static final Logger logger = LogManager.getLogger(IPCTestUtils.class);

    public static String TEST_SERVICE_NAME = "ServiceName";
    public static int DEFAULT_IPC_API_TIMEOUT_SECONDS = 3;
    private IPCTestUtils() {

    }

    public static Kernel prepareKernelFromConfigFile(String configFile, Class testClass, String... serviceNames)
            throws InterruptedException, IOException {
        Kernel kernel = new Kernel();
        NoOpPathOwnershipHandler.register(kernel);
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel, testClass.getResource(configFile));
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

    public static EventStreamRPCConnection getEventStreamRpcConnection(Kernel kernel, String serviceName) throws ExecutionException,
            InterruptedException {
        return connectToGGCOverEventStreamIPC(TestUtils.getSocketOptionsForIPC(),
                IPCTestUtils.getAuthTokeForService(kernel, serviceName),
                kernel);
    }

    @SuppressWarnings("PMD.CloseResource")
    public static EventStreamRPCConnection connectToGGCOverEventStreamIPC(SocketOptions socketOptions,
                                                                          String authToken,
                                                                          Kernel kernel) throws ExecutionException,
            InterruptedException {

        try (EventLoopGroup elGroup = new EventLoopGroup(1); ClientBootstrap clientBootstrap = new ClientBootstrap(elGroup, null)) {

            //String ipcServerSocketPath = kernel.getContext().get(IPCEventStreamService.class).getIpcServerSocketPath();
            final String[] ipcServerSocketPath = new String[1];
            kernel.getConfig().getRoot().lookup(SETENV_CONFIG_NAMESPACE, NUCLEUS_DOMAIN_SOCKET_FILEPATH_FOR_COMPONENT)
                    .subscribe(new Subscriber() {
                                   @Override
                                   public void published(WhatHappened what, Topic t) {
                                       ipcServerSocketPath[0] = (String)t.getOnce();
                                   }
                               });

            final EventStreamRPCConnectionConfig config = new EventStreamRPCConnectionConfig(clientBootstrap, elGroup,
                    socketOptions, null, ipcServerSocketPath[0], DEFAULT_PORT_NUMBER,
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

    public static void publishToTopicOverIpcAsBinaryMessage(GreengrassCoreIPCClient ipcClient, String topic,
                                                            String message) throws InterruptedException, ExecutionException, TimeoutException {
        PublishToTopicRequest publishToTopicRequest = new PublishToTopicRequest();
        publishToTopicRequest.setTopic(topic);
        PublishMessage publishMessage = new PublishMessage();
        BinaryMessage binaryMessage = new BinaryMessage();
        binaryMessage.setMessage(message.getBytes(StandardCharsets.UTF_8));
        publishMessage.setBinaryMessage(binaryMessage);
        publishToTopicRequest.setPublishMessage(publishMessage);
        ipcClient.publishToTopic(publishToTopicRequest, Optional.empty()).getResponse().get(5, TimeUnit.SECONDS);
    }

    public static void subscribeToTopicOveripcForBinaryMessages(GreengrassCoreIPCClient ipcClient, String topic,
                                                                Consumer<byte[]> consumer) throws InterruptedException, ExecutionException, TimeoutException {
        SubscribeToTopicRequest request = new SubscribeToTopicRequest();
        request.setTopic(topic);
        ipcClient.subscribeToTopic(request, Optional.of(new StreamResponseHandler<SubscriptionResponseMessage>() {
            @Override
            public void onStreamEvent(SubscriptionResponseMessage streamEvent) {
                consumer.accept(streamEvent.getBinaryMessage().getMessage());
            }

            @Override
            public boolean onStreamError(Throwable error) {
                logger.atError().setCause(error).log("Caught error while subscribing to a topic");
                return false;
            }

            @Override
            public void onStreamClosed() {

            }
        })).getResponse().get(5, TimeUnit.SECONDS);
    }


    public static <T> Optional<StreamResponseHandler<T>> getResponseHandler(Consumer<T> eventConsumer, Logger logger){

        return Optional.of(new StreamResponseHandler<T>() {

            @Override
            public void onStreamEvent(T streamEvent) {
                eventConsumer.accept(streamEvent);
            }

            @Override
            public boolean onStreamError(Throwable error) {
                logger.atError().log("Received a stream error", error);
                return false;
            }

            @Override
            public void onStreamClosed() {

            }
        });
    }

}
