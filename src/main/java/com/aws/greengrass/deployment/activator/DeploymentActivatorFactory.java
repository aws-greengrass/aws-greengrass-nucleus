/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.activator;


import com.aws.greengrass.deployment.bootstrap.BootstrapManager;
import com.aws.greengrass.deployment.exceptions.ComponentConfigurationValidationException;
import com.aws.greengrass.deployment.exceptions.ServiceUpdateException;
import com.aws.greengrass.lifecyclemanager.Kernel;
import lombok.AllArgsConstructor;

import java.util.Map;
import javax.inject.Inject;

@AllArgsConstructor
public class DeploymentActivatorFactory {
    @Inject
    private final Kernel kernel;

    /**
     * Get deployment activator based on the new configuration.
     *
     * @param newConfig new configuration from deployment
     * @return DeploymentActivator instance
     * @throws ServiceUpdateException                    if processing new configuration for activation fails
     * @throws ComponentConfigurationValidationException If changed nucleus component configuration is invalid
     */
    public DeploymentActivator getDeploymentActivator(Map<String, Object> newConfig) throws ServiceUpdateException,
            ComponentConfigurationValidationException {
        BootstrapManager bootstrapManager = kernel.getContext().get(BootstrapManager.class);
        if (bootstrapManager.isBootstrapRequired(newConfig)) {
            return kernel.getContext().get(KernelUpdateActivator.class);
        }
        return kernel.getContext().get(DefaultActivator.class);
    }
}
