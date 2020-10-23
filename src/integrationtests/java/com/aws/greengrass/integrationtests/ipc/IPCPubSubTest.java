/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.greengrass.integrationtests.ipc;

import com.aws.greengrass.authorization.AuthorizationModule;
import com.aws.greengrass.authorization.Permission;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.ipc.IPCClient;
import com.aws.greengrass.ipc.IPCClientImpl;
import com.aws.greengrass.ipc.config.KernelIPCClientConfig;
import com.aws.greengrass.ipc.services.pubsub.PubSub;
import com.aws.greengrass.ipc.services.pubsub.PubSubException;
import com.aws.greengrass.ipc.services.pubsub.PubSubImpl;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.logging.impl.Slf4jLogAdapter;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.util.Pair;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCClient;
import software.amazon.awssdk.aws.greengrass.model.BinaryMessage;
import software.amazon.awssdk.aws.greengrass.model.PublishMessage;
import software.amazon.awssdk.aws.greengrass.model.PublishToTopicRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToTopicRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToTopicResponse;
import software.amazon.awssdk.aws.greengrass.model.SubscriptionResponseMessage;
import software.amazon.awssdk.aws.greengrass.model.UnauthorizedError;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;
import software.amazon.awssdk.eventstreamrpc.StreamResponseHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.getIPCConfigForService;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.prepareKernelFromConfigFile;
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

@ExtendWith(GGExtension.class)
class IPCPubSubTest {
    private static final Logger logger = LogManager.getLogger(IPCPubSubTest.class);

    @TempDir
    static Path tempRootDir;
    private static int TIMEOUT_FOR_PUBSUB_SECONDS = 2;
    private static Kernel kernel;
    private static IPCClient client;
    public static Permission TES_DEFAULT_POLICY =
            Permission.builder().principal("*").operation("getCredentials").resource(null).build();
    private static final String newACl =
            "{  \n" +
            "   \"aws.greengrass.ipc.pubsub\":[\n" +
            "        {\n" +
            "          \"policyId10\":{\n" +
            "            \"policyDescription\":\"access to pubsub topics for ServiceName\",\n" +
            "            \"operations\":[\n" +
            "              \"*\"\n" +
            "            ],\n" +
            "            \"resources\":[\n" +
            "              \"*\"\n" +
            "            ]\n" +
            "          }\n" +
            "        }\n" +
            "    ]\n" +
            "}";

    private static final String oldACl =
            "{  \n" +
            "   \"aws.greengrass.ipc.pubsub\":[\n" +
            "        {\n" +
            "          \"policyId4\":{\n" +
            "            \"policyDescription\":\"access to pubsub topics for ServiceName\",\n" +
            "            \"operations\":[\n" +
            "              \"publish\"\n" +
            "            ],\n" +
            "            \"resources\":[\n" +
            "              \"/topic/1/#\",\n" +
            "              \"/longer/topic/example/\",\n" +
            "              \"*\"\n" +
            "            ]\n" +
            "          }\n" +
            "        }\n" +
            "    ]\n" +
            "}";

    @BeforeAll
    static void beforeEach(ExtensionContext context) throws InterruptedException, ExecutionException {
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());
        ignoreExceptionOfType(context, InterruptedException.class);
        ignoreExceptionWithMessage(context, "Connection reset by peer");
        // Ignore if IPC can't send us more lifecycle updates because the test is already done.
        ignoreExceptionUltimateCauseWithMessage(context, "Channel not found for given connection context");
        kernel = prepareKernelFromConfigFile("pubsub.yaml", IPCPubSubTest.class, "SubscribeAndPublish");
    }

    @AfterAll
    static void stopKernel() throws IOException {
        if (client != null) {
            client.disconnect();
        }
        kernel.shutdown();
    }

    @Test
    void GIVEN_pubsubclient_WHEN_subscribe_and_publish_is_authorized_THEN_succeeds() throws Exception {
        KernelIPCClientConfig config = getIPCConfigForService("SubscribeAndPublish", kernel);
        client = new IPCClientImpl(config);
        PubSub c = new PubSubImpl(client);

        Pair<CompletableFuture<Void>, Consumer<byte[]>> cb = asyncAssertOnConsumer((m) -> {
            assertEquals("some message", new String(m, StandardCharsets.UTF_8));
        });
        c.subscribeToTopic("a", cb.getRight());
        c.publishToTopic("a", "some message".getBytes(StandardCharsets.UTF_8));
        cb.getLeft().get(TIMEOUT_FOR_PUBSUB_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void GIVEN_pubsubclient_WHEN_subscribe_is_not_authorized_THEN_Fail() throws Exception {
        KernelIPCClientConfig config = getIPCConfigForService("PublishNotSubscribe", kernel);
        client = new IPCClientImpl(config);
        PubSub c = new PubSubImpl(client);

        Pair<CompletableFuture<Void>, Consumer<byte[]>> cb = asyncAssertOnConsumer((m) -> {
            assertEquals("some message", new String(m, StandardCharsets.UTF_8));
        });

        assertThrows(PubSubException.class, () -> c.subscribeToTopic("a", cb.getRight()));
    }

    @Test
    void GIVEN_pubsubclient_WHEN_publish_is_not_authorized_THEN_Fail() throws Exception {
        KernelIPCClientConfig config = getIPCConfigForService("SubscribeNotPublish", kernel);
        client = new IPCClientImpl(config);
        PubSub c = new PubSubImpl(client);

        assertThrows(PubSubException.class, () -> c.publishToTopic("a", "some message".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void GIVEN_pubsubclient_WHEN_subscribe_authorization_changes_to_authorized_THEN_succeeds() throws Exception {
        KernelIPCClientConfig config = getIPCConfigForService("OnlyPublish", kernel);
        client = new IPCClientImpl(config);
        PubSub c = new PubSubImpl(client);

        assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS, TES_DEFAULT_POLICY));

        Pair<CompletableFuture<Void>, Consumer<byte[]>> cb = asyncAssertOnConsumer((m) -> {
            assertEquals("some message", new String(m, StandardCharsets.UTF_8));
        });
        c.publishToTopic("a", "some message".getBytes(StandardCharsets.UTF_8));
        assertThrows(PubSubException.class, () -> c.subscribeToTopic("a", cb.getRight()));
        Topic aclTopic = kernel.findServiceTopic("OnlyPublish").find(PARAMETERS_CONFIG_KEY, ACCESS_CONTROL_NAMESPACE_TOPIC);
        aclTopic.withNewerValue(System.currentTimeMillis(), newACl);
        //Block until events are completed
        kernel.getContext().runOnPublishQueueAndWait(() -> {
        });

        assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS, TES_DEFAULT_POLICY));

        c.subscribeToTopic("a", cb.getRight()); //now this should succeed
        c.publishToTopic("a", "some message".getBytes(StandardCharsets.UTF_8));
        cb.getLeft().get(TIMEOUT_FOR_PUBSUB_SECONDS, TimeUnit.SECONDS);

        aclTopic = kernel.findServiceTopic("OnlyPublish").find(PARAMETERS_CONFIG_KEY, ACCESS_CONTROL_NAMESPACE_TOPIC);
        aclTopic.withNewerValue(System.currentTimeMillis(), oldACl);
        //Block until events are completed
        kernel.getContext().runOnPublishQueueAndWait(() -> {
        });
    }


    @Test
    @SuppressWarnings({"PMD.AvoidCatchingGenericException"})
    void GIVEN_PubSubEventStreamClient_WHEN_subscribe_and_unsubscribe_THEN_publishes_only_once() throws Exception {
        String topicName = "topicName";
        SubscribeToTopicRequest subscribeToTopicRequest = new SubscribeToTopicRequest();
        subscribeToTopicRequest.setTopic(topicName);
        CountDownLatch cdl = new CountDownLatch(1);
        AtomicInteger atomicInteger = new AtomicInteger();

        CountDownLatch subscriptionLatch = new CountDownLatch(1);
        Slf4jLogAdapter.addGlobalListener(m -> {
            if (m.getMessage().contains("Subscribing to topic")) {
                subscriptionLatch.countDown();
            }
        });
        String authToken = IPCTestUtils.getAuthTokeForService(kernel, "SubscribeAndPublish");
        SocketOptions socketOptions = TestUtils.getSocketOptionsForIPC();
        try (EventStreamRPCConnection clientConnection =
                     IPCTestUtils.connectToGGCOverEventStreamIPC(socketOptions, authToken, kernel)){
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

            PublishToTopicRequest publishToTopicRequest = new PublishToTopicRequest();
            publishToTopicRequest.setTopic(topicName);
            PublishMessage publishMessage = new PublishMessage();
            BinaryMessage binaryMessage = new BinaryMessage();
            binaryMessage.setMessage("ABCDEFG".getBytes());
            publishMessage.setBinaryMessage(binaryMessage);
            publishToTopicRequest.setPublishMessage(publishMessage);
            greengrassCoreIPCClient.publishToTopic(publishToTopicRequest, Optional.empty()).getResponse().get(10, TimeUnit.SECONDS);
            assertTrue(cdl.await(20, TimeUnit.SECONDS));
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
        String authToken = IPCTestUtils.getAuthTokeForService(kernel, "PublishNotSubscribe");
        SocketOptions socketOptions = TestUtils.getSocketOptionsForIPC();
        try (EventStreamRPCConnection clientConnection =
                     IPCTestUtils.connectToGGCOverEventStreamIPC(socketOptions, authToken, kernel)) {
            GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);

            String topicName = "topicName";
            PublishToTopicRequest publishToTopicRequest = new PublishToTopicRequest();
            publishToTopicRequest.setTopic(topicName);
            PublishMessage publishMessage = new PublishMessage();
            BinaryMessage binaryMessage = new BinaryMessage();
            binaryMessage.setMessage("ABCDEFG".getBytes());
            publishMessage.setBinaryMessage(binaryMessage);
            publishToTopicRequest.setPublishMessage(publishMessage);
            ExecutionException executionException = assertThrows(ExecutionException.class, () ->
                    greengrassCoreIPCClient.publishToTopic(publishToTopicRequest, Optional.empty()).getResponse()
                            .get(10, TimeUnit.SECONDS));
            assertTrue(executionException.getCause() instanceof UnauthorizedError);
            UnauthorizedError unauthorizedError = (UnauthorizedError) executionException.getCause();
            assertEquals("Principal PublishNotSubscribe is not authorized to perform aws.greengrass.ipc.pubsub:aws.greengrass#PublishToTopic on resource topicName",
                    unauthorizedError.getMessage());

        }
    }

    @SuppressWarnings({"PMD.AvoidCatchingGenericException"})
    @Test
    void GIVEN_pubsubclient_with_event_stream_WHEN_subscribe_authorization_changes_to_authorized_THEN_succeeds() throws Exception {
        String topicName = "topicName";
        SubscribeToTopicRequest subscribeToTopicRequest = new SubscribeToTopicRequest();
        subscribeToTopicRequest.setTopic(topicName);
        CountDownLatch subscriptionLatch = new CountDownLatch(1);
        Slf4jLogAdapter.addGlobalListener(m -> {
            if (m.getMessage().contains("Subscribing to topic")) {
                subscriptionLatch.countDown();
            }
        });

        String authToken = IPCTestUtils.getAuthTokeForService(kernel, "OnlyPublish");
        SocketOptions socketOptions = TestUtils.getSocketOptionsForIPC();
        try (EventStreamRPCConnection clientConnection =
                     IPCTestUtils.connectToGGCOverEventStreamIPC(socketOptions, authToken, kernel)) {
            GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);

            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS, TES_DEFAULT_POLICY));
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
        Topic aclTopic = kernel.findServiceTopic("OnlyPublish").find(PARAMETERS_CONFIG_KEY, ACCESS_CONTROL_NAMESPACE_TOPIC);
        aclTopic.withNewerValue(System.currentTimeMillis(), newACl);
        //Block until events are completed
        kernel.getContext().runOnPublishQueueAndWait(() -> { });
        assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS, TES_DEFAULT_POLICY));

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

        aclTopic = kernel.findServiceTopic("OnlyPublish").find(PARAMETERS_CONFIG_KEY, ACCESS_CONTROL_NAMESPACE_TOPIC);
        aclTopic.withNewerValue(System.currentTimeMillis(), oldACl);
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

    //TODO: review if we want to add future support for the `unsubscribe` operation:
    // https://issues-iad.amazon.com/issues/V234932355
//    @Test
//    void GIVEN_pubsubclient_WHEN_unsubscribe_is_not_authorized_THEN_Fail(ExtensionContext context) throws Exception {
//
//        kernel = prepareKernelFromConfigFile("pubsub_unauthorized_unsubscribe.yaml",
//                TEST_SERVICE_NAME, this.getClass());
//        KernelIPCClientConfig config = getIPCConfigForService(TEST_SERVICE_NAME, kernel);
//        client = new IPCClientImpl(config);
//        PubSub c = new PubSubImpl(client);
//
//        Pair<CompletableFuture<Void>, Consumer<byte[]>> cb = asyncAssertOnConsumer((m) -> {
//            assertEquals("some message", new String(m, StandardCharsets.UTF_8));
//        });
//
//        c.subscribeToTopic("a", cb.getRight());
//        c.publishToTopic("a", "some message".getBytes(StandardCharsets.UTF_8));
//        cb.getLeft().get(2, TimeUnit.SECONDS);
//
//        ignoreExceptionOfType(context, AuthorizationException.class);
//        assertThrows(PubSubException.class, () -> c.unsubscribeFromTopic("a"));
//    }
}