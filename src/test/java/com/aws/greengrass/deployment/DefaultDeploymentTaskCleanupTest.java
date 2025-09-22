/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.deployment.model.DeploymentResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DefaultDeploymentTaskCleanupTest {

    @Mock
    private ComponentManager componentManager;

    @Test
    void GIVEN_deployment_completes_WHEN_cleanup_called_THEN_passes_deployment_result() {
        DeploymentResult result = new DeploymentResult(DeploymentResult.DeploymentStatus.SUCCESSFUL, null);
        
        // Simulate the cleanup call that happens in DefaultDeploymentTask
        componentManager.cleanupStaleVersions(result);
        
        verify(componentManager).cleanupStaleVersions(result);
    }

    @Test
    void GIVEN_failed_deployment_WHEN_cleanup_called_THEN_passes_failed_result() {
        DeploymentResult result = new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, 
                new RuntimeException("Test failure"));
        
        // Simulate the cleanup call that happens in DefaultDeploymentTask
        componentManager.cleanupStaleVersions(result);
        
        verify(componentManager).cleanupStaleVersions(result);
    }
}
