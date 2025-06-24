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
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Utils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.http.HttpStatusCode;
import software.amazon.awssdk.services.greengrassv2data.model.GreengrassV2DataException;
import software.amazon.awssdk.utils.ImmutableMap;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
    }
}
