/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.deployment.exceptions.ServiceUpdateException;
import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.deployment.model.DeploymentResult;
import com.aws.iot.evergreen.deployment.model.DeploymentTask;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.KernelAlternatives;
import com.aws.iot.evergreen.logging.api.Logger;
import lombok.AllArgsConstructor;

import java.io.IOException;

import static com.aws.iot.evergreen.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;
import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentStage.KERNEL_ACTIVATION;
import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentStage.KERNEL_ROLLBACK;

@AllArgsConstructor
public class KernelUpdateDeploymentTask implements DeploymentTask {
    private Kernel kernel;
    private final Logger logger;
    private Deployment deployment;

    @Override
    public DeploymentResult call() {
        Deployment.DeploymentStage stage = deployment.getDeploymentStage();
        KernelAlternatives kernelAlts = kernel.getContext().get(KernelAlternatives.class);
        try {
            DeploymentConfigMerger.waitForServicesToStart(kernel.orderedDependencies(),
                    kernel.getConfig().lookup("system", "rootpath").getModtime());

            DeploymentResult result = null;
            if (KERNEL_ACTIVATION.equals(stage)) {
                result = new DeploymentResult(DeploymentResult.DeploymentStatus.SUCCESSFUL, null);
                kernelAlts.activationSucceeds();
            } else if (KERNEL_ROLLBACK.equals(stage)) {
                result = new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_COMPLETE,
                        new ServiceUpdateException(deployment.getStageDetails()));
                kernelAlts.rollbackCompletes();
            }
            return result;
        } catch (InterruptedException | IOException e) {
            logger.atError("deployment-interrupted", e).kv("deployment", deployment).log();
            saveDeploymentStatusDetails(e.getMessage());
            // Interrupted workflow. Shutdown kernel and retry this stage.
            kernel.shutdown(30, REQUEST_RESTART);
            return null;
        } catch (ServiceUpdateException e) {
            logger.atError("deployment-errored", e).kv("deployment", deployment).log();
            if (KERNEL_ACTIVATION.equals(stage)) {
                try {
                    saveDeploymentStatusDetails(e.getMessage());
                    // Rollback workflow. Flip symlinks and restart kernel
                    kernelAlts.prepareRollback();
                    kernel.shutdown(30, REQUEST_RESTART);
                } catch (IOException ioException) {
                    return new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK,
                            ioException);
                }
                return null;
            } else if (KERNEL_ROLLBACK.equals(stage)) {
                try {
                    kernelAlts.rollbackCompletes();
                } catch (IOException ioException) {
                    logger.atWarn().log("Failed to reset Kernel launch directory");
                }
                return new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK, e);
            }
            return null;
        }
    }

    private void saveDeploymentStatusDetails(String message) {
        deployment.setStageDetails(message);
        try {
            kernel.getContext().get(DeploymentDirectoryManager.class).writeDeploymentMetadata(deployment);
        } catch (IOException ioException) {
            logger.atWarn().setCause(ioException).log("Fail to save deployment details to file");
        }
    }
}
