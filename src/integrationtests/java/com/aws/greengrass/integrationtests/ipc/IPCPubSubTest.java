/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.greengrass.integrationtests.ipc;

import com.aws.greengrass.authorization.AuthorizationModule;
import com.aws.greengrass.authorization.Permission;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.testcommons.testutilities.UniqueRootPathBeforeAll;
import com.aws.greengrass.ipc.IPCClient;
import com.aws.greengrass.ipc.IPCClientImpl;
import com.aws.greengrass.ipc.config.KernelIPCClientConfig;
import com.aws.greengrass.ipc.services.pubsub.PubSub;
import com.aws.greengrass.ipc.services.pubsub.PubSubException;
import com.aws.greengrass.ipc.services.pubsub.PubSubImpl;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Pair;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith({GGExtension.class, UniqueRootPathBeforeAll.class})
class IPCPubSubTest {

    private static int TIMEOUT_FOR_PUBSUB_SECONDS = 2;
    private static Kernel kernel;
    private static IPCClient client;
    public static Permission TES_DEFAULT_POLICY =
            Permission.builder().principal("*").operation("getCredentials").resource(null).build();

    @BeforeAll
    static void beforeEach(ExtensionContext context) throws InterruptedException {
        ignoreExceptionOfType(context, InterruptedException.class);
        ignoreExceptionWithMessage(context, "Connection reset by peer");
        // Ignore if IPC can't send us more lifecycle updates because the test is already done.
        ignoreExceptionUltimateCauseWithMessage(context, "Channel not found for given connection context");
        kernel = prepareKernelFromConfigFile("pubsub.yaml", IPCPubSubTest.class, "SubscribeAndPublish");
    }

    @AfterAll
    static void stopKernel() throws IOException {
        client.disconnect();
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
        String newACl =
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

        Topic aclTopic = kernel.findServiceTopic("OnlyPublish").find(PARAMETERS_CONFIG_KEY, ACCESS_CONTROL_NAMESPACE_TOPIC);
        aclTopic.withNewerValue(System.currentTimeMillis(), newACl);
        //Block until events are completed
        kernel.getContext().runOnPublishQueueAndWait(() -> {
        });

        assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS, TES_DEFAULT_POLICY));

        c.subscribeToTopic("a", cb.getRight()); //now this should succeed
        c.publishToTopic("a", "some message".getBytes(StandardCharsets.UTF_8));
        cb.getLeft().get(TIMEOUT_FOR_PUBSUB_SECONDS, TimeUnit.SECONDS);
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
