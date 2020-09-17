/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.integrationtests.ipc;

import com.aws.iot.evergreen.auth.AuthorizationModule;
import com.aws.iot.evergreen.auth.Permission;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.ipc.IPCClient;
import com.aws.iot.evergreen.ipc.IPCClientImpl;
import com.aws.iot.evergreen.ipc.config.KernelIPCClientConfig;
import com.aws.iot.evergreen.ipc.services.pubsub.PubSub;
import com.aws.iot.evergreen.ipc.services.pubsub.PubSubException;
import com.aws.iot.evergreen.ipc.services.pubsub.PubSubImpl;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.util.Pair;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.aws.iot.evergreen.integrationtests.ipc.IPCTestUtils.getIPCConfigForService;
import static com.aws.iot.evergreen.integrationtests.ipc.IPCTestUtils.prepareKernelFromConfigFile;
import static com.aws.iot.evergreen.ipc.modules.PubSubIPCService.PUB_SUB_SERVICE_NAME;
import static com.aws.iot.evergreen.kernel.EvergreenService.ACCESS_CONTROL_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static com.aws.iot.evergreen.testcommons.testutilities.TestUtils.asyncAssertOnConsumer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(EGExtension.class)
class IPCPubSubTest {

    @TempDir
    static Path tempRootDir;
    private static int TIMEOUT_FOR_PUBSUB_SECONDS = 2;
    private static Kernel kernel;
    private IPCClient client;

    @BeforeAll
    static void startKernel() throws InterruptedException {
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());
        kernel = prepareKernelFromConfigFile("pubsub.yaml", IPCPubSubTest.class, "SubscribeAndPublish");
    }

    @AfterAll
    static void stopKernel() {
        kernel.shutdown();
    }

    @BeforeEach
    void beforeEach(ExtensionContext context) {
        ignoreExceptionOfType(context, InterruptedException.class);
        ignoreExceptionWithMessage(context, "Connection reset by peer");
        // Ignore if IPC can't send us more lifecycle updates because the test is already done.
        ignoreExceptionUltimateCauseWithMessage(context, "Channel not found for given connection context");
    }

    @AfterEach
    void afterEach() throws IOException {
        client.disconnect();
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
        c.subscribeToTopic("a", cb.getRight()); //now this should succeed
        c.publishToTopic("a", "some message".getBytes(StandardCharsets.UTF_8));
        cb.getLeft().get(TIMEOUT_FOR_PUBSUB_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void GIVEN_pubsubclient_WHEN_authorized_THEN_ACL_child_removed_THEN_updates() throws Exception {
        KernelIPCClientConfig config = getIPCConfigForService("DoAll1", kernel);
        client = new IPCClientImpl(config);
        PubSub c = new PubSubImpl(client);

        Pair<CompletableFuture<Void>, Consumer<byte[]>> cb = asyncAssertOnConsumer((m) -> {
            assertEquals("some message", new String(m, StandardCharsets.UTF_8));
        });
        c.subscribeToTopic("a", cb.getRight()); //this should succeed
        c.publishToTopic("a", "some message".getBytes(StandardCharsets.UTF_8));
        cb.getLeft().get(TIMEOUT_FOR_PUBSUB_SECONDS, TimeUnit.SECONDS);

        Topics serviceTopic = kernel.findServiceTopic("DoAll1");
        Topics parameters = serviceTopic.findTopics(PARAMETERS_CONFIG_KEY);
        Topic acl = parameters.find(ACCESS_CONTROL_NAMESPACE_TOPIC);
        if (acl != null) {
            acl.remove();
        }
        //Block until events are completed
        kernel.getContext().runOnPublishQueueAndWait(() -> {
        });
        //Now the authorization policies should have been removed and these should fail
        assertThrows(PubSubException.class, () -> c.subscribeToTopic("a", cb.getRight()));
        assertThrows(PubSubException.class, () -> c.publishToTopic("a", "some message".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void GIVEN_pubsubclient_WHEN_authorized_THEN_parameters_child_removed_THEN_updates() throws Exception {
        KernelIPCClientConfig config = getIPCConfigForService("DoAll2", kernel);
        client = new IPCClientImpl(config);
        PubSub c = new PubSubImpl(client);

        Pair<CompletableFuture<Void>, Consumer<byte[]>> cb = asyncAssertOnConsumer((m) -> {
            assertEquals("some message", new String(m, StandardCharsets.UTF_8));
        });
        c.subscribeToTopic("a", cb.getRight()); //this should succeed
        c.publishToTopic("a", "some message".getBytes(StandardCharsets.UTF_8));
        cb.getLeft().get(TIMEOUT_FOR_PUBSUB_SECONDS, TimeUnit.SECONDS);

        Topics serviceTopic = kernel.findServiceTopic("DoAll2");
        Topics parameters = serviceTopic.findTopics(PARAMETERS_CONFIG_KEY);
        if (parameters != null) {
            parameters.remove();
        }
        //Block until events are completed
        kernel.getContext().runOnPublishQueueAndWait(() -> {
        });
        //Now the authorization policies should have been removed and these should fail
        assertThrows(PubSubException.class, () -> c.subscribeToTopic("a", cb.getRight()));
        assertThrows(PubSubException.class, () -> c.publishToTopic("a", "some message".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void GIVEN_pubsubclient_WHEN_service_removed_and_added_THEN_fail_and_succeed() throws Exception {
        KernelIPCClientConfig config = getIPCConfigForService("SubscribeAndPublish", kernel);
        client = new IPCClientImpl(config);
        PubSub c = new PubSubImpl(client);
        Pair<CompletableFuture<Void>, Consumer<byte[]>> cb = asyncAssertOnConsumer((m) -> {
            assertEquals("some message", new String(m, StandardCharsets.UTF_8));
        }, -1);
        Permission policyId1 =
                Permission.builder().principal("SubscribeAndPublish").operation("*").resource("*").build();
        Permission policyId2 =
                Permission.builder().principal("PublishNotSubscribe").operation("publish").resource("*").build();
        assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(PUB_SUB_SERVICE_NAME, policyId1));
        assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(PUB_SUB_SERVICE_NAME, policyId2));
        c.subscribeToTopic("a", cb.getRight());
        c.publishToTopic("a", "some message".getBytes(StandardCharsets.UTF_8));
        cb.getLeft().get(TIMEOUT_FOR_PUBSUB_SECONDS, TimeUnit.SECONDS);

        // Remove the service topic
        Topics serviceTopic = kernel.findServiceTopic("SubscribeAndPublish");
        if (serviceTopic != null) {
            serviceTopic.remove();
        }
        kernel.getContext().runOnPublishQueueAndWait(() -> {
        });
        assertFalse(kernel.getContext().get(AuthorizationModule.class).isPresent(PUB_SUB_SERVICE_NAME, policyId1));
        assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(PUB_SUB_SERVICE_NAME, policyId2));
        assertThrows(PubSubException.class, () -> c.subscribeToTopic("a", cb.getRight()));
        assertThrows(PubSubException.class, () -> c.publishToTopic("a", "some message".getBytes(StandardCharsets.UTF_8)));

        // Reload the kernel with the service and correct authorization policy
        kernel.getConfig().read(new URL(IPCPubSubTest.class.getResource("pubsub.yaml").toString()), false);
        kernel.getContext().runOnPublishQueueAndWait(() -> {
        });
        assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(PUB_SUB_SERVICE_NAME, policyId1));
        assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(PUB_SUB_SERVICE_NAME, policyId2));
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
