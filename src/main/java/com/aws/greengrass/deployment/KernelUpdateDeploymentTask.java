/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.deployment.exceptions.ServiceUpdateException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.deployment.model.DeploymentTask;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.KernelAlternatives;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.util.Utils;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static com.aws.greengrass.deployment.DeploymentConfigMerger.DEPLOYMENT_ID_LOG_KEY;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.KERNEL_ACTIVATION;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.KERNEL_ROLLBACK;

public class KernelUpdateDeploymentTask implements DeploymentTask {
    private final Kernel kernel;
    private final Logger logger;
    private final Deployment deployment;
    private final ComponentManager componentManager;

    /**
     * Constructor for DefaultDeploymentTask.
     *
     * @param kernel Kernel instance
     * @param logger Logger instance
     * @param deployment Deployment instance
     * @param componentManager ComponentManager instance
     */
    public KernelUpdateDeploymentTask(Kernel kernel, Logger logger, Deployment deployment,
                                      ComponentManager componentManager) {
        this.kernel = kernel;
        this.deployment = deployment;
        this.logger = logger.dfltKv(DEPLOYMENT_ID_LOG_KEY, deployment.getDeploymentDocumentObj().getDeploymentId());
        this.componentManager = componentManager;
    }

    @SuppressWarnings({"PMD.AvoidDuplicateLiterals"})
    @Override
    public DeploymentResult call() {
        Deployment.DeploymentStage stage = deployment.getDeploymentStage();
        try {
            List<GreengrassService> servicesToTrack =
                    kernel.orderedDependencies().stream().filter(GreengrassService::shouldAutoStart)
                            .filter(o -> !kernel.getMain().equals(o)).collect(Collectors.toList());
            long mergeTimestamp = kernel.getConfig().lookup("system", "rootpath").getModtime();
            logger.atDebug().kv("serviceToTrack", servicesToTrack).kv("mergeTime", mergeTimestamp)
                    .log("Nucleus update workflow waiting for services to complete update");
            DeploymentConfigMerger.waitForServicesToStart(servicesToTrack, mergeTimestamp);

            DeploymentResult result = null;
            if (KERNEL_ACTIVATION.equals(stage)) {
                result = new DeploymentResult(DeploymentResult.DeploymentStatus.SUCCESSFUL, null);
            } else if (KERNEL_ROLLBACK.equals(stage)) {
                result = new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_COMPLETE,
                        getDeploymentStatusDetails());
            }

            componentManager.cleanupStaleVersions();
            return result;
        } catch (InterruptedException e) {
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
                    deployment.setDeploymentStage(KERNEL_ROLLBACK);
                    saveDeploymentStatusDetails(e.getMessage());
                    // Rollback workflow. Flip symlinks and restart kernel
                    kernel.getContext().get(KernelAlternatives.class).prepareRollback();
                    kernel.shutdown(30, REQUEST_RESTART);
                } catch (IOException ioException) {
                    logger.atError().log("Failed to set up Nucleus rollback directory", ioException);
                    return new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK, e);
                }
                return null;
            } else if (KERNEL_ROLLBACK.equals(stage)) {
                logger.atError().log("Nucleus update workflow failed on rollback", e);
                return new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK,
                        getDeploymentStatusDetails());
            }
            return null;
        }
    }

    private void saveDeploymentStatusDetails(String message) throws IOException {
        deployment.setStageDetails(message);
        kernel.getContext().get(DeploymentDirectoryManager.class).writeDeploymentMetadata(deployment);
    }

    private ServiceUpdateException getDeploymentStatusDetails() {
        return new ServiceUpdateException(Utils.isEmpty(deployment.getStageDetails())
                ? "Nucleus update workflow failed to restart Nucleus. See loader logs for more details"
                : deployment.getStageDetails());
    }
}
