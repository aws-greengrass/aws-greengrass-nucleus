/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.deployment.exceptions.ServiceUpdateException;
import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.deployment.model.DeploymentResult;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.KernelAlternatives;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Arrays;

import static com.aws.iot.evergreen.dependency.State.BROKEN;
import static com.aws.iot.evergreen.dependency.State.FINISHED;
import static com.aws.iot.evergreen.dependency.State.RUNNING;
import static com.aws.iot.evergreen.dependency.State.STARTING;
import static com.aws.iot.evergreen.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;
import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentStage.KERNEL_ACTIVATION;
import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentStage.KERNEL_ROLLBACK;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.isA;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class KernelUpdateDeploymentTaskTest {
    private static Logger logger = LogManager.getLogger(KernelUpdateDeploymentTaskTest.class);

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
    EvergreenService evergreenService;

    KernelUpdateDeploymentTask task;

    @BeforeEach
    void beforeEach() {
        lenient().doReturn(kernelAlternatives).when(context).get(KernelAlternatives.class);
        lenient().doReturn(deploymentDirectoryManager).when(context).get(DeploymentDirectoryManager.class);
        lenient().doReturn(context).when(kernel).getContext();
        lenient().doReturn("A").when(evergreenService).getName();
        lenient().doReturn(Arrays.asList(evergreenService)).when(kernel).orderedDependencies();

        Topic topic = mock(Topic.class);
        lenient().doReturn(1L).when(topic).getModtime();
        Configuration configuration = mock(Configuration.class);
        lenient().doReturn(topic).when(configuration).lookup(any());
        lenient().doReturn(configuration).when(kernel).getConfig();

        task = new KernelUpdateDeploymentTask(kernel, logger, deployment);
    }

    @Test
    void GIVEN_deployment_activation_WHEN_service_broken_THEN_prepare_rollback(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, ServiceUpdateException.class);

        doReturn(KERNEL_ACTIVATION).when(deployment).getDeploymentStage();
        doReturn(BROKEN).when(evergreenService).getState();
        doReturn(2L).when(evergreenService).getStateModTime();

        task.call();
        verify(deployment).setStageDetails(matches("Service A in broken state after deployment"));
        verify(kernelAlternatives).prepareRollback();
        verify(kernel).shutdown(eq(30), eq(REQUEST_RESTART));
    }

    @Test
    void GIVEN_deployment_activation_WHEN_service_broken_and_rollback_ioe_THEN_unable_to_rollback(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, ServiceUpdateException.class);

        doReturn(KERNEL_ACTIVATION).when(deployment).getDeploymentStage();
        doReturn(BROKEN).when(evergreenService).getState();
        doReturn(2L).when(evergreenService).getStateModTime();
        doThrow(new IOException("mock io error")).when(kernelAlternatives).prepareRollback();

        DeploymentResult result = task.call();
        verify(deployment).setStageDetails(matches("Service A in broken state after deployment"));
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK, result.getDeploymentStatus());
        assertThat(result.getFailureCause().toString(), stringContainsInOrder("mock io error",
                "Service A in broken state after deployment"));
    }

    @Test
    void GIVEN_deployment_activation_WHEN_service_healthy_THEN_succeed() throws Exception{
        doReturn(KERNEL_ACTIVATION).when(deployment).getDeploymentStage();
        doReturn(STARTING, RUNNING).when(evergreenService).getState();
        doReturn(true).when(evergreenService).reachedDesiredState();

        assertEquals(new DeploymentResult(DeploymentResult.DeploymentStatus.SUCCESSFUL, null), task.call());
        verify(kernelAlternatives).activationSucceeds();
    }

    @Test
    void GIVEN_deployment_activation_WHEN_IOException_THEN_retry(ExtensionContext context) throws Exception{
        ignoreExceptionOfType(context, IOException.class);

        doReturn(KERNEL_ACTIVATION).when(deployment).getDeploymentStage();
        doReturn(STARTING, RUNNING).when(evergreenService).getState();
        doReturn(true).when(evergreenService).reachedDesiredState();
        doThrow(new IOException("any io error")).when(kernelAlternatives).activationSucceeds();

        task.call();
        verify(deployment).setStageDetails(matches("any io error"));
        verify(kernel).shutdown(eq(30), eq(REQUEST_RESTART));
    }

    @Test
    void GIVEN_deployment_rollback_WHEN_service_broken_THEN_rollback_fails(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, ServiceUpdateException.class);

        doReturn(KERNEL_ROLLBACK).when(deployment).getDeploymentStage();
        doReturn(BROKEN).when(evergreenService).getState();
        doReturn(0L, 2L).when(evergreenService).getStateModTime();
        DeploymentResult result = task.call();
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK, result.getDeploymentStatus());
        assertThat(result.getFailureCause(), isA(ServiceUpdateException.class));
        verify(kernelAlternatives).rollbackCompletes();
    }

    @Test
    void GIVEN_deployment_rollback_WHEN_service_healthy_THEN_rollback_succeeds() throws Exception {
        doReturn(KERNEL_ROLLBACK).when(deployment).getDeploymentStage();
        doReturn(FINISHED).when(evergreenService).getState();
        doReturn(true).when(evergreenService).reachedDesiredState();
        doReturn("mock message").when(deployment).getStageDetails();

        DeploymentResult result = task.call();
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_COMPLETE, result.getDeploymentStatus());
        assertThat(result.getFailureCause(), isA(ServiceUpdateException.class));
        assertEquals("mock message", result.getFailureCause().getMessage());
        verify(kernelAlternatives).rollbackCompletes();
    }
}
