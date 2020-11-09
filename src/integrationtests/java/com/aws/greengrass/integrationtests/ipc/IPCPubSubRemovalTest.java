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
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.util.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCClient;
import software.amazon.awssdk.aws.greengrass.model.CreateLocalDeploymentRequest;
import software.amazon.awssdk.aws.greengrass.model.CreateLocalDeploymentResponse;
import software.amazon.awssdk.aws.greengrass.model.UnauthorizedError;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;
import software.amazon.awssdk.utils.ImmutableMap;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.greengrass.integrationtests.ipc.IPCPubSubTest.TES_DEFAULT_POLICY;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.prepareKernelFromConfigFile;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.publishToTopicOverIpcAsBinaryMessage;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.subscribeToTopicOveripcForBinaryMessages;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.waitForDeploymentToBeSuccessful;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.waitForServiceToComeInState;
import static com.aws.greengrass.ipc.modules.PubSubIPCService.PUB_SUB_SERVICE_NAME;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.ACCESS_CONTROL_NAMESPACE_TOPIC;
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
    void beforeEach(ExtensionContext context) throws InterruptedException {
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

            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS, TES_DEFAULT_POLICY));

            Pair<CompletableFuture<Void>, Consumer<byte[]>> cb = asyncAssertOnConsumer((m) -> {
                assertEquals("some message", new String(m, StandardCharsets.UTF_8));
            });
            subscribeToTopicOveripcForBinaryMessages(ipcClient, "a", cb.getRight());//this should succeed
            publishToTopicOverIpcAsBinaryMessage(ipcClient, "a", "some message");
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

            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS, TES_DEFAULT_POLICY));

            //Now the authorization policies should have been removed and these should fail
            ExecutionException executionException = assertThrows(ExecutionException.class,
                    () -> subscribeToTopicOveripcForBinaryMessages(ipcClient, "a", cb.getRight()));
            assertTrue(executionException.getCause() instanceof UnauthorizedError);

            ExecutionException executionException1 = assertThrows(ExecutionException.class,
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

            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS, TES_DEFAULT_POLICY));

            Pair<CompletableFuture<Void>, Consumer<byte[]>> cb = asyncAssertOnConsumer((m) -> {
                assertEquals("some message", new String(m, StandardCharsets.UTF_8));
            });
            subscribeToTopicOveripcForBinaryMessages(ipcClient, "a", cb.getRight());//this should succeed
            publishToTopicOverIpcAsBinaryMessage(ipcClient, "a", "some message");
            cb.getLeft().get(TIMEOUT_FOR_PUBSUB_SECONDS, TimeUnit.SECONDS);

            ignoreExceptionOfType(context, PackageDownloadException.class);
            // Deployment with updated recipes
            Path recipesPath = Paths.get(this.getClass().getResource("recipes").toURI());
            software.amazon.awssdk.aws.greengrass.model.UpdateRecipesAndArtifactsRequest updateRecipesAndArtifactsRequest = new software.amazon.awssdk.aws.greengrass.model.UpdateRecipesAndArtifactsRequest();
            updateRecipesAndArtifactsRequest.setRecipeDirectoryPath(recipesPath.toString());
            ipcClient.updateRecipesAndArtifacts(updateRecipesAndArtifactsRequest, Optional.empty());

            Map<String, Object> configUpdate = new HashMap<>();
            configUpdate.put("MERGE", ImmutableMap.of("accessControl", ""));
                             Map<String, Map<String, Object>> componentToConfiguration = new HashMap<>();
            componentToConfiguration.put("SubscribeAndPublish", configUpdate);
            CreateLocalDeploymentRequest createLocalDeploymentRequest =
                    new CreateLocalDeploymentRequest();
            createLocalDeploymentRequest.setRootComponentVersionsToAdd(Collections.singletonMap("SubscribeAndPublish", "1.0.0"));
            createLocalDeploymentRequest.setComponentToConfiguration(componentToConfiguration);

            CreateLocalDeploymentResponse createLocalDeploymentResponse =
                    ipcClient.createLocalDeployment(createLocalDeploymentRequest, Optional.empty()).getResponse().get(5,
                    TimeUnit.SECONDS);
            String deploymentId1 = createLocalDeploymentResponse.getDeploymentId();
            waitForServiceToComeInState("SubscribeAndPublish", State.RUNNING, kernel).await(10, TimeUnit.SECONDS);
            waitForDeploymentToBeSuccessful(deploymentId1, kernel).await(30, TimeUnit.SECONDS);


            assertFalse(kernel.getContext().get(AuthorizationModule.class).isPresent(PUB_SUB_SERVICE_NAME, policyId1));
            // GG_NEEDS_REVIEW: TODO: convert all these integ tests to use only recipe merging instead of loading a kernel config file
            // Otherwise the removal of "SubscribeAndPublish" also inadvertently results in the "PublishNotSubscribe"
            // component (and all other components) and its policies being removed, since it is not part of the deployment.
            // Hence the next line is commented out
            //assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(PUB_SUB_SERVICE_NAME,policyId2));

            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS, TES_DEFAULT_POLICY));

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

            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS, TES_DEFAULT_POLICY));

            Pair<CompletableFuture<Void>, Consumer<byte[]>> cb = asyncAssertOnConsumer((m) -> {
                assertEquals("some message", new String(m, StandardCharsets.UTF_8));
            });
            subscribeToTopicOveripcForBinaryMessages(ipcClient, "a", cb.getRight());//this should succeed
            publishToTopicOverIpcAsBinaryMessage(ipcClient, "a", "some message");
            cb.getLeft().get(TIMEOUT_FOR_PUBSUB_SECONDS, TimeUnit.SECONDS);

            Topics serviceTopic = kernel.findServiceTopic("DoAll2");
            Topics parameters = serviceTopic.findTopics(PARAMETERS_CONFIG_KEY);
            if (parameters != null) {
                parameters.remove();
            }
            //Block until events are completed
            kernel.getContext().runOnPublishQueueAndWait(() -> {
            });

            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS, TES_DEFAULT_POLICY));

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

            assertTrue(kernel.getContext().get(AuthorizationModule.class).isPresent(TOKEN_EXCHANGE_SERVICE_TOPICS, TES_DEFAULT_POLICY));

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
            kernel.getContext().runOnPublishQueueAndWait(() -> {
            });
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
            kernel.getContext().runOnPublishQueueAndWait(() -> {
            });
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

            CreateLocalDeploymentRequest createLocalDeploymentRequest =
                    new CreateLocalDeploymentRequest();
            createLocalDeploymentRequest.setRootComponentsToRemove(Collections.singletonList("SubscribeAndPublish"));
            CreateLocalDeploymentResponse createLocalDeploymentResponse =
                    ipcClient.createLocalDeployment(createLocalDeploymentRequest, Optional.empty()).getResponse().get(5, TimeUnit.SECONDS);
            String deploymentId1 = createLocalDeploymentResponse.getDeploymentId();
            waitForServiceToComeInState("SubscribeAndPublish", State.RUNNING, kernel).await(10, TimeUnit.SECONDS);
            waitForDeploymentToBeSuccessful(deploymentId1, kernel).await(30, TimeUnit.SECONDS);

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
