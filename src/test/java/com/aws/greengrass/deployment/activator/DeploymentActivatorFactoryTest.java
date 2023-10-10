/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.activator;

import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.bootstrap.BootstrapManager;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class DeploymentActivatorFactoryTest {
    @Mock
    Kernel kernel;
    @Mock
    Context context;
    @Mock
    BootstrapManager bootstrapManager;

    @Test
    void testGetDefaultActivator() throws Exception {
        when(bootstrapManager.isBootstrapRequired(any())).thenReturn(false);
        when(context.get(eq(BootstrapManager.class))).thenReturn(bootstrapManager);
        when(kernel.getContext()).thenReturn(context);
        DeploymentActivatorFactory deploymentActivatorFactory = new DeploymentActivatorFactory(kernel);
        deploymentActivatorFactory.getDeploymentActivator(Collections.emptyMap());
        verify(context).get(eq(DefaultActivator.class));
    }

    @Test
    void testGetKernelUpdateActivator() throws Exception {
        when(bootstrapManager.isBootstrapRequired(any())).thenReturn(true);
        when(context.get(eq(BootstrapManager.class))).thenReturn(bootstrapManager);
        when(kernel.getContext()).thenReturn(context);
        DeploymentActivatorFactory deploymentActivatorFactory = new DeploymentActivatorFactory(kernel);
        deploymentActivatorFactory.getDeploymentActivator(Collections.emptyMap());
        verify(context).get(eq(KernelUpdateActivator.class));
    }
}
