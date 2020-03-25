package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.deployment.exceptions.NonRetryableDeploymentTaskFailureException;
import com.aws.iot.evergreen.deployment.exceptions.RetryableDeploymentTaskFailureException;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.DependencyResolver;
import com.aws.iot.evergreen.packagemanager.KernelConfigResolver;
import com.aws.iot.evergreen.packagemanager.PackageStore;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageVersionConflictException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

<<<<<<< HEAD
<<<<<<< HEAD
import java.util.Collections;
=======
=======
import java.util.HashMap;
>>>>>>> Removing spying from DeploymentTaskTest
import java.util.Map;
>>>>>>> Updating the status of deployments in the order of their completion. Refactoring DeploymentTask to parse the job document
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.isA;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class DeploymentTaskTest {

    private static Map<String, Object> jobDocument;

    private static DeploymentDocument deploymentDocument;

    @Mock
    private DependencyResolver mockDependencyResolver;
    @Mock
    private PackageStore mockPackageStore;
    @Mock
    private KernelConfigResolver mockKernelConfigResolver;
    @Mock
    private Kernel mockKernel;
<<<<<<< HEAD
<<<<<<< HEAD
    private final DeploymentDocument deploymentDocument =
            DeploymentDocument.builder().deploymentId("TestDeployment").timestamp(System.currentTimeMillis())
                    .rootPackages(Collections.EMPTY_LIST).build();
=======
    @Mock
    private Map<String, Object> jobDocument;

    private DeploymentDocument deploymentDocument =
<<<<<<< HEAD
            DeploymentDocument.builder().deploymentId("TestDeployment").timestamp(System.currentTimeMillis()).build();;
>>>>>>> Updating the status of deployments in the order of their completion. Refactoring DeploymentTask to parse the job document
=======
            DeploymentDocument.builder().deploymentId("TestDeployment").timestamp(System.currentTimeMillis()).build();
>>>>>>> Persisting job execution number
=======
>>>>>>> Removing spying from DeploymentTaskTest

    private final Logger logger = LogManager.getLogger("unit test");

    private DeploymentTask deploymentTask = new DeploymentTask(mockDependencyResolver, mockPackageCache,
            mockKernelConfigResolver, mockKernel,logger, jobDocument);

    @BeforeAll
    public static void initialize() {
        Long currentTimestamp = System.currentTimeMillis();
        jobDocument = new HashMap<>();
        jobDocument.put("DeploymentId", "TestDeployment");
        jobDocument.put("Timestamp", currentTimestamp);
        deploymentDocument =
                DeploymentDocument.builder().deploymentId("TestDeployment").timestamp(currentTimestamp).build();
    }

    @BeforeEach
<<<<<<< HEAD
    public void setup() throws Exception {
        deploymentTask =
<<<<<<< HEAD
                new DeploymentTask(mockDependencyResolver, mockPackageStore, mockKernelConfigResolver, mockKernel,
                        logger, deploymentDocument);

=======
                spy(new DeploymentTask(mockDependencyResolver, mockPackageCache, mockKernelConfigResolver, mockKernel,
                        logger, jobDocument));
        doReturn(deploymentDocument).when(deploymentTask).parseAndValidateJobDocument(eq(jobDocument));
>>>>>>> Updating the status of deployments in the order of their completion. Refactoring DeploymentTask to parse the job document
=======
    public void setup() {
        deploymentTask = new DeploymentTask(mockDependencyResolver, mockPackageCache, mockKernelConfigResolver, mockKernel,
                        logger, jobDocument);
>>>>>>> Removing spying from DeploymentTaskTest
    }

    @Test
    public void GIVEN_deploymentDocument_WHEN_start_deploymentTask_THEN_succeeds() throws Exception {
        when(mockPackageStore.preparePackages(anyList())).thenReturn(CompletableFuture.completedFuture(null));
        when(mockKernel.mergeInNewConfig(eq("TestDeployment"), anyLong(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(null));
        deploymentTask.call();
<<<<<<< HEAD
<<<<<<< HEAD
        verify(mockDependencyResolver).resolveDependencies(deploymentDocument, Collections.EMPTY_LIST);
        verify(mockPackageStore).preparePackages(anyList());
        verify(mockKernelConfigResolver).resolve(anyList(), eq(deploymentDocument), anyList());
=======
        ArgumentCaptor<DeploymentDocument> deploymentDocumentArgumentCaptor =
                ArgumentCaptor.forClass(DeploymentDocument.class);
=======
>>>>>>> Persisting job execution number
        verify(mockDependencyResolver).resolveDependencies(eq(deploymentDocument));
        verify(mockPackageCache).preparePackages(anyList());
        verify(mockKernelConfigResolver).resolve(anyList(), eq(deploymentDocument), anySet());
>>>>>>> Updating the status of deployments in the order of their completion. Refactoring DeploymentTask to parse the job document
        verify(mockKernel).mergeInNewConfig(eq("TestDeployment"), anyLong(), anyMap());
    }

    @Test
    public void GIVEN_deploymentDocument_WHEN_resolveDependencies_with_conflicted_dependency_THEN_deploymentTask_aborted()
            throws Exception {
        when(mockDependencyResolver.resolveDependencies(deploymentDocument, Collections.EMPTY_LIST))
                .thenThrow(new PackageVersionConflictException(""));
        Exception thrown = assertThrows(NonRetryableDeploymentTaskFailureException.class, () -> deploymentTask.call());
        assertThat(thrown.getCause(), isA(PackageVersionConflictException.class));
        verify(mockDependencyResolver).resolveDependencies(deploymentDocument, Collections.EMPTY_LIST);
        verify(mockPackageStore, times(0)).preparePackages(anyList());
        verify(mockKernelConfigResolver, times(0)).resolve(anyList(), eq(deploymentDocument), anyList());
        verify(mockKernel, times(0)).mergeInNewConfig(eq("TestDeployment"), anyLong(), anyMap());
    }

    @Test
    public void GIVEN_deploymentDocument_WHEN_resolveDependencies_errored_THEN_deploymentTask_aborted()
            throws Exception {
        when(mockDependencyResolver.resolveDependencies(deploymentDocument, Collections.EMPTY_LIST))
                .thenThrow(new PackagingException("mock error"));
        Exception thrown = assertThrows(RetryableDeploymentTaskFailureException.class, () -> deploymentTask.call());
        assertThat(thrown.getCause(), isA(PackagingException.class));
        verify(mockDependencyResolver).resolveDependencies(deploymentDocument, Collections.EMPTY_LIST);
        verify(mockPackageStore, times(0)).preparePackages(anyList());
        verify(mockKernelConfigResolver, times(0)).resolve(anyList(), eq(deploymentDocument), anyList());
        verify(mockKernel, times(0)).mergeInNewConfig(eq("TestDeployment"), anyLong(), anyMap());
    }

    @Test
    public void GIVEN_deploymentDocument_WHEN_preparePackages_interrupted_THEN_deploymentTask_aborted()
            throws Exception {
        lenient().when(mockPackageStore.preparePackages(anyList())).thenReturn(new CompletableFuture<>());
        FutureTask<Void> futureTask = new FutureTask<>(deploymentTask);
        Thread t = new Thread(futureTask);
        t.start();

        t.interrupt();
        Exception thrown = assertThrows(ExecutionException.class, () -> futureTask.get(5, TimeUnit.SECONDS));
        assertThat(thrown.getCause(), isA(RetryableDeploymentTaskFailureException.class));
        verify(mockDependencyResolver).resolveDependencies(deploymentDocument, Collections.EMPTY_LIST);
        verify(mockPackageStore).preparePackages(anyList());
        verify(mockKernelConfigResolver, times(0)).resolve(anyList(), eq(deploymentDocument), anyList());
        verify(mockKernel, times(0)).mergeInNewConfig(eq("TestDeployment"), anyLong(), anyMap());
    }

    @Test
    public void GIVEN_deploymentDocument_WHEN_resolve_kernel_config_interrupted_THEN_deploymentTask_aborted()
            throws Exception {
        when(mockPackageStore.preparePackages(anyList())).thenReturn(CompletableFuture.completedFuture(null));
        when(mockKernelConfigResolver.resolve(anyList(), eq(deploymentDocument), anyList()))
                .thenThrow(new InterruptedException());

        Exception thrown = assertThrows(RetryableDeploymentTaskFailureException.class, () -> deploymentTask.call());
        assertThat(thrown.getCause(), isA(InterruptedException.class));
        verify(mockDependencyResolver).resolveDependencies(deploymentDocument, Collections.EMPTY_LIST);
        verify(mockPackageStore).preparePackages(anyList());
        verify(mockKernelConfigResolver).resolve(anyList(), eq(deploymentDocument), anyList());
        verify(mockKernel, times(0)).mergeInNewConfig(eq("TestDeployment"), anyLong(), anyMap());
    }
}
