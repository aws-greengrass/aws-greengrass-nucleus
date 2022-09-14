/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.ipc;

import com.aws.greengrass.authorization.AuthorizationModule;
import com.aws.greengrass.authorization.Permission;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.util.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCClient;
import software.amazon.awssdk.aws.greengrass.model.UnauthorizedError;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.integrationtests.ipc.IPCPubSubTest.TES_DEFAULT_POLICY;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.prepareKernelFromConfigFile;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.publishToTopicOverIpcAsBinaryMessage;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.subscribeToTopicOveripcForBinaryMessages;
import static com.aws.greengrass.ipc.modules.PubSubIPCService.PUB_SUB_SERVICE_NAME;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.ACCESS_CONTROL_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.tes.TokenExchangeService.TOKEN_EXCHANGE_SERVICE_TOPICS;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static com.aws.greengrass.testcommons.testutilities.TestUtils.asyncAssertOnConsumer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class IPCPubSubRemovalTest extends BaseITCase {

    private static final int TIMEOUT_FOR_PUBSUB_SECONDS = 2;
    private Kernel kernel;

    @AfterEach
    void stopKernel() {
        kernel.shutdown();
    }

    @BeforeEach
    void beforeEach(ExtensionContext context) throws InterruptedException, IOException, DeviceConfigurationException {
        ignoreExceptionOfType(context, InterruptedException.class);
        ignoreExceptionWithMessage(context, "Connection reset by peer");
        // Ignore if IPC can't send us more lifecycle updates because the test is already done.
        ignoreExceptionUltimateCauseWithMessage(context, "Channel not found for given connection context");

        kernel = prepareKernelFromConfigFile("pubsub.yaml", IPCPubSubTest.class, "SubscribeAndPublish");
    }

    @Test
    void GIVEN_pubsubclient_WHEN_authorized_THEN_ACL_child_removed_THEN_updates() throws Exception {
        try(EventStreamRPCConnection connection = IPCTestUtils.getEventStreamRpcConnection(kernel,
                "DoAll1")) {
            GreengrassCoreIPCClient ipcClient = new GreengrassCoreIPCClient(connection);

            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS,
                    TES_DEFAULT_POLICY));

            Pair<CompletableFuture<Void>, Consumer<byte[]>> cb = asyncAssertOnConsumer((m) -> {
                assertEquals("some message", new String(m, StandardCharsets.UTF_8));
            });
            subscribeToTopicOveripcForBinaryMessages(ipcClient, "a", cb.getRight());
            publishToTopicOverIpcAsBinaryMessage(ipcClient, "a", "some message");
            cb.getLeft().get(TIMEOUT_FOR_PUBSUB_SECONDS, TimeUnit.SECONDS);

            Topics serviceTopic = kernel.findServiceTopic("DoAll1");
            Topics parameters = serviceTopic.findTopics(CONFIGURATION_CONFIG_KEY);
            Topic acl = parameters.find(ACCESS_CONTROL_NAMESPACE_TOPIC, "aws.greengrass.ipc.pubsub",
                    "policyId5", "operations");
            if (acl != null) {
                acl.withValue(Collections.emptyList());
            }
            //Block until events are completed
            kernel.getContext().waitForPublishQueueToClear();

            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS,
                    TES_DEFAULT_POLICY));

            //Now the authorization policies should have been removed and these should fail
            ExecutionException executionException = assertThrows(ExecutionException.class,
                    () -> subscribeToTopicOveripcForBinaryMessages(ipcClient, "a", cb.getRight()));
            assertTrue(executionException.getCause() instanceof UnauthorizedError);

            ExecutionException executionException1 = assertThrows(ExecutionException.class,
                    () -> publishToTopicOverIpcAsBinaryMessage(ipcClient, "a", "some message"));
            assertTrue(executionException1.getCause() instanceof UnauthorizedError);

            serviceTopic = kernel.findServiceTopic("DoAll1");
            parameters = serviceTopic.findTopics(CONFIGURATION_CONFIG_KEY);
            Topics aclTopics = parameters.findTopics(ACCESS_CONTROL_NAMESPACE_TOPIC);
            if (aclTopics != null) {
                aclTopics.remove();
            }
            //Block until events are completed
            kernel.getContext().waitForPublishQueueToClear();

            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS,
                    TES_DEFAULT_POLICY));

            //Now the authorization policies should have been removed and these should fail
            executionException = assertThrows(ExecutionException.class,
                    () -> subscribeToTopicOveripcForBinaryMessages(ipcClient, "a", cb.getRight()));
            assertTrue(executionException.getCause() instanceof UnauthorizedError);

            executionException1 = assertThrows(ExecutionException.class,
                    () -> publishToTopicOverIpcAsBinaryMessage(ipcClient, "a", "some message"));
            assertTrue(executionException1.getCause() instanceof UnauthorizedError);
        }
    }

    @Test
    void GIVEN_pubsubclient_WHEN_authorized_THEN_ACL_parameter_removed_via_deployment_THEN_updates(ExtensionContext context) throws Exception {
        try(EventStreamRPCConnection connection = IPCTestUtils.getEventStreamRpcConnection(kernel,
                "SubscribeAndPublish")) {
            GreengrassCoreIPCClient ipcClient = new GreengrassCoreIPCClient(connection);

            Permission policyId1 = Permission.builder().principal("SubscribeAndPublish").operation("*").resource("*").build();
            Permission policyId2 = Permission.builder().principal("PublishNotSubscribe").operation("aws.greengrass#PublishToTopic").resource("*").build();
            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(PUB_SUB_SERVICE_NAME, policyId1));
            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(PUB_SUB_SERVICE_NAME, policyId2));

            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS,
                    TES_DEFAULT_POLICY));

            Pair<CompletableFuture<Void>, Consumer<byte[]>> cb = asyncAssertOnConsumer((m) -> {
                assertEquals("some message", new String(m, StandardCharsets.UTF_8));
            });
            subscribeToTopicOveripcForBinaryMessages(ipcClient, "a", cb.getRight());//this should succeed
            publishToTopicOverIpcAsBinaryMessage(ipcClient, "a", "some message");
            cb.getLeft().get(TIMEOUT_FOR_PUBSUB_SECONDS, TimeUnit.SECONDS);

            ignoreExceptionOfType(context, PackageDownloadException.class);

            // Remove ACL parameter from component SubscribeAndPublish
            Topics aclNode = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC,
                    "SubscribeAndPublish", CONFIGURATION_CONFIG_KEY);
            aclNode.remove(aclNode.lookupTopics("accessControl"));
            kernel.getContext().waitForPublishQueueToClear();

            assertFalse(kernel.getContext().get(AuthorizationModule.class).isPresent(PUB_SUB_SERVICE_NAME, policyId1));
            // GG_NEEDS_REVIEW: TODO: convert all these integ tests to use only recipe merging instead of loading a kernel config file
            // Otherwise the removal of "SubscribeAndPublish" also inadvertently results in the "PublishNotSubscribe"
            // component (and all other components) and its policies being removed, since it is not part of the deployment.
            // Hence the next line is commented out
            //assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(PUB_SUB_SERVICE_NAME,policyId2));

            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS,
                    TES_DEFAULT_POLICY));

            //Now the authorization policies should have been removed and these should fail
            ExecutionException ee = assertThrows(ExecutionException.class,
                    () -> subscribeToTopicOveripcForBinaryMessages(ipcClient, "a",
                            cb.getRight()));
            assertTrue(ee.getCause() instanceof UnauthorizedError);
            ExecutionException ee1 = assertThrows(ExecutionException.class,
                    () -> publishToTopicOverIpcAsBinaryMessage(ipcClient, "a",
                            "some message"));
            assertTrue(ee1.getCause() instanceof UnauthorizedError);
        }
    }

    @Test
    void GIVEN_pubsubclient_WHEN_authorized_THEN_parameters_child_removed_THEN_updates() throws Exception {
        try(EventStreamRPCConnection connection = IPCTestUtils.getEventStreamRpcConnection(kernel,
                "DoAll2")) {
            GreengrassCoreIPCClient ipcClient = new GreengrassCoreIPCClient(connection);

            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS,
                    TES_DEFAULT_POLICY));

            Pair<CompletableFuture<Void>, Consumer<byte[]>> cb = asyncAssertOnConsumer((m) -> {
                assertEquals("some message", new String(m, StandardCharsets.UTF_8));
            });
            subscribeToTopicOveripcForBinaryMessages(ipcClient, "a", cb.getRight());//this should succeed
            publishToTopicOverIpcAsBinaryMessage(ipcClient, "a", "some message");
            cb.getLeft().get(TIMEOUT_FOR_PUBSUB_SECONDS, TimeUnit.SECONDS);

            Topics serviceTopic = kernel.findServiceTopic("DoAll2");
            Topics parameters = serviceTopic.findTopics(CONFIGURATION_CONFIG_KEY);
            if (parameters != null) {
                parameters.remove();
            }
            //Block until events are completed
            kernel.getContext().waitForPublishQueueToClear();

            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS,
                    TES_DEFAULT_POLICY));

            //Now the authorization policies should have been removed and these should fail
            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> subscribeToTopicOveripcForBinaryMessages(ipcClient,
                            "a", cb.getRight()));
            assertTrue(e.getCause() instanceof UnauthorizedError);
            e = assertThrows(ExecutionException.class, () -> publishToTopicOverIpcAsBinaryMessage(ipcClient, "a",
                    "some message"));
            assertTrue(e.getCause() instanceof UnauthorizedError);
        }
    }

    @Test
    void GIVEN_pubsubclient_WHEN_service_removed_and_added_THEN_fail_and_succeed() throws Exception {
        try(EventStreamRPCConnection connection = IPCTestUtils.getEventStreamRpcConnection(kernel,
                "SubscribeAndPublish")) {
            GreengrassCoreIPCClient ipcClient = new GreengrassCoreIPCClient(connection);

            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS,
                    TES_DEFAULT_POLICY));

            Pair<CompletableFuture<Void>, Consumer<byte[]>> cb = asyncAssertOnConsumer((m) -> {
                assertEquals("some message", new String(m, StandardCharsets.UTF_8));
            }, -1);
            Permission policyId1 = Permission.builder().principal("SubscribeAndPublish").operation("*").resource("*").build();
            Permission policyId2 = Permission.builder().principal("PublishNotSubscribe").operation("aws.greengrass#PublishToTopic").resource("*").build();
            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(PUB_SUB_SERVICE_NAME, policyId1));
            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(PUB_SUB_SERVICE_NAME, policyId2));
            subscribeToTopicOveripcForBinaryMessages(ipcClient, "a", cb.getRight());
            publishToTopicOverIpcAsBinaryMessage(ipcClient, "a", "some message");
            cb.getLeft().get(TIMEOUT_FOR_PUBSUB_SECONDS, TimeUnit.SECONDS);

            // Remove the service topic
            Topics serviceTopic = kernel.findServiceTopic("SubscribeAndPublish");
            if (serviceTopic != null) {
                serviceTopic.remove();
            }
            kernel.getContext().waitForPublishQueueToClear();
            assertFalse(kernel.getContext().get(AuthorizationModule.class).isPresent(PUB_SUB_SERVICE_NAME, policyId1));
            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(PUB_SUB_SERVICE_NAME, policyId2));
            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> subscribeToTopicOveripcForBinaryMessages(ipcClient,
                            "a", cb.getRight()));
            assertTrue(e.getCause() instanceof UnauthorizedError);
            e = assertThrows(ExecutionException.class, () -> publishToTopicOverIpcAsBinaryMessage(ipcClient, "a",
                    "some message"));
            assertTrue(e.getCause() instanceof UnauthorizedError);

            // Reload the kernel with the service and correct authorization policy
            kernel.getConfig().read(new URL(IPCPubSubTest.class.getResource("pubsub.yaml").toString()), false);
            kernel.getContext().waitForPublishQueueToClear();
            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(PUB_SUB_SERVICE_NAME, policyId1));
            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(PUB_SUB_SERVICE_NAME, policyId2));

            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS, TES_DEFAULT_POLICY));

            subscribeToTopicOveripcForBinaryMessages(ipcClient, "a", cb.getRight());//now this should succeed
            publishToTopicOverIpcAsBinaryMessage(ipcClient, "a", "some message");
            cb.getLeft().get(TIMEOUT_FOR_PUBSUB_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Test
    void GIVEN_pubsubclient_WHEN_authorized_THEN_component_removed_via_deployment_THEN_updates(ExtensionContext context) throws Exception {
        try (EventStreamRPCConnection connection = IPCTestUtils
                .getEventStreamRpcConnection(kernel, "SubscribeAndPublish")) {
            GreengrassCoreIPCClient ipcClient = new GreengrassCoreIPCClient(connection);
            Pair<CompletableFuture<Void>, Consumer<byte[]>> cb = asyncAssertOnConsumer((m) -> {
                assertEquals("some message", new String(m, StandardCharsets.UTF_8));
            }, -1);
            Permission policyId1 = Permission.builder().principal("SubscribeAndPublish").operation("*").resource("*").build();
            Permission policyId2 = Permission.builder().principal("PublishNotSubscribe").operation("aws.greengrass#PublishToTopic").resource("*").build();
            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(PUB_SUB_SERVICE_NAME, policyId1));
            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(PUB_SUB_SERVICE_NAME, policyId2));
            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS, TES_DEFAULT_POLICY));

            subscribeToTopicOveripcForBinaryMessages(ipcClient, "a", cb.getRight());
            publishToTopicOverIpcAsBinaryMessage(ipcClient, "a", "some message");
            cb.getLeft().get(TIMEOUT_FOR_PUBSUB_SECONDS, TimeUnit.SECONDS);

            // Remove component SubscribeAndPublish
            GreengrassService subscribeAndPublish = kernel.locate("SubscribeAndPublish");
            subscribeAndPublish.close().get(1, TimeUnit.MINUTES);
            subscribeAndPublish.getConfig().remove();
            kernel.getContext().waitForPublishQueueToClear();

            assertFalse(kernel.getContext().get(AuthorizationModule.class).isPresent(PUB_SUB_SERVICE_NAME, policyId1));
            // GG_NEEDS_REVIEW: TODO: convert all these integ tests to use only recipe merging instead of loading a kernel config file
            // Otherwise the removal of "SubscribeAndPublish" also inadvertently results in the "PublishNotSubscribe"
            // component (and all other components) and its policies being removed, since it is not part of the deployment.
            // Hence the next line is commented out
            //assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(PUB_SUB_SERVICE_NAME,policyId2));

            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS, TES_DEFAULT_POLICY));

            //Now the authorization policies should have been removed and these should fail
            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> subscribeToTopicOveripcForBinaryMessages(ipcClient,
                            "a", cb.getRight()));
            assertTrue(e.getCause() instanceof UnauthorizedError);
            e = assertThrows(ExecutionException.class, () -> publishToTopicOverIpcAsBinaryMessage(ipcClient, "a",
                    "some message"));
        }
    }
}
