/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.activator;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.dependency.Crashable;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.DeploymentDirectoryManager;
import com.aws.greengrass.deployment.exceptions.ServiceUpdateException;

import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.deployment.model.FailureHandlingPolicy;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    DefaultActivator defaultActivator;

    @BeforeEach
    void beforeEach() {
        doReturn(deploymentDirectoryManager).when(context).get(DeploymentDirectoryManager.class);
        doReturn(context).when(kernel).getContext();
        lenient().doReturn(config).when(kernel).getConfig();
        lenient().doReturn(Collections.emptyList()).when(kernel).orderedDependencies();
        defaultActivator = spy(new DefaultActivator(kernel));
    }

    @Test
    void GIVEN_endpoint_switch_with_DO_NOTHING_WHEN_activate_THEN_snapshot_taken() throws Exception {
        when(deploymentDirectoryManager.hasEndpointSwitchMetadata()).thenReturn(true);
        Path snapshotPath = mock(Path.class);
        when(deploymentDirectoryManager.getSnapshotFilePath()).thenReturn(snapshotPath);

        CompletableFuture<DeploymentResult> future = new CompletableFuture<>();

        defaultActivator.activate(createNewConfig(), createDeployment(FailureHandlingPolicy.DO_NOTHING),
                System.currentTimeMillis(), future);

        verify(deploymentDirectoryManager).takeConfigSnapshot(snapshotPath);
    }

    @Test
    void GIVEN_endpoint_switch_with_DO_NOTHING_WHEN_failure_THEN_rollback_performed(
            ExtensionContext extContext) throws Exception {
        ignoreExceptionOfType(extContext, ServiceUpdateException.class);
        when(deploymentDirectoryManager.hasEndpointSwitchMetadata()).thenReturn(true);
        Path snapshotPath = mock(Path.class);
        when(deploymentDirectoryManager.getSnapshotFilePath()).thenReturn(snapshotPath);

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
        when(deploymentDirectoryManager.hasEndpointSwitchMetadata()).thenReturn(false);

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
        verify(deploymentDirectoryManager, never()).takeConfigSnapshot(any());
    }

    private Deployment createDeployment(FailureHandlingPolicy policy) {
        DeploymentDocument doc = DeploymentDocument.builder()
                .deploymentId("testId")
                .failureHandlingPolicy(policy)
                .timestamp(0L)
                .build();
        Deployment deployment = mock(Deployment.class);
        when(deployment.getDeploymentDocumentObj()).thenReturn(doc);
        return deployment;
    }

    private Map<String, Object> createNewConfig() {
        Map<String, Object> newConfig = new HashMap<>();
        newConfig.put(SERVICES_NAMESPACE_TOPIC, new HashMap<>());
        return newConfig;
    }

}
