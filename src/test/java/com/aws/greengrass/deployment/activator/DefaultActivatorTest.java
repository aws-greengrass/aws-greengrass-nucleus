/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.activator;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.ConfigurationReader;
import com.aws.greengrass.config.UpdateBehaviorTree;
import com.aws.greengrass.dependency.Crashable;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.DeploymentDirectoryManager;
import com.aws.greengrass.deployment.EndpointSwitchState;
import com.aws.greengrass.deployment.exceptions.ServiceUpdateException;

import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.deployment.model.FailureHandlingPolicy;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.KernelLifecycle;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class DefaultActivatorTest {

    @Mock
    Kernel kernel;
    @Mock
    Context context;
    @Mock
    Configuration config;
    @Mock
    DeploymentDirectoryManager deploymentDirectoryManager;
    @Mock
    EndpointSwitchState endpointSwitchState;

    DefaultActivator defaultActivator;

    @BeforeEach
    void beforeEach() {
        doReturn(deploymentDirectoryManager).when(context).get(DeploymentDirectoryManager.class);
        // A deployment directory exists for every deployment DeploymentService submits.
        lenient().doReturn(true).when(deploymentDirectoryManager).hasUnfinishedDeployment();

        lenient().doReturn(endpointSwitchState).when(context).get(EndpointSwitchState.class);
        doReturn(context).when(kernel).getContext();
        lenient().doReturn(config).when(kernel).getConfig();
        lenient().doReturn(Collections.emptyList()).when(kernel).orderedDependencies();
        defaultActivator = spy(new DefaultActivator(kernel));
    }

    @Test
    void GIVEN_rollback_not_requested_WHEN_activate_THEN_snapshot_taken() throws Exception {
        CompletableFuture<DeploymentResult> future = new CompletableFuture<>();

        defaultActivator.activate(createNewConfig(), createDeployment(FailureHandlingPolicy.DO_NOTHING),
                System.currentTimeMillis(), future);

        verify(deploymentDirectoryManager).takeRollbackSnapshot();
    }

    @Test
    void GIVEN_endpoint_switch_with_DO_NOTHING_WHEN_failure_THEN_rollback_performed(
            ExtensionContext extContext) throws Exception {
        ignoreExceptionOfType(extContext, ServiceUpdateException.class);
        when(endpointSwitchState.isEndpointSwitchDeployment("testId")).thenReturn(true);

        CompletableFuture<DeploymentResult> future = new CompletableFuture<>();

        // First call: updateConfiguration. Second call: setDesiredState returns failure.
        AtomicInteger callCount = new AtomicInteger();
        doAnswer(i -> {
            if (callCount.getAndIncrement() == 0) {
                ((Crashable) i.getArgument(0)).run();
                return null;
            }
            return new ServiceUpdateException("test failure");
        }).when(context).runOnPublishQueueAndWait(any(Crashable.class));

        doReturn(-1L).when(defaultActivator).rollbackConfig(any(), any());

        defaultActivator.activate(createNewConfig(), createDeployment(FailureHandlingPolicy.DO_NOTHING),
                System.currentTimeMillis(), future);

        verify(defaultActivator).rollbackConfig(any(), any());
    }

    @Test
    void GIVEN_non_endpoint_switch_with_DO_NOTHING_WHEN_failure_THEN_no_rollback(
            ExtensionContext extContext) throws Exception {
        ignoreExceptionOfType(extContext, ServiceUpdateException.class);
        when(endpointSwitchState.isEndpointSwitchDeployment("testId")).thenReturn(false);

        CompletableFuture<DeploymentResult> future = new CompletableFuture<>();

        AtomicInteger callCount = new AtomicInteger();
        doAnswer(i -> {
            if (callCount.getAndIncrement() == 0) {
                ((Crashable) i.getArgument(0)).run();
                return null;
            }
            return new ServiceUpdateException("test failure");
        }).when(context).runOnPublishQueueAndWait(any(Crashable.class));

        defaultActivator.activate(createNewConfig(), createDeployment(FailureHandlingPolicy.DO_NOTHING),
                System.currentTimeMillis(), future);

        assertEquals(DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_NOT_REQUESTED,
                future.get().getDeploymentStatus());
        // The snapshot is taken regardless of policy so an interrupted deployment stays undoable; the
        // policy governs only whether a failed deployment rolls back.
        verify(deploymentDirectoryManager).takeRollbackSnapshot();
    }

    private Deployment createDeployment(FailureHandlingPolicy policy) {
        DeploymentDocument doc = DeploymentDocument.builder()
                .deploymentId("testId")
                .failureHandlingPolicy(policy)
                .timestamp(0L)
                .build();
        Deployment deployment = mock(Deployment.class);
        when(deployment.getDeploymentDocumentObj()).thenReturn(doc);
        when(deployment.getId()).thenReturn("testId");
        return deployment;
    }

    @Test
    void GIVEN_deployment_cancelled_after_merge_WHEN_services_wait_returns_THEN_deployment_stops() throws Exception {
        lenient().doReturn(Collections.emptySet()).when(kernel).findAutoStartableServicesToTrack();

        CompletableFuture<DeploymentResult> future = spy(new CompletableFuture<>());

        // Cancel while the merge's listeners are running, i.e. after the configuration has been applied
        // but before the services have been waited on — the window a superseding deployment lands in.
        AtomicInteger callCount = new AtomicInteger();
        doAnswer(i -> {
            ((Crashable) i.getArgument(0)).run();
            if (callCount.getAndIncrement() == 1) {
                future.cancel(true);
            }
            return null;
        }).when(context).runOnPublishQueueAndWait(any(Crashable.class));

        defaultActivator.activate(createNewConfig(), createDeployment(FailureHandlingPolicy.ROLLBACK),
                System.currentTimeMillis(), future);

        // Neither completed as successful nor rolled back: a cancelled deployment stops where it is rather
        // than removing services alongside the deployment that replaced it.
        verify(future, never()).complete(any());
        verify(defaultActivator, never()).rollback(any(), any(), any(), any());
    }

    private Map<String, Object> createNewConfig() {
        Map<String, Object> newConfig = new HashMap<>();
        newConfig.put(SERVICES_NAMESPACE_TOPIC, new HashMap<>());
        return newConfig;
    }

    @Test
    void GIVEN_builtins_missing_from_dependency_graph_WHEN_deployment_merge_runs_THEN_builtin_config_is_retained()
            throws Exception {
        // The dependency graph is damaged: orderedDependencies() contains no builtin services
        // (beforeEach stubs it to an empty list) even though the builtin services are running.
        UpdateBehaviorTree behavior = defaultActivator.createDeploymentMergeBehavior(
                System.currentTimeMillis(), createNewConfig());

        // Builtin merge protection must be present regardless of the dependency graph state
        Map<String, UpdateBehaviorTree> servicesOverrides =
                behavior.getChildOverride().get(SERVICES_NAMESPACE_TOPIC).getChildOverride();
        for (String builtinName : KernelLifecycle.AUTOSTART_BUILTIN_SERVICE_NAMES) {
            UpdateBehaviorTree override = servicesOverrides.get(builtinName);
            assertNotNull(override, builtinName + " must have a merge behavior override");
            assertEquals(UpdateBehaviorTree.UpdateBehavior.MERGE, override.getBehavior(),
                    builtinName + " must have a MERGE override");
        }

        // Applying a deployment merge which does not contain the builtin must retain its config subtree
        try (Context realContext = new Context()) {
            Configuration realConfig = new Configuration(realContext);
            realConfig.lookup(SERVICES_NAMESPACE_TOPIC, "DeploymentService", "GroupToRootComponents",
                    "thinggroup/testGroup", "testComponent", "version").withValue("1.0.0");
            realConfig.lookup(SERVICES_NAMESPACE_TOPIC, "removedService", "version").withValue("1.0.0");
            realContext.waitForPublishQueueToClear();

            realConfig.updateMap(createNewConfig(), behavior);
            realContext.waitForPublishQueueToClear();

            assertNotNull(realConfig.findTopics(SERVICES_NAMESPACE_TOPIC, "DeploymentService",
                            "GroupToRootComponents"),
                    "builtin service config must survive a merge while missing from the dependency graph");
            assertNull(realConfig.findTopics(SERVICES_NAMESPACE_TOPIC, "removedService"),
                    "non-builtin service absent from the deployment must still be removed");
        }
    }

    @Test
    void GIVEN_builtin_config_missing_from_snapshot_WHEN_rollback_replays_THEN_builtin_config_is_retained()
            throws Exception {
        UpdateBehaviorTree behavior = defaultActivator.createRollbackMergeBehavior();

        try (Context realContext = new Context()) {
            Configuration realConfig = new Configuration(realContext);
            realConfig.lookup(SERVICES_NAMESPACE_TOPIC, "FleetStatusService", "sequenceNumber").withValue(34);
            realConfig.lookup(SERVICES_NAMESPACE_TOPIC, "removedService", "version").withValue("1.0.0");
            realContext.waitForPublishQueueToClear();

            // Snapshot tlog which contains neither the builtin nor the non-builtin service, as produced
            // when the snapshot was dumped while the builtin's config subtree was detached from the tree
            Path snapshot = Files.createTempFile("rollback_snapshot", ".tlog");
            try {
                Files.write(snapshot, Collections.singletonList(
                        "{\"TS\":1,\"TP\":[\"system\",\"thingName\"],\"W\":\"changed\",\"V\":\"testThing\"}"),
                        StandardCharsets.UTF_8);

                ConfigurationReader.updateFromTLog(realConfig, snapshot, true, null, behavior);
                realContext.waitForPublishQueueToClear();

                assertNotNull(realConfig.find(SERVICES_NAMESPACE_TOPIC, "FleetStatusService", "sequenceNumber"),
                        "builtin service config must survive a rollback whose snapshot lacks it");
                assertNull(realConfig.findTopics(SERVICES_NAMESPACE_TOPIC, "removedService"),
                        "non-builtin service absent from the snapshot must still be discarded");
            } finally {
                Files.deleteIfExists(snapshot);
            }
        }
    }
}
