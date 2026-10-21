/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.componentmanager.DependencyResolver;
import com.aws.greengrass.componentmanager.KernelConfigResolver;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.exceptions.PackagingException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.converter.DeploymentDocumentConverter;
import com.aws.greengrass.deployment.exceptions.DeploymentTaskFailureException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.exceptions.TLSAuthException;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.http.HttpStatusCode;
import software.amazon.awssdk.services.greengrassv2data.model.GreengrassV2DataException;
import software.amazon.awssdk.utils.ImmutableMap;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.DEFAULT;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class DeploymentTaskTest {

    private static final String COMPONENT_2_ROOT_PACKAGE_NAME = "component2";
    private static Context context;
    private final DeploymentDocument deploymentDocument =
            DeploymentDocument.builder().deploymentId("TestDeployment").timestamp(System.currentTimeMillis())
                    .groupName(DeploymentDocumentConverter.LOCAL_DEPLOYMENT_GROUP_NAME).build();
    private final Logger logger = LogManager.getLogger("unit test");
    @Mock
    private DependencyResolver mockDependencyResolver;
    @Mock
    private ComponentManager mockComponentManager;
    @Mock
    private KernelConfigResolver mockKernelConfigResolver;
    @Mock
    private DeploymentConfigMerger mockDeploymentConfigMerger;
    @Mock
    private Future<List<ComponentIdentifier>> mockResolveDependencyFuture;
    @Mock
    private Future<Void> mockPreparePackagesFuture;
    @Mock
    private Future<DeploymentResult> mockMergeConfigFuture;
    @Mock
    private Topics mockDeploymentServiceConfig;
    @Mock
    private ExecutorService mockExecutorService;
    @Mock
    private DeploymentDocumentDownloader deploymentDocumentDownloader;

    private Topics mockGroupToRootConfig;
    private Topics mockGroupMembership;
    private DefaultDeploymentTask deploymentTask;

    @Mock
    private ThingGroupHelper mockThingGroupHelper;
    @Mock
    private DeviceConfiguration mockDeviceConfiguration;
    @Captor
    private ArgumentCaptor<Map<String, String>> versionsCaptor;

    @BeforeAll
    static void setupContext() {
        context = new Context();
    }

    @AfterAll
    static void cleanContext() throws IOException {
        context.close();
    }

    @BeforeEach
    void setup() {
        mockGroupToRootConfig = Topics.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        mockGroupToRootConfig.lookupTopics("group1", COMPONENT_2_ROOT_PACKAGE_NAME)
                .replaceAndWait(ImmutableMap.of(DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));

        mockGroupMembership = Topics.of(context, DeploymentService.GROUP_MEMBERSHIP_TOPICS, null);
        lenient().when(mockDeploymentServiceConfig.lookupTopics(eq(DeploymentService.GROUP_MEMBERSHIP_TOPICS)))
                .thenReturn(mockGroupMembership);
        lenient().when(mockDeploymentServiceConfig.lookupTopics(eq(DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS)))
                .thenReturn(mockGroupToRootConfig);
        deploymentTask =
                new DefaultDeploymentTask(mockDependencyResolver, mockComponentManager, mockKernelConfigResolver,
                        mockDeploymentConfigMerger, logger,
                        new Deployment(deploymentDocument, Deployment.DeploymentType.IOT_JOBS, "jobId", DEFAULT),
                        mockDeploymentServiceConfig, mockExecutorService, deploymentDocumentDownloader, mockThingGroupHelper, mockDeviceConfiguration);
    }

    @Test
    void GIVEN_deploymentDocument_WHEN_start_deploymentTask_THEN_succeeds() throws Exception {
        when(mockComponentManager.preparePackages(anyList())).thenReturn(CompletableFuture.completedFuture(null));
        when(mockExecutorService.submit(any(Callable.class)))
                .thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));
        when(mockDeploymentConfigMerger.mergeInNewConfig(any(), any(), any(Long.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        deploymentTask.call();
        verify(mockComponentManager).preparePackages(anyList());
        verify(mockKernelConfigResolver).resolve(anyList(), eq(deploymentDocument), anyList(), any(Long.class));
        verify(mockDeploymentConfigMerger).mergeInNewConfig(any(), any(), any(Long.class));
    }

    @Test
    void GIVEN_deploymentDocument_WHEN_thingGroupHelper_return_forbidden_THEN_succeeds(ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, GreengrassV2DataException.class);
        when(mockComponentManager.preparePackages(anyList())).thenReturn(CompletableFuture.completedFuture(null));
        when(mockExecutorService.submit(any(Callable.class)))
                .thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));
        when(mockDeploymentConfigMerger.mergeInNewConfig(any(), any(), any(Long.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(mockThingGroupHelper.listThingGroupsForDevice(anyInt()))
                .thenThrow(GreengrassV2DataException.builder().statusCode(HttpStatusCode.FORBIDDEN).build());

        deploymentTask.call();

        verify(mockComponentManager).preparePackages(anyList());
        verify(mockKernelConfigResolver).resolve(anyList(), eq(deploymentDocument), anyList(), any(Long.class));
        verify(mockDeploymentConfigMerger).mergeInNewConfig(any(), any(), any(Long.class));
    }

    @Test
    void GIVEN_deploymentDocument_WHEN_thingGroupHelper_throws_error_THEN_deployment_result_has_chain_of_error_messages(ExtensionContext context)
            throws Exception {
        ignoreExceptionUltimateCauseOfType(context, GreengrassV2DataException.class);

        when(mockThingGroupHelper.listThingGroupsForDevice(anyInt()))
                .thenThrow(GreengrassV2DataException.builder().message("Original error message").build());

        DeploymentResult result = deploymentTask.call();
        Throwable failureCause = result.getFailureCause();
        String failureMessage = Utils.generateFailureMessage(failureCause);
        assertEquals("Error fetching thing group information. Original error message", failureMessage);
    }

    @Test
    void GIVEN_deploymentDocument_WHEN_thingGroupHelper_interrupted_THEN_deployment_task_interrupted() throws Exception {
        when(mockThingGroupHelper.listThingGroupsForDevice(anyInt())).thenThrow(InterruptedException.class);
        assertThrows(InterruptedException.class, deploymentTask::call);
    }

    @Test
    void GIVEN_deploymentDocument_WHEN_resolveDependencies_errored_THEN_deploymentTask_aborted(ExtensionContext context)
            throws Exception {
        ignoreExceptionUltimateCauseOfType(context, PackagingException.class);

        when(mockExecutorService.submit(any(Callable.class))).thenReturn(mockResolveDependencyFuture);
        when(mockResolveDependencyFuture.get())
                .thenThrow(new ExecutionException(new PackagingException("unknown package")));
        DeploymentResult result = deploymentTask.call();
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, result.getDeploymentStatus());
        assertTrue(result.getFailureCause() instanceof PackagingException);

        verify(mockComponentManager, times(0)).preparePackages(anyList());
        verify(mockKernelConfigResolver, times(0)).resolve(anyList(), eq(deploymentDocument), anyList(), any(Long.class));
        verify(mockDeploymentConfigMerger, times(0)).mergeInNewConfig(any(), any(), any(Long.class));
    }

    @Test
    void GIVEN_deploymentDocument_WHEN_resolve_kernel_config_throws_PackageLoadingException_THEN_deploymentTask_aborted(
            ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, PackageLoadingException.class);

        when(mockExecutorService.submit(any(Callable.class)))
                .thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));
        when(mockComponentManager.preparePackages(anyList())).thenReturn(CompletableFuture.completedFuture(null));
        when(mockKernelConfigResolver.resolve(anyList(), eq(deploymentDocument), anyList(), any(Long.class)))
                .thenThrow(new PackageLoadingException("failed to load package"));

        DeploymentResult result = deploymentTask.call();
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, result.getDeploymentStatus());
        assertTrue(result.getFailureCause() instanceof PackageLoadingException);
        verify(mockComponentManager).preparePackages(anyList());
        verify(mockKernelConfigResolver).resolve(anyList(), eq(deploymentDocument), anyList(), any(Long.class));
        verify(mockDeploymentConfigMerger, times(0)).mergeInNewConfig(any(), any(), any(Long.class));
    }

    @Test
    void GIVEN_deployment_task_interrupted_WHEN_preparePackages_not_started_THEN_do_nothing(ExtensionContext context)
            throws Exception {
        ignoreExceptionUltimateCauseOfType(context, InterruptedException.class);

        when(mockExecutorService.submit(any(Callable.class))).thenReturn(mockResolveDependencyFuture);
        when(mockResolveDependencyFuture.get()).thenThrow(new ExecutionException(new InterruptedException()));

        assertThrows(InterruptedException.class, () -> deploymentTask.call());
        verify(mockComponentManager, times(0)).preparePackages(anyList());
        verify(mockKernelConfigResolver, times(0)).resolve(anyList(), eq(deploymentDocument), anyList(), any(Long.class));
        verify(mockDeploymentConfigMerger, times(0)).mergeInNewConfig(any(), any(), any(Long.class));
    }

    @Test
    void GIVEN_deployment_task_interrupted_WHEN_preparePackages_in_progress_THEN_cancel_prepare_packages() throws Exception {
        when(mockExecutorService.submit(any(Callable.class)))
                .thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));
        CountDownLatch preparePackagesInvoked = new CountDownLatch(1);
        when(mockComponentManager.preparePackages(anyList())).thenAnswer(invocationOnMock -> {
            preparePackagesInvoked.countDown();
            return mockPreparePackagesFuture;
        });
        when(mockPreparePackagesFuture.get()).thenAnswer(invocationOnMock -> {
            Thread.sleep(1000);
            return null;
        });
        FutureTask<DeploymentResult> futureTask = new FutureTask<>(deploymentTask);
        Thread t = new Thread(futureTask);
        t.start();
        assertTrue(preparePackagesInvoked.await(3, TimeUnit.SECONDS));
        t.interrupt();

        verify(mockComponentManager).preparePackages(anyList());
        verify(mockPreparePackagesFuture, timeout(5000)).cancel(true);
        verify(mockKernelConfigResolver, times(0)).resolve(anyList(), eq(deploymentDocument), anyList(), any(Long.class));
        verify(mockDeploymentConfigMerger, times(0)).mergeInNewConfig(any(), any(), any(Long.class));
    }

    @Test
    void GIVEN_deployment_task_interrupted_WHEN_preparePackages_done_merge_not_started_THEN_do_nothing() throws Exception {
        when(mockExecutorService.submit(any(Callable.class)))
                .thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));
        CountDownLatch resolveConfigInvoked = new CountDownLatch(1);
        when(mockComponentManager.preparePackages(anyList())).thenReturn(CompletableFuture.completedFuture(null));
        when(mockKernelConfigResolver.resolve(any(), any(), any(), any(Long.class))).thenAnswer(invocationOnMock -> {
            resolveConfigInvoked.countDown();
            Thread.sleep(1000);
            return Collections.emptyMap();
        });

        FutureTask<DeploymentResult> futureTask = new FutureTask<>(deploymentTask);
        Thread t = new Thread(futureTask);
        Thread.sleep(1000);
        t.start();
        resolveConfigInvoked.await(3, TimeUnit.SECONDS);
        t.interrupt();

        verify(mockComponentManager).preparePackages(anyList());
        verify(mockKernelConfigResolver).resolve(anyList(), eq(deploymentDocument), anyList(), any(Long.class));
        verify(mockDeploymentConfigMerger, times(0)).mergeInNewConfig(any(), any(), any(Long.class));
    }

    @Test
    void GIVEN_deployment_task_interrupted_WHEN_merge_in_progress_THEN_cancel_merge() throws Exception {
        when(mockExecutorService.submit(any(Callable.class)))
                .thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));
        CountDownLatch mergeConfigInvoked = new CountDownLatch(1);
        when(mockComponentManager.preparePackages(anyList())).thenReturn(CompletableFuture.completedFuture(null));
        when(mockDeploymentConfigMerger.mergeInNewConfig(any(), any(), any(Long.class))).thenAnswer(invocationOnMock -> {
            mergeConfigInvoked.countDown();
            return mockMergeConfigFuture;
        });
        when(mockMergeConfigFuture.get()).thenAnswer(invocationOnMock -> {
            Thread.sleep(1000);
            return null;
        });

        FutureTask<DeploymentResult> futureTask = new FutureTask<>(deploymentTask);
        Thread t = new Thread(futureTask);
        t.start();
        assertTrue(mergeConfigInvoked.await(3, TimeUnit.SECONDS));
        t.interrupt();

        verify(mockComponentManager).preparePackages(anyList());
        verify(mockKernelConfigResolver).resolve(anyList(), eq(deploymentDocument), anyList(), any(Long.class));
        verify(mockDeploymentConfigMerger, timeout(4000)).mergeInNewConfig(any(), any(), any(Long.class));
        verify(mockMergeConfigFuture, timeout(5000)).cancel(false);
        // Wait for the finally block to run so the cleanup assertion below is not vacuous.
        t.join(5000);
        assertFalse(t.isAlive());
        verify(mockComponentManager, never()).cleanupStaleVersions(anyBoolean(), any());
    }

    @Test
    void GIVEN_resolved_deployment_WHEN_non_interrupt_failure_with_interrupt_flag_set_THEN_cleanup_skipped(
            ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, PackageLoadingException.class);

        // Resolution succeeds, so desiredPackages is non-null and the unresolved-deployment guard does not apply.
        when(mockExecutorService.submit(any(Callable.class))).thenReturn(CompletableFuture.completedFuture(
                Collections.singletonList(new ComponentIdentifier("component1", new Semver("1.0.0")))));
        when(mockComponentManager.preparePackages(anyList())).thenReturn(CompletableFuture.completedFuture(null));
        // Set the interrupt flag, then exit through a non-interrupt path so deploymentInterrupted stays false.
        // Only the isInterrupted() check in the finally block can skip cleanup here.
        when(mockKernelConfigResolver.resolve(anyList(), eq(deploymentDocument), anyList(), any(Long.class)))
                .thenAnswer(invocation -> {
                    Thread.currentThread().interrupt();
                    throw new PackageLoadingException("failed to load package");
                });

        try {
            DeploymentResult result = deploymentTask.call();
            // Returning a result rather than throwing confirms the non-interrupt exit path was taken.
            assertEquals(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, result.getDeploymentStatus());
            assertTrue(result.getFailureCause() instanceof PackageLoadingException);
            verify(mockComponentManager, never()).cleanupStaleVersions(anyBoolean(), any());
            verify(mockComponentManager, never()).cleanupStaleVersions();
        } finally {
            // Clear the flag so it cannot leak into other tests running on this thread.
            Thread.interrupted();
        }
    }

    @Test
    void GIVEN_successful_deployment_WHEN_completed_THEN_cleanup_called_with_result() throws Exception {
        when(mockComponentManager.preparePackages(anyList())).thenReturn(CompletableFuture.completedFuture(null));
        when(mockExecutorService.submit(any(Callable.class)))
                .thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));
        
        DeploymentResult successResult = new DeploymentResult(DeploymentResult.DeploymentStatus.SUCCESSFUL, null);
        when(mockDeploymentConfigMerger.mergeInNewConfig(any(), any(), any(Long.class)))
                .thenReturn(CompletableFuture.completedFuture(successResult));
        
        DeploymentResult result = deploymentTask.call();
        
        verify(mockComponentManager).cleanupStaleVersions(eq(false), any());
        assertEquals(DeploymentResult.DeploymentStatus.SUCCESSFUL, result.getDeploymentStatus());
    }

    @Test
    void GIVEN_unsuccessful_deployment_WHEN_completed_THEN_cleanup_called_with_unsuccessful_result() throws Exception {
        when(mockComponentManager.preparePackages(anyList())).thenReturn(CompletableFuture.completedFuture(null));
        when(mockExecutorService.submit(any(Callable.class)))
                .thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));
        
        DeploymentResult failedResult = new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, null);
        when(mockDeploymentConfigMerger.mergeInNewConfig(any(), any(), any(Long.class)))
                .thenReturn(CompletableFuture.completedFuture(failedResult));
        
        DeploymentResult result = deploymentTask.call();
        
        verify(mockComponentManager).cleanupStaleVersions(eq(true), any());
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, result.getDeploymentStatus());
    }

    @Test
    void GIVEN_deployment_exception_WHEN_cleanup_called_THEN_cleanup_still_executed() throws Exception {
        when(mockComponentManager.preparePackages(anyList())).thenReturn(CompletableFuture.completedFuture(null));
        when(mockExecutorService.submit(any(Callable.class)))
                .thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));
        when(mockDeploymentConfigMerger.mergeInNewConfig(any(), any(), any(Long.class)))
                .thenThrow(new RuntimeException("Test exception"));
        
        assertThrows(RuntimeException.class, () -> deploymentTask.call());
        
        verify(mockComponentManager).cleanupStaleVersions(eq(true), any());
    }

    @Test
    void GIVEN_deployment_task_interrupted_WHEN_resolveDependencies_in_progress_THEN_stale_versions_not_cleaned_up()
            throws Exception {
        when(mockExecutorService.submit(any(Callable.class))).thenReturn(mockResolveDependencyFuture);
        CountDownLatch resolveDependenciesBlocked = new CountDownLatch(1);
        when(mockResolveDependencyFuture.get()).thenAnswer(invocationOnMock -> {
            resolveDependenciesBlocked.countDown();
            // Block until interrupted, so desiredPackages is never assigned
            Thread.sleep(TimeUnit.SECONDS.toMillis(30));
            return Collections.emptyList();
        });

        FutureTask<DeploymentResult> futureTask = new FutureTask<>(deploymentTask);
        Thread t = new Thread(futureTask);
        t.start();
        assertTrue(resolveDependenciesBlocked.await(3, TimeUnit.SECONDS));
        t.interrupt();

        ExecutionException e = assertThrows(ExecutionException.class, () -> futureTask.get(5, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof InterruptedException);
        verify(mockResolveDependencyFuture).cancel(true);
        verify(mockComponentManager, never()).cleanupStaleVersions(anyBoolean(), any());
        verify(mockComponentManager, never()).cleanupStaleVersions();
    }

    @Test
    void GIVEN_deployment_task_interrupted_WHEN_resolveDependencies_throws_interrupted_cause_THEN_stale_versions_not_cleaned_up(
            ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, InterruptedException.class);

        when(mockExecutorService.submit(any(Callable.class))).thenReturn(mockResolveDependencyFuture);
        when(mockResolveDependencyFuture.get()).thenThrow(new ExecutionException(new InterruptedException()));

        assertThrows(InterruptedException.class, () -> deploymentTask.call());
        verify(mockComponentManager, never()).cleanupStaleVersions(anyBoolean(), any());
        verify(mockComponentManager, never()).cleanupStaleVersions();
    }

    @Test
    void GIVEN_deployment_task_interrupted_WHEN_preparePackages_in_progress_THEN_stale_versions_not_cleaned_up()
            throws Exception {
        when(mockExecutorService.submit(any(Callable.class))).thenReturn(CompletableFuture.completedFuture(
                Collections.singletonList(new ComponentIdentifier("ComponentA", new Semver("1.0.0")))));
        CountDownLatch preparePackagesBlocked = new CountDownLatch(1);
        when(mockComponentManager.preparePackages(anyList())).thenReturn(mockPreparePackagesFuture);
        when(mockPreparePackagesFuture.get()).thenAnswer(invocationOnMock -> {
            preparePackagesBlocked.countDown();
            // Block until interrupted, while downloads would still be in flight
            Thread.sleep(TimeUnit.SECONDS.toMillis(30));
            return null;
        });

        FutureTask<DeploymentResult> futureTask = new FutureTask<>(deploymentTask);
        Thread t = new Thread(futureTask);
        t.start();
        assertTrue(preparePackagesBlocked.await(3, TimeUnit.SECONDS));
        t.interrupt();

        ExecutionException e = assertThrows(ExecutionException.class, () -> futureTask.get(5, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof InterruptedException);
        verify(mockPreparePackagesFuture).cancel(true);
        verify(mockComponentManager, never()).cleanupStaleVersions(anyBoolean(), any());
        verify(mockComponentManager, never()).cleanupStaleVersions();
    }

    @Test
    void GIVEN_successful_deployment_WHEN_completed_THEN_cleanup_called_with_current_deployment_versions()
            throws Exception {
        when(mockExecutorService.submit(any(Callable.class))).thenReturn(CompletableFuture.completedFuture(
                Arrays.asList(new ComponentIdentifier("ComponentA", new Semver("1.0.0")),
                        new ComponentIdentifier("ComponentB", new Semver("2.1.0")))));
        when(mockComponentManager.preparePackages(anyList())).thenReturn(CompletableFuture.completedFuture(null));
        DeploymentResult successResult = new DeploymentResult(DeploymentResult.DeploymentStatus.SUCCESSFUL, null);
        when(mockDeploymentConfigMerger.mergeInNewConfig(any(), any(), any(Long.class)))
                .thenReturn(CompletableFuture.completedFuture(successResult));

        DeploymentResult result = deploymentTask.call();

        assertEquals(DeploymentResult.DeploymentStatus.SUCCESSFUL, result.getDeploymentStatus());
        ArgumentCaptor<Boolean> abortedCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(mockComponentManager).cleanupStaleVersions(abortedCaptor.capture(), versionsCaptor.capture());
        assertFalse(abortedCaptor.getValue());
        assertEquals(2, versionsCaptor.getValue().size());
        assertEquals("1.0.0", versionsCaptor.getValue().get("ComponentA"));
        assertEquals("2.1.0", versionsCaptor.getValue().get("ComponentB"));
    }

    @Test
    void GIVEN_deployment_WHEN_resolve_kernel_config_throws_PackageLoadingException_THEN_cleanup_called_as_aborted(
            ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, PackageLoadingException.class);

        when(mockExecutorService.submit(any(Callable.class))).thenReturn(CompletableFuture.completedFuture(
                Collections.singletonList(new ComponentIdentifier("ComponentA", new Semver("1.0.0")))));
        when(mockComponentManager.preparePackages(anyList())).thenReturn(CompletableFuture.completedFuture(null));
        when(mockKernelConfigResolver.resolve(anyList(), eq(deploymentDocument), anyList(), any(Long.class)))
                .thenThrow(new PackageLoadingException("failed to load package"));

        DeploymentResult result = deploymentTask.call();

        assertEquals(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, result.getDeploymentStatus());
        verify(mockComponentManager).cleanupStaleVersions(eq(true), versionsCaptor.capture());
        assertEquals(Collections.singletonMap("ComponentA", "1.0.0"), versionsCaptor.getValue());
    }

    @Test
    void GIVEN_deployment_WHEN_resolveDependencies_errored_THEN_stale_versions_not_cleaned_up(ExtensionContext context)
            throws Exception {
        ignoreExceptionUltimateCauseOfType(context, PackagingException.class);

        when(mockExecutorService.submit(any(Callable.class))).thenReturn(mockResolveDependencyFuture);
        when(mockResolveDependencyFuture.get())
                .thenThrow(new ExecutionException(new PackagingException("unknown package")));

        DeploymentResult result = deploymentTask.call();

        assertEquals(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, result.getDeploymentStatus());
        // Components were never resolved, so the versions staged for this deployment are unknown and a
        // cleanup with a null version map would delete them. Cleanup must be skipped.
        verify(mockComponentManager, never()).cleanupStaleVersions(anyBoolean(), any());
        verify(mockComponentManager, never()).cleanupStaleVersions();
    }

    @Test
    void GIVEN_cloud_deployment_WHEN_thingGroupHelper_throws_non_interrupt_error_THEN_failed_and_not_cleaned_up(
            ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, GreengrassV2DataException.class);
        assertFalse(Thread.currentThread().isInterrupted());

        // Not FORBIDDEN and not retryable, on a cloud (IOT_JOBS) deployment: no fallback is taken and the
        // task exits before resolving components.
        when(mockThingGroupHelper.listThingGroupsForDevice(anyInt())).thenThrow(GreengrassV2DataException.builder()
                .statusCode(HttpStatusCode.INTERNAL_SERVER_ERROR).message("service error").build());

        DeploymentResult result = deploymentTask.call();

        assertEquals(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, result.getDeploymentStatus());
        assertTrue(result.getFailureCause() instanceof DeploymentTaskFailureException);
        assertFalse(Thread.currentThread().isInterrupted());
        verify(mockExecutorService, never()).submit(any(Callable.class));
        verify(mockComponentManager, never()).cleanupStaleVersions(anyBoolean(), any());
        verify(mockComponentManager, never()).cleanupStaleVersions();
    }

    @Test
    void GIVEN_deployment_WHEN_thingGroupHelper_throws_wrapped_interrupt_THEN_task_interrupted_and_not_cleaned_up(
            ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, InterruptedException.class);

        // Real-world shape: shutdown interrupt during TLS key-manager loading.
        TLSAuthException wrapped = new TLSAuthException("Get key managers interrupted",
                new InterruptedException("Get key managers interrupted"));
        when(mockThingGroupHelper.listThingGroupsForDevice(anyInt())).thenThrow(wrapped);

        try {
            InterruptedException e = assertThrows(InterruptedException.class, deploymentTask::call);
            assertEquals(wrapped, e.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
            verify(mockExecutorService, never()).submit(any(Callable.class));
            verify(mockComponentManager, never()).cleanupStaleVersions(anyBoolean(), any());
            verify(mockComponentManager, never()).cleanupStaleVersions();
        } finally {
            // Clear the interrupt flag set by the task so it cannot leak into other tests.
            Thread.interrupted();
        }
    }

    @Test
    void GIVEN_deployment_WHEN_thingGroupHelper_throws_deeply_wrapped_interrupt_THEN_task_interrupted(
            ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, InterruptedException.class);

        // InterruptedException three levels down, so a single getCause() check is not enough.
        Exception wrapped = new IllegalStateException("outer",
                new IOException("middle", new TLSAuthException("Get key managers interrupted",
                        new InterruptedException("Get key managers interrupted"))));
        when(mockThingGroupHelper.listThingGroupsForDevice(anyInt())).thenThrow(wrapped);

        try {
            InterruptedException e = assertThrows(InterruptedException.class, deploymentTask::call);
            assertEquals(wrapped, e.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
            verify(mockComponentManager, never()).cleanupStaleVersions(anyBoolean(), any());
            verify(mockComponentManager, never()).cleanupStaleVersions();
        } finally {
            // Clear the interrupt flag set by the task so it cannot leak into other tests.
            Thread.interrupted();
        }
    }

    @Test
    void GIVEN_deployment_WHEN_thingGroupHelper_throws_chain_without_interrupt_THEN_reported_as_failure(
            ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, IOException.class);

        Exception wrapped = new IllegalStateException("outer",
                new TLSAuthException("Get key managers failed", new IOException("keystore unreadable")));
        when(mockThingGroupHelper.listThingGroupsForDevice(anyInt())).thenThrow(wrapped);

        DeploymentResult result = deploymentTask.call();

        assertEquals(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, result.getDeploymentStatus());
        assertTrue(result.getFailureCause() instanceof DeploymentTaskFailureException);
        assertEquals(wrapped, result.getFailureCause().getCause());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void GIVEN_successful_deployment_WHEN_cleanup_throws_THEN_deployment_result_returned(ExtensionContext context)
            throws Exception {
        ignoreExceptionUltimateCauseOfType(context, IllegalStateException.class);

        when(mockExecutorService.submit(any(Callable.class)))
                .thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));
        when(mockComponentManager.preparePackages(anyList())).thenReturn(CompletableFuture.completedFuture(null));
        DeploymentResult successResult = new DeploymentResult(DeploymentResult.DeploymentStatus.SUCCESSFUL, null);
        when(mockDeploymentConfigMerger.mergeInNewConfig(any(), any(), any(Long.class)))
                .thenReturn(CompletableFuture.completedFuture(successResult));
        doThrow(new IllegalStateException("cleanup failed")).when(mockComponentManager)
                .cleanupStaleVersions(anyBoolean(), any());

        DeploymentResult result = deploymentTask.call();

        assertEquals(DeploymentResult.DeploymentStatus.SUCCESSFUL, result.getDeploymentStatus());
        assertNull(result.getFailureCause());
    }
}
