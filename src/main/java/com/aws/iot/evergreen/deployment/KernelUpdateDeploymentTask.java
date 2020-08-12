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

import java.io.IOException;

import static com.aws.iot.evergreen.deployment.DeploymentConfigMerger.DEPLOYMENT_ID_LOG_KEY;
import static com.aws.iot.evergreen.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;
import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentStage.KERNEL_ACTIVATION;
import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentStage.KERNEL_ROLLBACK;

public class KernelUpdateDeploymentTask implements DeploymentTask {
    private final Kernel kernel;
    private final Logger logger;
    private final Deployment deployment;

    /**
     * Constructor for DefaultDeploymentTask.
     *
     * @param kernel Kernel instance
     * @param logger Logger instance
     * @param deployment Deployment instance
     */
    public KernelUpdateDeploymentTask(Kernel kernel, Logger logger, Deployment deployment) {
        this.kernel = kernel;
        this.deployment = deployment;
        this.logger = logger.dfltKv(DEPLOYMENT_ID_LOG_KEY, deployment.getDeploymentDocumentObj().getDeploymentId());
    }

    @SuppressWarnings({"PMD.AvoidDuplicateLiterals"})
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
            logger.atError("deployment-interrupted", e).log();
            try {
                saveDeploymentStatusDetails(e.getMessage());
            } catch (IOException ioException) {
                logger.atError().log("Failed to persist deployment error information", ioException);
            }
            // Interrupted workflow. Shutdown kernel and retry this stage.
            kernel.shutdown(30, REQUEST_RESTART);
            return null;
        } catch (ServiceUpdateException e) {
            logger.atError("deployment-errored", e).log();
            if (KERNEL_ACTIVATION.equals(stage)) {
                try {
                    saveDeploymentStatusDetails(e.getMessage());
                    // Rollback workflow. Flip symlinks and restart kernel
                    kernelAlts.prepareRollback();
                    kernel.shutdown(30, REQUEST_RESTART);
                } catch (IOException ioException) {
                    logger.atError().log("Failed to set up Kernel rollback directory", ioException);
                    return new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK, e);
                }
                return null;
            } else if (KERNEL_ROLLBACK.equals(stage)) {
                try {
                    kernelAlts.rollbackCompletes();
                } catch (IOException ioException) {
                    logger.atError().log("Failed to reset Kernel launch directory", ioException);
                }
                return new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK, e);
            }
            return null;
        }
    }

    private void saveDeploymentStatusDetails(String message) throws IOException {
        deployment.setStageDetails(message);
        kernel.getContext().get(DeploymentDirectoryManager.class).writeDeploymentMetadata(deployment);
    }
}
