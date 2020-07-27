/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment.activator;


import com.aws.iot.evergreen.deployment.bootstrap.BootstrapManager;
import com.aws.iot.evergreen.deployment.exceptions.ServiceUpdateException;
import com.aws.iot.evergreen.kernel.Kernel;
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
     * @throws ServiceUpdateException if processing new configuration for activation fails
     */
    public DeploymentActivator getDeploymentActivator(Map<Object, Object> newConfig) throws ServiceUpdateException {
        BootstrapManager bootstrapManager = kernel.getContext().get(BootstrapManager.class);
        if (bootstrapManager.isBootstrapRequired(newConfig)) {
            return kernel.getContext().get(KernelUpdateActivator.class);
        }
        return kernel.getContext().get(DefaultActivator.class);
    }
}