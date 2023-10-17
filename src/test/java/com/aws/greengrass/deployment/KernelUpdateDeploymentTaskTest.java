/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.exceptions.DeploymentException;
import com.aws.greengrass.deployment.exceptions.ServiceUpdateException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.KernelAlternatives;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Arrays;

import static com.aws.greengrass.dependency.State.BROKEN;
import static com.aws.greengrass.dependency.State.FINISHED;
import static com.aws.greengrass.dependency.State.RUNNING;
import static com.aws.greengrass.dependency.State.STARTING;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.KERNEL_ACTIVATION;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.KERNEL_ROLLBACK;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.isA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class KernelUpdateDeploymentTaskTest {
    private static final Logger logger = LogManager.getLogger(KernelUpdateDeploymentTaskTest.class);

    @Mock
    Kernel kernel;
    @Mock
    Context context;
    @Mock
    KernelAlternatives kernelAlternatives;
    @Mock
    DeploymentDirectoryManager deploymentDirectoryManager;
    @Mock
    Deployment deployment;
    @Mock
    GreengrassService greengrassService;
    @Mock
    GreengrassService mainService;
    @Mock
    ComponentManager componentManager;

    KernelUpdateDeploymentTask task;

    @BeforeEach
    void beforeEach() throws Exception {
        lenient().doReturn(kernelAlternatives).when(context).get(KernelAlternatives.class);
        lenient().doReturn(deploymentDirectoryManager).when(context).get(DeploymentDirectoryManager.class);
        lenient().doReturn(context).when(kernel).getContext();
        lenient().doReturn("A").when(greengrassService).getName();
        lenient().doReturn(mainService).when(kernel).getMain();
        lenient().doReturn(true).when(greengrassService).shouldAutoStart();
        lenient().doReturn(Arrays.asList(greengrassService)).when(kernel).orderedDependencies();
        lenient().doNothing().when(componentManager).cleanupStaleVersions();

        Topic topic = mock(Topic.class);
        lenient().doReturn(1L).when(topic).getModtime();
        Configuration configuration = mock(Configuration.class);
        lenient().doReturn(topic).when(configuration).lookup(any());
        lenient().doReturn(configuration).when(kernel).getConfig();
        task = new KernelUpdateDeploymentTask(kernel, logger, deployment, componentManager);
    }

    @Test
    void GIVEN_deployment_activation_WHEN_service_broken_THEN_prepare_rollback(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, ServiceUpdateException.class);

        doReturn(KERNEL_ACTIVATION).when(deployment).getDeploymentStage();
        doReturn(BROKEN).when(greengrassService).getState();
        doReturn(2L).when(greengrassService).getStateModTime();

        task.call();
        verify(deployment).setStageDetails(matches("Service A in broken state after deployment"));
        verify(kernelAlternatives).prepareRollback();
        verify(kernel).shutdown(eq(30), eq(REQUEST_RESTART));
    }

    @Test
    void GIVEN_deployment_activation_WHEN_service_broken_and_rollback_ioe_THEN_unable_to_rollback(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, ServiceUpdateException.class);
        ignoreExceptionOfType(context, IOException.class);

        doReturn(KERNEL_ACTIVATION).when(deployment).getDeploymentStage();
        doReturn(BROKEN).when(greengrassService).getState();
        doReturn(2L).when(greengrassService).getStateModTime();
        doThrow(new IOException("mock io error")).when(kernelAlternatives).prepareRollback();

        DeploymentResult result = task.call();
        verify(deployment).setStageDetails(matches("Service A in broken state after deployment"));
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK, result.getDeploymentStatus());
        assertThat(result.getFailureCause(), isA(ServiceUpdateException.class));
    }

    @Test
    void GIVEN_deployment_activation_WHEN_service_healthy_THEN_succeed() throws Exception{
        doReturn(KERNEL_ACTIVATION).when(deployment).getDeploymentStage();
        doReturn(STARTING, RUNNING).when(greengrassService).getState();
        doReturn(true).when(greengrassService).reachedDesiredState();

        assertEquals(new DeploymentResult(DeploymentResult.DeploymentStatus.SUCCESSFUL, null), task.call());
    }

    @Test
    void GIVEN_deployment_rollback_WHEN_service_broken_THEN_rollback_fails(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, ServiceUpdateException.class);

        doReturn(KERNEL_ROLLBACK).when(deployment).getDeploymentStage();
        doReturn("mock activate error").when(deployment).getStageDetails();
        doReturn(BROKEN).when(greengrassService).getState();
        doReturn(0L, 2L).when(greengrassService).getStateModTime();
        DeploymentResult result = task.call();
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK, result.getDeploymentStatus());
        assertThat(result.getFailureCause(), isA(DeploymentException.class));
        assertEquals("mock activate error", result.getFailureCause().getMessage());
    }

    @Test
    void GIVEN_deployment_rollback_WHEN_service_healthy_THEN_rollback_succeeds() throws Exception {
        doReturn(KERNEL_ROLLBACK).when(deployment).getDeploymentStage();
        doReturn(FINISHED).when(greengrassService).getState();
        doReturn(true).when(greengrassService).reachedDesiredState();
        doReturn("mock message").when(deployment).getStageDetails();

        DeploymentResult result = task.call();
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_COMPLETE, result.getDeploymentStatus());
        assertThat(result.getFailureCause(), isA(DeploymentException.class));
        assertEquals("mock message", result.getFailureCause().getMessage());
    }
}
