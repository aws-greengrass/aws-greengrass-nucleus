package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.deployment.converter.DeploymentDocumentConverter;
import com.aws.iot.evergreen.deployment.exceptions.NonRetryableDeploymentTaskFailureException;
import com.aws.iot.evergreen.deployment.exceptions.RetryableDeploymentTaskFailureException;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentResult;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.DependencyResolver;
import com.aws.iot.evergreen.packagemanager.KernelConfigResolver;
import com.aws.iot.evergreen.packagemanager.PackageManager;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageLoadingException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageVersionConflictException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.utils.ImmutableMap;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.isA;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, EGExtension.class})
class DeploymentTaskTest {

    private static final String COMPONENT_1_ROOT_PACKAGE_NAME = "component1";
    private static final String COMPONENT_2_ROOT_PACKAGE_NAME = "component2";

    @Mock
    private DependencyResolver mockDependencyResolver;
    @Mock
    private PackageManager mockPackageManager;
    @Mock
    private KernelConfigResolver mockKernelConfigResolver;
    @Mock
    private DeploymentConfigMerger mockDeploymentConfigMerger;
    @Mock
    private Future<Void> mockPreparePackagesFuture;
    @Mock
    private Future<DeploymentResult> mockMergeConfigFuture;
    @Mock
    private Topics mockDeploymentServiceConfig;
    private Topics mockGroupToRootConfig;
    private static Context context;

    private final DeploymentDocument deploymentDocument =
            DeploymentDocument.builder().deploymentId("TestDeployment").timestamp(System.currentTimeMillis())
                    .groupName(DeploymentDocumentConverter.DEFAULT_GROUP_NAME)
                    .rootPackages(Arrays.asList(COMPONENT_1_ROOT_PACKAGE_NAME)).build();

    private final Logger logger = LogManager.getLogger("unit test");

    private DeploymentTask deploymentTask;

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
//        when(mockKernel.getConfig()).thenReturn(mockConfig);
        mockGroupToRootConfig = Topics.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS,
                null);
        mockGroupToRootConfig.lookupTopics("group1").lookup(COMPONENT_2_ROOT_PACKAGE_NAME)
                .withValue(ImmutableMap.of(DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));

        when(mockDeploymentServiceConfig.lookupTopics(eq(DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS)))
                .thenReturn(mockGroupToRootConfig);
        deploymentTask = new DeploymentTask(mockDependencyResolver, mockPackageManager, mockKernelConfigResolver,
                        mockDeploymentConfigMerger, logger, deploymentDocument, mockDeploymentServiceConfig);
    }

    @Test
    void GIVEN_deploymentDocument_WHEN_start_deploymentTask_THEN_succeeds() throws Exception {

        when(mockPackageManager.preparePackages(anyList())).thenReturn(CompletableFuture.completedFuture(null));
        when(mockDeploymentConfigMerger.mergeInNewConfig(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        deploymentTask.call();
        verify(mockDependencyResolver).resolveDependencies(eq(deploymentDocument), eq(mockGroupToRootConfig));
        verify(mockPackageManager).preparePackages(anyList());
        verify(mockKernelConfigResolver).resolve(anyList(), eq(deploymentDocument), anyList());
        verify(mockDeploymentConfigMerger).mergeInNewConfig(any(), any());
    }

    @Test
    void GIVEN_deploymentDocument_WHEN_resolveDependencies_with_conflicted_dependency_THEN_deploymentTask_aborted()
            throws Exception {
        when(mockDependencyResolver.resolveDependencies(eq(deploymentDocument), eq(mockGroupToRootConfig)))
                .thenThrow(new PackageVersionConflictException(""));
        Exception thrown = assertThrows(NonRetryableDeploymentTaskFailureException.class, () -> deploymentTask.call());
        assertThat(thrown.getCause(), isA(PackageVersionConflictException.class));
        verify(mockDependencyResolver).resolveDependencies(eq(deploymentDocument), eq(mockGroupToRootConfig));

        verify(mockPackageManager, times(0)).preparePackages(anyList());
        verify(mockKernelConfigResolver, times(0)).resolve(anyList(), eq(deploymentDocument), anyList());
        verify(mockDeploymentConfigMerger, times(0)).mergeInNewConfig(any(), any());
    }

    @Test
    void GIVEN_deploymentDocument_WHEN_resolveDependencies_errored_THEN_deploymentTask_aborted() throws Exception {
        when(mockDependencyResolver.resolveDependencies(eq(deploymentDocument), eq(mockGroupToRootConfig)))
                .thenThrow(new PackagingException("mock error"));
        Exception thrown = assertThrows(RetryableDeploymentTaskFailureException.class, () -> deploymentTask.call());
        assertThat(thrown.getCause(), isA(PackagingException.class));
        verify(mockDependencyResolver).resolveDependencies(eq(deploymentDocument), eq(mockGroupToRootConfig));
        verify(mockPackageManager, times(0)).preparePackages(anyList());
        verify(mockKernelConfigResolver, times(0)).resolve(anyList(), eq(deploymentDocument), anyList());
        verify(mockDeploymentConfigMerger, times(0)).mergeInNewConfig(any(), any());
    }

    @Test
    void GIVEN_deploymentDocument_WHEN_resolve_kernel_config_throws_PackageLoadingException_THEN_deploymentTask_aborted()
            throws Exception {
        when(mockPackageManager.preparePackages(anyList())).thenReturn(CompletableFuture.completedFuture(null));
        when(mockKernelConfigResolver.resolve(anyList(), eq(deploymentDocument), anyList()))
                .thenThrow(new PackageLoadingException("failed to load package"));

        Exception thrown = assertThrows(RetryableDeploymentTaskFailureException.class, () -> deploymentTask.call());
        assertThat(thrown.getCause(), isA(PackageLoadingException.class));
        verify(mockDependencyResolver).resolveDependencies(deploymentDocument, mockGroupToRootConfig);
        verify(mockPackageManager).preparePackages(anyList());
        verify(mockKernelConfigResolver).resolve(anyList(), eq(deploymentDocument), anyList());
        verify(mockDeploymentConfigMerger, times(0)).mergeInNewConfig(any(), any());
    }

    @Test
    void GIVEN_deployment_task_interrupted_WHEN_preparePackages_not_started_THEN_do_nothing() throws Exception {
        CountDownLatch resolveDependenciesInvoked= new CountDownLatch(1);
        when(mockDependencyResolver.resolveDependencies(any(), any())).thenAnswer(
                invocationOnMock -> {
                    resolveDependenciesInvoked.countDown();
                    Thread.sleep(2000);
                    return Collections.emptyList();
                });

        FutureTask<DeploymentResult> futureTask = new FutureTask<>(deploymentTask);
        Thread t = new Thread(futureTask);
        t.start();
        resolveDependenciesInvoked.await(3, TimeUnit.SECONDS);
        t.interrupt();

        verify(mockDependencyResolver).resolveDependencies(deploymentDocument, mockGroupToRootConfig);
        verify(mockPackageManager, times(0)).preparePackages(anyList());
        verify(mockKernelConfigResolver, times(0)).resolve(anyList(), eq(deploymentDocument), anyList());
        verify(mockDeploymentConfigMerger, times(0)).mergeInNewConfig(any(), any());
    }

    @Test
    void GIVEN_deployment_task_interrupted_WHEN_preparePackages_in_progress_THEN_cancel_prepare_packages() throws Exception {
        CountDownLatch preparePackagesInvoked = new CountDownLatch(1);
        when(mockPackageManager.preparePackages(anyList())).thenAnswer(
                invocationOnMock -> {
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

        verify(mockDependencyResolver).resolveDependencies(deploymentDocument, mockGroupToRootConfig);
        verify(mockPackageManager).preparePackages(anyList());
        verify(mockPreparePackagesFuture, timeout(5000)).cancel(true);
        verify(mockKernelConfigResolver, times(0)).resolve(anyList(), eq(deploymentDocument), anyList());
        verify(mockDeploymentConfigMerger, times(0)).mergeInNewConfig(any(), any());
    }

    @Test
    void GIVEN_deployment_task_interrupted_WHEN_preparePackages_done_merge_not_started_THEN_do_nothing() throws Exception {
        CountDownLatch resolveConfigInvoked = new CountDownLatch(1);
        when(mockPackageManager.preparePackages(anyList())).thenReturn(CompletableFuture.completedFuture(null));
        when(mockKernelConfigResolver.resolve(any(), any(), any())).thenAnswer(
                invocationOnMock -> {
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

        verify(mockDependencyResolver).resolveDependencies(deploymentDocument, mockGroupToRootConfig);
        verify(mockPackageManager).preparePackages(anyList());
        verify(mockKernelConfigResolver).resolve(anyList(), eq(deploymentDocument), anyList());
        verify(mockDeploymentConfigMerger, times(0)).mergeInNewConfig(any(), any());
    }

    @Test
    void GIVEN_deployment_task_interrupted_WHEN_merge_in_progress_THEN_cancel_merge() throws Exception {
        CountDownLatch mergeConfigInvoked = new CountDownLatch(1);
        when(mockPackageManager.preparePackages(anyList())).thenReturn(CompletableFuture.completedFuture(null));
        when(mockDeploymentConfigMerger.mergeInNewConfig(any(), any())).thenAnswer(
                invocationOnMock -> {
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

        verify(mockDependencyResolver).resolveDependencies(deploymentDocument, mockGroupToRootConfig);
        verify(mockPackageManager).preparePackages(anyList());
        verify(mockKernelConfigResolver).resolve(anyList(), eq(deploymentDocument), anyList());
        verify(mockDeploymentConfigMerger, timeout(4000)).mergeInNewConfig(any(), any());
        verify(mockMergeConfigFuture, timeout(5000)).cancel(false);
    }
}