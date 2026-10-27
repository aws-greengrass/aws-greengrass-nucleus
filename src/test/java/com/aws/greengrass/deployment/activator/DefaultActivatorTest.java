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
import com.aws.greengrass.deployment.DeploymentConfigMerger;
import com.aws.greengrass.deployment.DeploymentDirectoryManager;
import com.aws.greengrass.deployment.DeviceConfiguration;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class DefaultActivatorTest {
    private static final String DEPLOYMENT_ID = "testId";

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
    @Mock
    DeviceConfiguration deviceConfiguration;

    DefaultActivator defaultActivator;

    @BeforeEach
    void beforeEach() {
        doReturn(deploymentDirectoryManager).when(context).get(DeploymentDirectoryManager.class);
        // A deployment directory, named after the deployment, exists for every deployment DeploymentService submits.
        lenient().doReturn(true).when(deploymentDirectoryManager).hasUnfinishedDeployment();
        lenient().doReturn(true).when(deploymentDirectoryManager).isOngoingDeployment(DEPLOYMENT_ID);

        lenient().doReturn(endpointSwitchState).when(context).get(EndpointSwitchState.class);
        lenient().doReturn(DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS).when(deviceConfiguration)
                .getEffectiveMaxInterruptedDeploymentAttempts();
        lenient().doReturn(deviceConfiguration).when(context).get(DeviceConfiguration.class);
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

        verify(deploymentDirectoryManager).takeRollbackSnapshot(DEPLOYMENT_ID);
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
        verify(deploymentDirectoryManager).takeRollbackSnapshot(DEPLOYMENT_ID);
    }

    @Test
    void GIVEN_deployment_WHEN_activate_THEN_attempt_counted_once_snapshot_is_durable_and_before_merge()
            throws Exception {
        CompletableFuture<DeploymentResult> future = new CompletableFuture<>();

        defaultActivator.activate(createNewConfig(), createDeployment(FailureHandlingPolicy.ROLLBACK),
                System.currentTimeMillis(), future);

        // A crash between the snapshot and the count counts an attempt that changed nothing; a crash between the
        // count and the merge likewise. No order leaves an applied attempt uncounted.
        InOrder inOrder = inOrder(deploymentDirectoryManager, context);
        inOrder.verify(deploymentDirectoryManager).takeRollbackSnapshot(DEPLOYMENT_ID);
        inOrder.verify(deploymentDirectoryManager).recordProcessingAttempt(DEPLOYMENT_ID,
                DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS, true);
        // The first publish-queue task is the merge.
        inOrder.verify(context, calls(1)).runOnPublishQueueAndWait(any(Crashable.class));
    }

    @Test
    void GIVEN_cancelled_once_its_attempt_is_counted_WHEN_activate_THEN_deployment_not_merged() throws Exception {
        CompletableFuture<DeploymentResult> future = new CompletableFuture<>();
        // The cancel lands after the check before counting, while the attempt is being recorded.
        doAnswer(invocation -> {
            future.cancel(true);
            return 1;
        }).when(deploymentDirectoryManager).recordProcessingAttempt(any(), anyInt(), anyBoolean());

        defaultActivator.activate(createNewConfig(), createDeployment(FailureHandlingPolicy.ROLLBACK),
                System.currentTimeMillis(), future);

        // A deployment that replaced or cancelled this one owns the configuration now.
        verify(context, never()).runOnPublishQueueAndWait(any(Crashable.class));
    }

    @Test
    void GIVEN_overtaken_once_its_attempt_is_counted_WHEN_activate_THEN_deployment_not_merged() throws Exception {
        CompletableFuture<DeploymentResult> future = new CompletableFuture<>();
        // A superseding deployment's directory replaced this one's on the ongoing link after the attempt was counted.
        doAnswer(invocation -> {
            doReturn(false).when(deploymentDirectoryManager).isOngoingDeployment(DEPLOYMENT_ID);
            return 1;
        }).when(deploymentDirectoryManager).recordProcessingAttempt(any(), anyInt(), anyBoolean());

        defaultActivator.activate(createNewConfig(), createDeployment(FailureHandlingPolicy.ROLLBACK),
                System.currentTimeMillis(), future);

        verify(context, never()).runOnPublishQueueAndWait(any(Crashable.class));
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, future.get().getDeploymentStatus());
    }

    @Test
    void GIVEN_attempt_cannot_be_recorded_WHEN_activate_THEN_deployment_fails_without_merging(
            ExtensionContext extContext) throws Exception {
        ignoreExceptionOfType(extContext, IOException.class);
        IOException diskFull = new IOException("disk full");
        doThrow(diskFull).when(deploymentDirectoryManager).recordProcessingAttempt(any(), anyInt(), anyBoolean());
        CompletableFuture<DeploymentResult> future = new CompletableFuture<>();

        defaultActivator.activate(createNewConfig(), createDeployment(FailureHandlingPolicy.ROLLBACK),
                System.currentTimeMillis(), future);

        // An attempt that is not counted cannot be capped, so it must not be applied either.
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, future.get().getDeploymentStatus());
        assertEquals(diskFull, future.get().getFailureCause().getCause());
        verify(context, never()).runOnPublishQueueAndWait(any(Crashable.class));
    }

    @Test
    void GIVEN_nucleus_stopping_WHEN_attempt_recorded_THEN_deployment_not_applied_and_left_without_a_result()
            throws Exception {
        doThrow(new InterruptedIOException("Interrupted while writing the processing state"))
                .when(deploymentDirectoryManager).recordProcessingAttempt(any(), anyInt(), anyBoolean());
        CompletableFuture<DeploymentResult> future = new CompletableFuture<>();

        defaultActivator.activate(createNewConfig(), createDeployment(FailureHandlingPolicy.ROLLBACK),
                System.currentTimeMillis(), future);

        // Nothing is applied, and, like an activation the nucleus interrupts, the attempt ends without a result:
        // nothing is reported and stale-version cleanup is skipped.
        assertNull(future.get());
        verify(context, never()).runOnPublishQueueAndWait(any(Crashable.class));
    }

    @Test
    void GIVEN_nucleus_stopping_WHEN_snapshot_taken_THEN_deployment_not_applied_and_left_without_a_result()
            throws Exception {
        doThrow(new InterruptedIOException("Interrupted while taking the rollback snapshot"))
                .when(deploymentDirectoryManager).takeRollbackSnapshot(DEPLOYMENT_ID);
        CompletableFuture<DeploymentResult> future = new CompletableFuture<>();

        defaultActivator.activate(createNewConfig(), createDeployment(FailureHandlingPolicy.ROLLBACK),
                System.currentTimeMillis(), future);

        assertNull(future.get());
        verify(deploymentDirectoryManager, never()).recordProcessingAttempt(any(), anyInt(), anyBoolean());
        verify(context, never()).runOnPublishQueueAndWait(any(Crashable.class));
    }

    @Test
    void GIVEN_snapshot_cannot_be_taken_WHEN_activate_THEN_attempt_not_counted(ExtensionContext extContext)
            throws Exception {
        ignoreExceptionOfType(extContext, IOException.class);
        doThrow(new IOException("disk full")).when(deploymentDirectoryManager).takeRollbackSnapshot(DEPLOYMENT_ID);
        CompletableFuture<DeploymentResult> future = new CompletableFuture<>();

        defaultActivator.activate(createNewConfig(), createDeployment(FailureHandlingPolicy.ROLLBACK),
                System.currentTimeMillis(), future);

        assertEquals(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, future.get().getDeploymentStatus());
        verify(deploymentDirectoryManager, never()).recordProcessingAttempt(any(), anyInt(), anyBoolean());
        verify(context, never()).runOnPublishQueueAndWait(any(Crashable.class));
    }

    @Test
    void GIVEN_failure_handling_WHEN_attempt_counted_THEN_whether_a_failure_rolls_back_is_recorded() throws Exception {
        defaultActivator.activate(createNewConfig(), createDeployment(FailureHandlingPolicy.DO_NOTHING),
                System.currentTimeMillis(), new CompletableFuture<>());
        verify(deploymentDirectoryManager).recordProcessingAttempt(DEPLOYMENT_ID, DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS,
                false);

        // An endpoint switch rolls back on failure whatever its policy, to restore the original endpoint.
        when(endpointSwitchState.isEndpointSwitchDeployment("testId")).thenReturn(true);
        defaultActivator.activate(createNewConfig(), createDeployment(FailureHandlingPolicy.DO_NOTHING),
                System.currentTimeMillis(), new CompletableFuture<>());
        verify(deploymentDirectoryManager).recordProcessingAttempt(DEPLOYMENT_ID, DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS,
                true);
    }

    @Test
    void GIVEN_deployment_cancelled_after_snapshot_WHEN_activating_THEN_attempt_not_recorded_or_applied()
            throws Exception {
        CompletableFuture<DeploymentResult> future = new CompletableFuture<>();
        // Superseded while the snapshot was taken: the deployment that replaced it owns the directory now.
        doAnswer(i -> future.cancel(false)).when(deploymentDirectoryManager).takeRollbackSnapshot(DEPLOYMENT_ID);

        defaultActivator.activate(createNewConfig(), createDeployment(FailureHandlingPolicy.ROLLBACK),
                System.currentTimeMillis(), future);

        verify(deploymentDirectoryManager, never()).recordProcessingAttempt(any(), anyInt(), anyBoolean());
        verify(context, never()).runOnPublishQueueAndWait(any(Crashable.class));
    }

    @Test
    void GIVEN_ongoing_link_names_another_deployment_WHEN_activating_THEN_nothing_written_or_applied()
            throws Exception {
        doReturn(false).when(deploymentDirectoryManager).isOngoingDeployment(DEPLOYMENT_ID);
        CompletableFuture<DeploymentResult> future = new CompletableFuture<>();

        defaultActivator.activate(createNewConfig(), createDeployment(FailureHandlingPolicy.DO_NOTHING),
                System.currentTimeMillis(), future);

        // A superseding deployment overtook this activation: writing into its directory would corrupt its record.
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, future.get().getDeploymentStatus());
        verify(deploymentDirectoryManager, never()).takeRollbackSnapshot(any());
        verify(deploymentDirectoryManager, never()).recordProcessingAttempt(any(), anyInt(), anyBoolean());
        verify(context, never()).runOnPublishQueueAndWait(any(Crashable.class));
    }

    @Test
    void GIVEN_deployment_cancelled_during_rollback_WHEN_services_wait_returns_THEN_services_not_removed()
            throws Exception {
        DeploymentConfigMerger.AggregateServicesChangeManager rollbackManager =
                mock(DeploymentConfigMerger.AggregateServicesChangeManager.class);
        CompletableFuture<DeploymentResult> future = spy(new CompletableFuture<>());
        // Cancelled once the rollback has been merged, the window in which waitForServicesToStart returns early.
        doAnswer(i -> {
            future.cancel(false);
            return Collections.emptySet();
        }).when(rollbackManager).servicesToTrack();
        doReturn(Collections.emptySet()).when(kernel).findDependers(any());
        doReturn(1L).when(defaultActivator).rollbackConfig(any(), any());
        doReturn(null).when(context).runOnPublishQueueAndWait(any(Crashable.class));

        defaultActivator.rollback(createDocument(FailureHandlingPolicy.ROLLBACK), future,
                new ServiceUpdateException("failed"), rollbackManager);

        // Neither completed nor removing services alongside the deployment that replaced this one.
        verify(rollbackManager, never()).removeObsoleteServices();
        verify(future, never()).complete(any());
    }

    @Test
    void GIVEN_deployment_already_cancelled_WHEN_rollback_requested_THEN_nothing_rolled_back() throws Exception {
        CompletableFuture<DeploymentResult> future = new CompletableFuture<>();
        future.cancel(false);
        DeploymentConfigMerger.AggregateServicesChangeManager rollbackManager =
                mock(DeploymentConfigMerger.AggregateServicesChangeManager.class);

        defaultActivator.rollback(createDocument(FailureHandlingPolicy.ROLLBACK), future,
                new ServiceUpdateException("failed"), rollbackManager);

        // The deployment that replaced this one owns the configuration now.
        verify(defaultActivator, never()).rollbackConfig(any(), any());
        verify(rollbackManager, never()).removeObsoleteServices();
    }

    @Test
    void GIVEN_no_deployment_directory_WHEN_deployment_that_rolls_back_activated_THEN_fails_before_merging()
            throws Exception {
        doReturn(false).when(deploymentDirectoryManager).hasUnfinishedDeployment();
        doReturn(false).when(deploymentDirectoryManager).isOngoingDeployment(DEPLOYMENT_ID);
        CompletableFuture<DeploymentResult> future = new CompletableFuture<>();

        defaultActivator.activate(createNewConfig(), createDeployment(FailureHandlingPolicy.ROLLBACK),
                System.currentTimeMillis(), future);

        // As on main: a deployment that rolls back on failure cannot be applied without a snapshot to roll back to.
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, future.get().getDeploymentStatus());
        verify(context, never()).runOnPublishQueueAndWait(any(Crashable.class));
    }

    @Test
    void GIVEN_no_deployment_directory_WHEN_deployment_that_does_not_roll_back_activated_THEN_applied()
            throws Exception {
        doReturn(false).when(deploymentDirectoryManager).hasUnfinishedDeployment();
        doReturn(false).when(deploymentDirectoryManager).isOngoingDeployment(DEPLOYMENT_ID);
        lenient().doReturn(Collections.emptySet()).when(kernel).findAutoStartableServicesToTrack();
        CompletableFuture<DeploymentResult> future = new CompletableFuture<>();

        defaultActivator.activate(createNewConfig(), createDeployment(FailureHandlingPolicy.DO_NOTHING),
                System.currentTimeMillis(), future);

        // Nothing tracks it and a failure would not roll it back, so it is applied without a snapshot, as on main.
        verify(deploymentDirectoryManager, never()).takeRollbackSnapshot(any());
        verify(deploymentDirectoryManager, never()).recordProcessingAttempt(any(), anyInt(), anyBoolean());
        verify(context, atLeastOnce()).runOnPublishQueueAndWait(any(Crashable.class));
    }

    private static DeploymentDocument createDocument(FailureHandlingPolicy policy) {
        return DeploymentDocument.builder()
                .deploymentId(DEPLOYMENT_ID)
                .failureHandlingPolicy(policy)
                .timestamp(0L)
                .build();
    }

    private Deployment createDeployment(FailureHandlingPolicy policy) {
        DeploymentDocument doc = createDocument(policy);
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
