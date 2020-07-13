/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.deployment.exceptions.ServiceUpdateException;
import com.aws.iot.evergreen.deployment.model.BaseDeploymentTask;
import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.deployment.model.DeploymentResult;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import lombok.AllArgsConstructor;

import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentStage.KERNEL_ACTIVATION;
import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentStage.KERNEL_ROLLBACK;

@AllArgsConstructor
public class KernelUpdateDeploymentTask implements BaseDeploymentTask {
    private Kernel kernel;
    private final Logger logger;
    private Deployment deployment;

    @Override
    public DeploymentResult call() {
        Deployment.DeploymentStage subtype = deployment.getDeploymentStage();
        try {
            DeploymentConfigMerger.waitForServicesToStart(kernel.orderedDependencies(),
                    kernel.getConfig().lookup().getModtime());

            DeploymentResult result = null;
            if (KERNEL_ACTIVATION.equals(subtype)) {
                result = new DeploymentResult(DeploymentResult.DeploymentStatus.SUCCESSFUL, null);
            } else if (KERNEL_ROLLBACK.equals(subtype)) {
                // TODO: add failure causes
                result = new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_COMPLETE, null);
            }
            return result;
        } catch (InterruptedException e) {
            logger.atError("deployment-interrupted", e).kv("deployment", deployment).log();
            // TODO: interrupted workflow. Maybe shutdown kernel and retry this step.
            return null;
        } catch (ServiceUpdateException e) {
            logger.atError("deployment-errored", e).kv("deployment", deployment).log();
            if (KERNEL_ACTIVATION.equals(subtype)) {
                // TODO: rollback workflow. Flip symlinks and restart
                return null;
            } else if (KERNEL_ROLLBACK.equals(subtype)) {
                // TODO: add failure causes
                return new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK, null);
            }
            return null;
        }
    }
}
