/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.ipc;

import com.aws.greengrass.authorization.AuthorizationModule;
import com.aws.greengrass.authorization.Permission;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.UpdateBehaviorTree;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.logging.impl.Slf4jLogAdapter;
import com.aws.greengrass.logging.impl.config.LogConfig;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.testcommons.testutilities.UniqueRootPathExtension;
import com.aws.greengrass.util.Pair;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.event.Level;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCClient;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToTopicRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToTopicResponse;
import software.amazon.awssdk.aws.greengrass.model.SubscriptionResponseMessage;
import software.amazon.awssdk.aws.greengrass.model.UnauthorizedError;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;
import software.amazon.awssdk.eventstreamrpc.StreamResponseHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.prepareKernelFromConfigFile;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.publishToTopicOverIpcAsBinaryMessage;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.subscribeToTopicOveripcForBinaryMessages;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.ACCESS_CONTROL_NAMESPACE_TOPIC;
import static com.aws.greengrass.tes.TokenExchangeService.TOKEN_EXCHANGE_SERVICE_TOPICS;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static com.aws.greengrass.testcommons.testutilities.TestUtils.asyncAssertOnConsumer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith({GGExtension.class, UniqueRootPathExtension.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IPCPubSubTest extends BaseITCase {
    private static final Logger logger = LogManager.getLogger(IPCPubSubTest.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static int TIMEOUT_FOR_PUBSUB_SECONDS = 2;
    private static Kernel kernel;
    public static Permission TES_DEFAULT_POLICY =
            Permission.builder().principal("*").operation("getCredentials").resource(null).build();
    private static final String newAclStr =
            "{  \n" +
            "   \"aws.greengrass.ipc.pubsub\":\n" +
            "        {\n" +
            "          \"policyId10\":{\n" +
            "            \"policyDescription\":\"all access to pubsub topics for ServiceName\",\n" +
            "            \"operations\":[\n" +
            "              \"*\"\n" +
            "            ],\n" +
            "            \"resources\":[\n" +
            "              \"*\"\n" +
            "            ]\n" +
            "          }\n" +
            "        }\n" +
            "}";
    private static final String oldAclStr =
            "{  \n" +
            "   \"aws.greengrass.ipc.pubsub\":\n" +
            "        {\n" +
            "          \"policyId4\":{\n" +
            "            \"policyDescription\":\"publish access to pubsub topics for ServiceName\",\n" +
            "            \"operations\":[\n" +
            "              \"aws.greengrass#PublishToTopic\"\n" +
            "            ],\n" +
            "            \"resources\":[\n" +
            "              \"/topic/1/#\",\n" +
            "              \"/longer/topic/example/\",\n" +
            "              \"*\"\n" +
            "            ]\n" +
            "          }\n" +
            "        }\n" +
            "}";

    @BeforeAll
    static void beforeEach(ExtensionContext context) throws InterruptedException, IOException {
        ignoreExceptionOfType(context, InterruptedException.class);
        ignoreExceptionWithMessage(context, "Connection reset by peer");
        // Ignore if IPC can't send us more lifecycle updates because the test is already done.
        ignoreExceptionUltimateCauseWithMessage(context, "Channel not found for given connection context");
        kernel = prepareKernelFromConfigFile("pubsub.yaml", IPCPubSubTest.class, "SubscribeAndPublish");
    }

    @AfterAll
    static void stopKernel() {
        kernel.shutdown();
    }

    @AfterEach
    void afterEach() {
        LogConfig.getRootLogConfig().reset();
    }

    @Test
    void GIVEN_pubsubclient_WHEN_subscribe_and_publish_is_authorized_THEN_succeeds() throws Exception {
        try(EventStreamRPCConnection connection = IPCTestUtils.getEventStreamRpcConnection(kernel,
                "SubscribeAndPublish")) {
            GreengrassCoreIPCClient ipcClient = new GreengrassCoreIPCClient(connection);

            Pair<CompletableFuture<Void>, Consumer<byte[]>> cb = asyncAssertOnConsumer((m) -> {
                assertEquals("some message", new String(m, StandardCharsets.UTF_8));
            });
            subscribeToTopicOveripcForBinaryMessages(ipcClient, "a", cb.getRight());
            publishToTopicOverIpcAsBinaryMessage(ipcClient, "a", "some message");
            cb.getLeft().get(TIMEOUT_FOR_PUBSUB_SECONDS, TimeUnit.SECONDS);
        }
    }


    @Test
    void GIVEN_pubsubclient_WHEN_subscribe_is_not_authorized_THEN_Fail() throws Exception {
        try(EventStreamRPCConnection connection = IPCTestUtils.getEventStreamRpcConnection(kernel,
                "PublishNotSubscribe")) {
            GreengrassCoreIPCClient ipcClient = new GreengrassCoreIPCClient(connection);

            Pair<CompletableFuture<Void>, Consumer<byte[]>> cb = asyncAssertOnConsumer((m) -> {
                assertEquals("some message", new String(m, StandardCharsets.UTF_8));
            });
            ExecutionException executionException = assertThrows(ExecutionException.class,
                    () -> subscribeToTopicOveripcForBinaryMessages(ipcClient, "a", cb.getRight()));
            assertTrue(executionException.getCause() instanceof UnauthorizedError);
        }
    }

    @Test
    void GIVEN_pubsubclient_WHEN_publish_is_not_authorized_THEN_Fail() throws Exception {
        try(EventStreamRPCConnection connection = IPCTestUtils.getEventStreamRpcConnection(kernel,
                "SubscribeNotPublish")) {
            GreengrassCoreIPCClient ipcClient = new GreengrassCoreIPCClient(connection);
            ExecutionException executionException1 = assertThrows(ExecutionException.class,
                    () -> publishToTopicOverIpcAsBinaryMessage(ipcClient, "a", "some message"));
            assertTrue(executionException1.getCause() instanceof UnauthorizedError);
        }
    }

    @Test
    @Order(1)
    void GIVEN_pubsubclient_WHEN_subscribe_authorization_changes_to_authorized_THEN_succeeds() throws Exception {
        LogConfig.getRootLogConfig().setLevel(Level.DEBUG);
        try(EventStreamRPCConnection connection = IPCTestUtils.getEventStreamRpcConnection(kernel,
                "OnlyPublish")) {
            GreengrassCoreIPCClient ipcClient = new GreengrassCoreIPCClient(connection);

            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS,
                    TES_DEFAULT_POLICY));

            Pair<CompletableFuture<Void>, Consumer<byte[]>> cb = asyncAssertOnConsumer((m) -> {
                assertEquals("some message", new String(m, StandardCharsets.UTF_8));
            });
            publishToTopicOverIpcAsBinaryMessage(ipcClient, "a", "some message");

            ExecutionException executionException = assertThrows(ExecutionException.class,
                    () -> subscribeToTopicOveripcForBinaryMessages(ipcClient, "a", cb.getRight()));
            assertTrue(executionException.getCause() instanceof UnauthorizedError);

            Topics aclTopic = kernel.findServiceTopic("OnlyPublish").findTopics(CONFIGURATION_CONFIG_KEY, ACCESS_CONTROL_NAMESPACE_TOPIC);
            Map<String, Object> newAcl = OBJECT_MAPPER.readValue(newAclStr, new TypeReference<Map<String, Object>>(){});
            aclTopic.updateFromMap(newAcl,
                    new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.REPLACE, System.currentTimeMillis()));
            //Block until events are completed
            kernel.getContext().waitForPublishQueueToClear();

            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS,
                    TES_DEFAULT_POLICY));

            subscribeToTopicOveripcForBinaryMessages(ipcClient, "a", cb.getRight());//now this should succeed
            publishToTopicOverIpcAsBinaryMessage(ipcClient, "a", "some message");

            cb.getLeft().get(TIMEOUT_FOR_PUBSUB_SECONDS, TimeUnit.SECONDS);

            aclTopic = kernel.findServiceTopic("OnlyPublish").findTopics(CONFIGURATION_CONFIG_KEY, ACCESS_CONTROL_NAMESPACE_TOPIC);
            Map<String, Object> oldAcl = OBJECT_MAPPER.readValue(oldAclStr, new TypeReference<Map<String, Object>>(){});
            aclTopic.updateFromMap(oldAcl,
                    new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.REPLACE, System.currentTimeMillis()));
            //Block until events are completed
            kernel.getContext().runOnPublishQueueAndWait(() -> {
            });
        }
    }


    @Test
    @SuppressWarnings({"PMD.AvoidCatchingGenericException"})
    void GIVEN_PubSubEventStreamClient_WHEN_subscribe_and_unsubscribe_THEN_publishes_only_once() throws Exception {
        LogConfig.getRootLogConfig().setLevel(Level.DEBUG);
        String topicName = "topicName";
        SubscribeToTopicRequest subscribeToTopicRequest = new SubscribeToTopicRequest();
        subscribeToTopicRequest.setTopic(topicName);
        CountDownLatch cdl = new CountDownLatch(1);
        AtomicInteger atomicInteger = new AtomicInteger();

        CountDownLatch subscriptionLatch = new CountDownLatch(1);

        String authToken = IPCTestUtils.getAuthTokeForService(kernel, "SubscribeAndPublish");
        SocketOptions socketOptions = TestUtils.getSocketOptionsForIPC();
        try (EventStreamRPCConnection clientConnection =
                     IPCTestUtils.connectToGGCOverEventStreamIPC(socketOptions, authToken, kernel);
            AutoCloseable l = TestUtils.createCloseableLogListener(m -> {
                if (m.getMessage().contains("Subscribed to topic")) {
                    subscriptionLatch.countDown();
                }
            })){
            GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);
            CompletableFuture<SubscribeToTopicResponse> fut =
                    greengrassCoreIPCClient.subscribeToTopic(subscribeToTopicRequest,
                            Optional.of(new StreamResponseHandler<SubscriptionResponseMessage>() {
                                @Override
                                public void onStreamEvent(SubscriptionResponseMessage message) {
                                    assertNotNull(message.getBinaryMessage());
                                    assertNull(message.getJsonMessage());
                                    assertEquals("ABCDEFG", new String(message.getBinaryMessage().getMessage()));
                                    atomicInteger.incrementAndGet();
                                    cdl.countDown();
                                }

                                @Override
                                public boolean onStreamError(Throwable error) {
                                    logger.atError().log("Received a stream error", error);
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
            assertTrue(subscriptionLatch.await(10, TimeUnit.SECONDS));

            publishToTopicOverIpcAsBinaryMessage(greengrassCoreIPCClient, topicName, "ABCDEFG");
            assertTrue(cdl.await(20, TimeUnit.SECONDS));
        }
    }

    @Test
    @SuppressWarnings({"PMD.AvoidCatchingGenericException"})
    void GIVEN_PubSubEventStreamClient_WHEN_subscribe_to_wildcard_THEN_publishes_to_subtopic_only_once() throws Exception {
        LogConfig.getRootLogConfig().setLevel(Level.DEBUG);
        String topicName = "/topic/1/+";
        SubscribeToTopicRequest subscribeToTopicRequest = new SubscribeToTopicRequest();
        subscribeToTopicRequest.setTopic(topicName);
        CountDownLatch cdl = new CountDownLatch(1);
        AtomicInteger atomicInteger = new AtomicInteger();

        CountDownLatch subscriptionLatch = new CountDownLatch(1);

        // Allowed resource /to*/#
        String authToken = IPCTestUtils.getAuthTokeForService(kernel, "SubscribeAndPublishWildcard");
        SocketOptions socketOptions = TestUtils.getSocketOptionsForIPC();
        try (EventStreamRPCConnection clientConnection =
                     IPCTestUtils.connectToGGCOverEventStreamIPC(socketOptions, authToken, kernel);
             AutoCloseable l = TestUtils.createCloseableLogListener(m -> {
                 if (m.getMessage().contains("Subscribed to topic")) {
                     subscriptionLatch.countDown();
                 }
             })){
            GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);
            CompletableFuture<SubscribeToTopicResponse> fut =
                    greengrassCoreIPCClient.subscribeToTopic(subscribeToTopicRequest,
                            Optional.of(new StreamResponseHandler<SubscriptionResponseMessage>() {
                                @Override
                                public void onStreamEvent(SubscriptionResponseMessage message) {
                                    assertNotNull(message.getBinaryMessage());
                                    assertNull(message.getJsonMessage());
                                    assertEquals("ABCDEFG", new String(message.getBinaryMessage().getMessage()));
                                    atomicInteger.incrementAndGet();
                                    cdl.countDown();
                                }

                                @Override
                                public boolean onStreamError(Throwable error) {
                                    logger.atError().log("Received a stream error", error);
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
            assertTrue(subscriptionLatch.await(10, TimeUnit.SECONDS));

            publishToTopicOverIpcAsBinaryMessage(greengrassCoreIPCClient, "/topic/1/2", "ABCDEFG");
            assertTrue(cdl.await(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void GIVEN_PubSubEventStreamClient_WHEN_subscribe_wildcard_is_not_authorized_THEN_Fail() throws Exception {
        String topicName = "topicName/#";
        SubscribeToTopicRequest subscribeToTopicRequest = new SubscribeToTopicRequest();
        subscribeToTopicRequest.setTopic(topicName);

        // Allowed resource /to*/#
        String authToken = IPCTestUtils.getAuthTokeForService(kernel, "SubscribeAndPublishWildcard");
        SocketOptions socketOptions = TestUtils.getSocketOptionsForIPC();
        try (EventStreamRPCConnection clientConnection =
                     IPCTestUtils.connectToGGCOverEventStreamIPC(socketOptions, authToken, kernel)) {
            GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);
            ExecutionException executionException = assertThrows(ExecutionException.class, () ->
                    greengrassCoreIPCClient.subscribeToTopic(subscribeToTopicRequest,
                            getOptionalStreamResponseHandler()).getResponse().get());

            assertTrue(executionException.getCause() instanceof UnauthorizedError);
            UnauthorizedError unauthorizedError = (UnauthorizedError) executionException.getCause();
            assertEquals("Principal SubscribeAndPublishWildcard is not authorized to perform aws.greengrass.ipc"
                            + ".pubsub:aws.greengrass#SubscribeToTopic on resource topicName/#",
                    unauthorizedError.getMessage());
        }
    }

    @Test
    void GIVEN_pubsubclient_with_event_stream_WHEN_subscribe_is_not_authorized_THEN_Fail() throws Exception {
        String topicName = "topicName";
        SubscribeToTopicRequest subscribeToTopicRequest = new SubscribeToTopicRequest();
        subscribeToTopicRequest.setTopic(topicName);

        String authToken = IPCTestUtils.getAuthTokeForService(kernel, "PublishNotSubscribe");
        SocketOptions socketOptions = TestUtils.getSocketOptionsForIPC();
        try (EventStreamRPCConnection clientConnection =
                     IPCTestUtils.connectToGGCOverEventStreamIPC(socketOptions, authToken, kernel)) {
            GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);
            ExecutionException executionException = assertThrows(ExecutionException.class, () ->
                    greengrassCoreIPCClient.subscribeToTopic(subscribeToTopicRequest,
                            getOptionalStreamResponseHandler()).getResponse().get());

            assertTrue(executionException.getCause() instanceof UnauthorizedError);
            UnauthorizedError unauthorizedError = (UnauthorizedError) executionException.getCause();
            assertEquals("Principal PublishNotSubscribe is not authorized to perform aws.greengrass.ipc.pubsub:aws.greengrass#SubscribeToTopic on resource topicName",
                    unauthorizedError.getMessage());
        }
    }

    @Test
    void GIVEN_pubsubclient_with_event_stream_WHEN_publish_is_not_authorized_THEN_Fail() throws Exception {
        String authToken = IPCTestUtils.getAuthTokeForService(kernel, "SubscribeNotPublish");
        SocketOptions socketOptions = TestUtils.getSocketOptionsForIPC();
        try (EventStreamRPCConnection clientConnection =
                     IPCTestUtils.connectToGGCOverEventStreamIPC(socketOptions, authToken, kernel)) {
            GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);

            String topicName = "topicName";
            ExecutionException executionException = assertThrows(ExecutionException.class, () ->
                    publishToTopicOverIpcAsBinaryMessage(greengrassCoreIPCClient, topicName, "ABCDEFG"));
            assertTrue(executionException.getCause() instanceof UnauthorizedError);
            UnauthorizedError unauthorizedError = (UnauthorizedError) executionException.getCause();
            assertEquals("Principal SubscribeNotPublish is not authorized to perform aws.greengrass.ipc.pubsub:aws" +
                            ".greengrass#PublishToTopic on resource topicName",
                    unauthorizedError.getMessage());
        }
    }

    @SuppressWarnings({"PMD.AvoidCatchingGenericException"})
    @Test
    void GIVEN_pubsubclient_with_event_stream_WHEN_subscribe_authorization_changes_to_authorized_THEN_succeeds() throws Exception {
        LogConfig.getRootLogConfig().setLevel(Level.DEBUG);
        String topicName = "topicName";
        SubscribeToTopicRequest subscribeToTopicRequest = new SubscribeToTopicRequest();
        subscribeToTopicRequest.setTopic(topicName);
        CountDownLatch subscriptionLatch = new CountDownLatch(1);
        Slf4jLogAdapter.addGlobalListener(m -> {
            if (m.getMessage().contains("Subscribed to topic")) {
                subscriptionLatch.countDown();
            }
        });

        String authToken = IPCTestUtils.getAuthTokeForService(kernel, "OnlyPublish");
        SocketOptions socketOptions = TestUtils.getSocketOptionsForIPC();
        try (EventStreamRPCConnection clientConnection =
                     IPCTestUtils.connectToGGCOverEventStreamIPC(socketOptions, authToken, kernel)) {
            GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);

            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS,
                    TES_DEFAULT_POLICY));
            CompletableFuture<SubscribeToTopicResponse> fut =
                    greengrassCoreIPCClient.subscribeToTopic(subscribeToTopicRequest,
                            getOptionalStreamResponseHandler()).getResponse();
            ExecutionException executionException = assertThrows(ExecutionException.class, () ->
                    fut.get(3, TimeUnit.SECONDS));
            assertTrue(executionException.getCause() instanceof UnauthorizedError);
            UnauthorizedError unauthorizedError = (UnauthorizedError) executionException.getCause();
            assertEquals("Principal OnlyPublish is not authorized to perform aws.greengrass.ipc.pubsub:aws.greengrass#SubscribeToTopic on resource topicName",
                    unauthorizedError.getMessage());

        }
        Topics aclTopic = kernel.findServiceTopic("OnlyPublish").findTopics(CONFIGURATION_CONFIG_KEY, ACCESS_CONTROL_NAMESPACE_TOPIC);
        Map<String, Object> newAcl = OBJECT_MAPPER.readValue(newAclStr, new TypeReference<Map<String, Object>>(){});
        aclTopic.updateFromMap(newAcl,
                new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.REPLACE, System.currentTimeMillis()));
        //Block until events are completed
        kernel.getContext().waitForPublishQueueToClear();
        assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS,
                TES_DEFAULT_POLICY));

        try (EventStreamRPCConnection clientConnection =
                     IPCTestUtils.connectToGGCOverEventStreamIPC(socketOptions, authToken, kernel)) {
            GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);

            CompletableFuture<SubscribeToTopicResponse> fut =
                    greengrassCoreIPCClient.subscribeToTopic(subscribeToTopicRequest,
                            getOptionalStreamResponseHandler()).getResponse();
            try {
                fut.get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.atError().setCause(e).log("Error when subscribing to component updates");
                fail("Caught exception when subscribing to component updates");
            }
            assertTrue(subscriptionLatch.await(10, TimeUnit.SECONDS));
        }

        aclTopic = kernel.findServiceTopic("OnlyPublish").findTopics(CONFIGURATION_CONFIG_KEY, ACCESS_CONTROL_NAMESPACE_TOPIC);
        Map<String, Object> oldAcl = OBJECT_MAPPER.readValue(oldAclStr, new TypeReference<Map<String, Object>>(){});
        aclTopic.updateFromMap(oldAcl,
                new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.REPLACE, System.currentTimeMillis()));
        //Block until events are completed
        kernel.getContext().runOnPublishQueueAndWait(() -> {
        });

    }

    private Optional<StreamResponseHandler<SubscriptionResponseMessage>> getOptionalStreamResponseHandler() {
        return Optional.of(new StreamResponseHandler<SubscriptionResponseMessage>() {
            @Override
            public void onStreamEvent(SubscriptionResponseMessage message) {
                //NA
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
