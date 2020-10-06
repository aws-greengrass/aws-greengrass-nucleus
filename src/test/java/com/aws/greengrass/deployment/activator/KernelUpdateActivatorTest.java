/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.activator;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.DeploymentDirectoryManager;
import com.aws.greengrass.deployment.bootstrap.BootstrapManager;
import com.aws.greengrass.deployment.exceptions.ServiceUpdateException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.KernelAlternatives;
import com.aws.greengrass.lifecyclemanager.KernelLifecycle;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.NO_OP;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_REBOOT;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.KERNEL_ROLLBACK;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class KernelUpdateActivatorTest {
    @Mock
    Kernel kernel;
    @Mock
    Context context;
    @Mock
    Configuration config;
    @Mock
    DeploymentDirectoryManager deploymentDirectoryManager;
    @Mock
    BootstrapManager bootstrapManager;
    @Mock
    KernelAlternatives kernelAlternatives;
    @Mock
    CompletableFuture<DeploymentResult> totallyCompleteFuture;
    @Mock
    Deployment deployment;
    @Mock
    Map<String, Object> newConfig;

    KernelUpdateActivator kernelUpdateActivator;

    @BeforeEach
    void beforeEach() {
        doReturn(deploymentDirectoryManager).when(context).get(eq(DeploymentDirectoryManager.class));
        lenient().doReturn(true).when(kernelAlternatives).isLaunchDirSetup();
        doReturn(kernelAlternatives).when(context).get(eq(KernelAlternatives.class));
        doReturn(context).when(kernel).getContext();
        lenient().doReturn(config).when(kernel).getConfig();
        kernelUpdateActivator = new KernelUpdateActivator(kernel, bootstrapManager);
        lenient().doReturn(DeploymentDocument.builder().timestamp(0L).deploymentId("testId").build())
                .when(deployment).getDeploymentDocumentObj();
        lenient().doReturn(mock(KernelLifecycle.class)).when(context).get(eq(KernelLifecycle.class));
    }

    @Test
    void GIVEN_deployment_activate_WHEN_takeConfigSnapshot_fails_THEN_deployment_fails(ExtensionContext context) throws Exception{
        ignoreExceptionOfType(context, IOException.class);

        IOException mockIOE = new IOException();
        doThrow(mockIOE).when(deploymentDirectoryManager).takeConfigSnapshot(any());
        kernelUpdateActivator.activate(newConfig, deployment, totallyCompleteFuture);
        verify(totallyCompleteFuture).complete(eq(new DeploymentResult(
                DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, mockIOE)));
    }

    @Test
    void GIVEN_deployment_activate_WHEN_prepareBootstrap_fails_THEN_deployment_rollback(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, IOException.class);

        Path bootstrapFilePath = mock(Path.class);
        doReturn(bootstrapFilePath).when(deploymentDirectoryManager).getBootstrapTaskFilePath();
        Path targetConfigFilePath = mock(Path.class);
        doReturn(targetConfigFilePath).when(deploymentDirectoryManager).getTargetConfigFilePath();
        IOException mockIOE = new IOException("mock error");
        doThrow(mockIOE).when(kernelAlternatives).prepareBootstrap(eq("testId"));
        doThrow(new IOException()).when(deploymentDirectoryManager).writeDeploymentMetadata(eq(deployment));

        kernelUpdateActivator.activate(newConfig, deployment, totallyCompleteFuture);
        verify(deploymentDirectoryManager).takeConfigSnapshot(eq(targetConfigFilePath));
        verify(bootstrapManager).persistBootstrapTaskList(eq(bootstrapFilePath));
        verify(deployment).setDeploymentStage(eq(KERNEL_ROLLBACK));
        verify(deployment).setStageDetails(eq("mock error"));
        verify(kernelAlternatives).prepareRollback();
        verify(kernel).shutdown(eq(30), eq(REQUEST_RESTART));
    }

    @Test
    void GIVEN_deployment_activate_WHEN_bootstrap_task_fails_THEN_deployment_rollback(ExtensionContext context) throws Exception  {
        ignoreExceptionOfType(context, IOException.class);
        ignoreExceptionOfType(context, ServiceUpdateException.class);

        Path bootstrapFilePath = mock(Path.class);
        doReturn(bootstrapFilePath).when(deploymentDirectoryManager).getBootstrapTaskFilePath();
        Path targetConfigFilePath = mock(Path.class);
        doReturn(targetConfigFilePath).when(deploymentDirectoryManager).getTargetConfigFilePath();
        ServiceUpdateException mockSUE = new ServiceUpdateException("mock error");
        doThrow(mockSUE).when(bootstrapManager).executeAllBootstrapTasksSequentially(eq(bootstrapFilePath));
        doThrow(new IOException()).when(kernelAlternatives).prepareRollback();

        kernelUpdateActivator.activate(newConfig, deployment, totallyCompleteFuture);
        verify(deploymentDirectoryManager).takeConfigSnapshot(eq(targetConfigFilePath));
        verify(bootstrapManager).persistBootstrapTaskList(eq(bootstrapFilePath));
        verify(kernelAlternatives).prepareBootstrap(eq("testId"));
        verify(deployment).setDeploymentStage(eq(KERNEL_ROLLBACK));
        verify(deployment).setStageDetails(eq("mock error"));
        verify(deploymentDirectoryManager).writeDeploymentMetadata(eq(deployment));
        verify(kernel).shutdown(eq(30), eq(REQUEST_RESTART));
    }

    @Test
    void GIVEN_deployment_activate_WHEN_bootstrap_finishes_THEN_request_restart() throws Exception  {
        Path bootstrapFilePath = mock(Path.class);
        doReturn(bootstrapFilePath).when(deploymentDirectoryManager).getBootstrapTaskFilePath();
        Path targetConfigFilePath = mock(Path.class);
        doReturn(targetConfigFilePath).when(deploymentDirectoryManager).getTargetConfigFilePath();
        doReturn(NO_OP).when(bootstrapManager).executeAllBootstrapTasksSequentially(eq(bootstrapFilePath));
        doReturn(false).when(bootstrapManager).hasNext();

        kernelUpdateActivator.activate(newConfig, deployment, totallyCompleteFuture);
        verify(deploymentDirectoryManager).takeConfigSnapshot(eq(targetConfigFilePath));
        verify(bootstrapManager).persistBootstrapTaskList(eq(bootstrapFilePath));
        verify(kernelAlternatives).prepareBootstrap(eq("testId"));
        verify(kernel).shutdown(eq(30), eq(REQUEST_RESTART));
    }

    @Test
    void GIVEN_deployment_activate_WHEN_bootstrap_requires_reboot_THEN_request_reboot() throws Exception  {
        Path bootstrapFilePath = mock(Path.class);
        doReturn(bootstrapFilePath).when(deploymentDirectoryManager).getBootstrapTaskFilePath();
        Path targetConfigFilePath = mock(Path.class);
        doReturn(targetConfigFilePath).when(deploymentDirectoryManager).getTargetConfigFilePath();
        doReturn(REQUEST_REBOOT).when(bootstrapManager).executeAllBootstrapTasksSequentially(eq(bootstrapFilePath));
        doReturn(true).when(bootstrapManager).hasNext();

        kernelUpdateActivator.activate(newConfig, deployment, totallyCompleteFuture);
        verify(deploymentDirectoryManager).takeConfigSnapshot(eq(targetConfigFilePath));
        verify(bootstrapManager).persistBootstrapTaskList(eq(bootstrapFilePath));
        verify(kernelAlternatives).prepareBootstrap(eq("testId"));
        verify(kernel).shutdown(eq(30), eq(REQUEST_REBOOT));
    }
}
