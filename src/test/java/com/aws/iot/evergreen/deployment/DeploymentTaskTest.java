package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.deployment.exceptions.NonRetryableDeploymentTaskFailureException;
import com.aws.iot.evergreen.deployment.exceptions.RetryableDeploymentTaskFailureException;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.DependencyResolver;
import com.aws.iot.evergreen.packagemanager.KernelConfigResolver;
import com.aws.iot.evergreen.packagemanager.PackageCache;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageVersionConflictException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.isA;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class DeploymentTaskTest {
    @Mock
    private DependencyResolver mockDependencyResolver;
    @Mock
    private PackageCache mockPackageCache;
    @Mock
    private KernelConfigResolver mockKernelConfigResolver;
    @Mock
    private Kernel mockKernel;
    @Mock
    private EvergreenService mainService;
    private DeploymentDocument deploymentDocument =
            DeploymentDocument.builder().deploymentId("TestDeployment").timestamp(System.currentTimeMillis())
                    .rootPackages(Collections.EMPTY_LIST).build();

    private Logger logger = LogManager.getLogger("unit test");

    private DeploymentTask deploymentTask;

    @BeforeEach
    public void setup() {
        deploymentTask =
                new DeploymentTask(mockDependencyResolver, mockPackageCache, mockKernelConfigResolver, mockKernel,
                        logger, deploymentDocument);

        when(mockKernel.getMain()).thenReturn(mainService);
        when(mainService.getDependencies()).thenReturn(Collections.emptyMap());
    }

    @Test
    public void GIVEN_deploymentDocument_WHEN_start_deploymentTask_THEN_succeeds() throws Exception {
        when(mockPackageCache.preparePackages(anyList())).thenReturn(CompletableFuture.completedFuture(null));
        when(mockKernel.mergeInNewConfig(eq("TestDeployment"), anyLong(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(null));
        deploymentTask.call();
        verify(mockDependencyResolver).resolveDependencies(deploymentDocument, Collections.EMPTY_SET);
        verify(mockPackageCache).preparePackages(anyList());
        verify(mockKernelConfigResolver).resolve(anyList(), eq(deploymentDocument), anySet());
        verify(mockKernel).mergeInNewConfig(eq("TestDeployment"), anyLong(), anyMap());
    }

    @Test
    public void GIVEN_deploymentDocument_WHEN_resolveDependencies_with_conflicted_dependency_THEN_deploymentTask_aborted()
            throws Exception {
        when(mockDependencyResolver.resolveDependencies(deploymentDocument, Collections.EMPTY_SET))
                .thenThrow(new PackageVersionConflictException(""));
        Exception thrown = assertThrows(NonRetryableDeploymentTaskFailureException.class, () -> deploymentTask.call());
        assertThat(thrown.getCause(), isA(PackageVersionConflictException.class));
        verify(mockDependencyResolver).resolveDependencies(deploymentDocument, Collections.EMPTY_SET);
        verify(mockPackageCache, times(0)).preparePackages(anyList());
        verify(mockKernelConfigResolver, times(0)).resolve(anyList(), eq(deploymentDocument), anySet());
        verify(mockKernel, times(0)).mergeInNewConfig(eq("TestDeployment"), anyLong(), anyMap());
    }

    @Test
    public void GIVEN_deploymentDocument_WHEN_resolveDependencies_errored_THEN_deploymentTask_aborted()
            throws Exception {
        when(mockDependencyResolver.resolveDependencies(deploymentDocument, Collections.EMPTY_SET)).thenThrow(
                new PackagingException("mock error"));
        Exception thrown = assertThrows(RetryableDeploymentTaskFailureException.class, () -> deploymentTask.call());
        assertThat(thrown.getCause(), isA(PackagingException.class));
        verify(mockDependencyResolver).resolveDependencies(deploymentDocument, Collections.EMPTY_SET);
        verify(mockPackageCache, times(0)).preparePackages(anyList());
        verify(mockKernelConfigResolver, times(0)).resolve(anyList(), eq(deploymentDocument), anySet());
        verify(mockKernel, times(0)).mergeInNewConfig(eq("TestDeployment"), anyLong(), anyMap());
    }

    @Test
    public void GIVEN_deploymentDocument_WHEN_preparePackages_interrupted_THEN_deploymentTask_aborted()
            throws Exception {
        lenient().when(mockPackageCache.preparePackages(anyList())).thenReturn(new CompletableFuture<>());
        FutureTask<Void> futureTask = new FutureTask<>(deploymentTask);
        Thread t = new Thread(futureTask);
        t.start();

        t.interrupt();
        Exception thrown = assertThrows(ExecutionException.class, () -> futureTask.get());
        assertThat(thrown.getCause(), isA(RetryableDeploymentTaskFailureException.class));
        verify(mockDependencyResolver).resolveDependencies(deploymentDocument, Collections.EMPTY_SET);
        verify(mockPackageCache).preparePackages(anyList());
        verify(mockKernelConfigResolver, times(0)).resolve(anyList(), eq(deploymentDocument), anySet());
        verify(mockKernel, times(0)).mergeInNewConfig(eq("TestDeployment"), anyLong(), anyMap());
    }

    @Test
    public void GIVEN_deploymentDocument_WHEN_resolve_kernel_config_interrupted_THEN_deploymentTask_aborted()
            throws Exception {
        when(mockPackageCache.preparePackages(anyList())).thenReturn(CompletableFuture.completedFuture(null));
        when(mockKernelConfigResolver.resolve(anyList(), eq(deploymentDocument), anySet()))
                .thenThrow(new InterruptedException());

        Exception thrown = assertThrows(RetryableDeploymentTaskFailureException.class, () -> deploymentTask.call());
        assertThat(thrown.getCause(), isA(InterruptedException.class));
        verify(mockDependencyResolver).resolveDependencies(deploymentDocument, Collections.EMPTY_SET);
        verify(mockPackageCache).preparePackages(anyList());
        verify(mockKernelConfigResolver).resolve(anyList(), eq(deploymentDocument), anySet());
        verify(mockKernel, times(0)).mergeInNewConfig(eq("TestDeployment"), anyLong(), anyMap());
    }
}
