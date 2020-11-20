/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.activator;

import com.aws.greengrass.deployment.bootstrap.BootstrapManager;
import com.aws.greengrass.deployment.exceptions.ServiceUpdateException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.KernelAlternatives;
import com.aws.greengrass.lifecyclemanager.KernelLifecycle;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;

import static com.aws.greengrass.deployment.DeploymentConfigMerger.DEPLOYMENT_ID_LOG_KEY;
import static com.aws.greengrass.deployment.DeploymentConfigMerger.MERGE_CONFIG_EVENT_KEY;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_REBOOT;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.KERNEL_ROLLBACK;

/**
 * Activation and rollback of Kernel update deployments.
 */
public class KernelUpdateActivator extends DeploymentActivator {
    private final BootstrapManager bootstrapManager;
    private final KernelAlternatives kernelAlternatives;

    /**
     * Constructor of KernelUpdateActivator.
     *
     * @param kernel Kernel instance
     * @param bootstrapManager BootstrapManager instance
     */
    @Inject
    public KernelUpdateActivator(Kernel kernel, BootstrapManager bootstrapManager) {
        super(kernel);
        this.bootstrapManager = bootstrapManager;
        this.kernelAlternatives = kernel.getContext().get(KernelAlternatives.class);
    }

    @Override
    public void activate(Map<String, Object> newConfig, Deployment deployment,
                         CompletableFuture<DeploymentResult> totallyCompleteFuture) {
        if (!takeConfigSnapshot(totallyCompleteFuture)) {
            return;
        }

        if (!kernelAlternatives.isLaunchDirSetup()) {
            totallyCompleteFuture.complete(new DeploymentResult(
                    DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, new UnsupportedOperationException(
                            "Unable to process deployment. Greengrass launch directory is not set up or Greengrass "
                                    + "is not set up as a system service")));
            return;
        }

        DeploymentDocument deploymentDocument = deployment.getDeploymentDocumentObj();
        KernelLifecycle lifecycle = kernel.getContext().get(KernelLifecycle.class);
        // Preserve tlog state before launch directory is updated to reflect ongoing deployment.
        lifecycle.getTlog().close();
        // Wait for all services to close.
        lifecycle.stopAllServices(30);

        kernel.getConfig().mergeMap(deploymentDocument.getTimestamp(), newConfig);
        Path bootstrapTaskFilePath;
        try {
            bootstrapTaskFilePath = deploymentDirectoryManager.getBootstrapTaskFilePath();
            deploymentDirectoryManager.takeConfigSnapshot(deploymentDirectoryManager.getTargetConfigFilePath());
            bootstrapManager.persistBootstrapTaskList(bootstrapTaskFilePath);
            kernelAlternatives.prepareBootstrap(deploymentDocument.getDeploymentId());
        } catch (IOException e) {
            rollback(deployment, e);
            return;
        }

        try {
            int exitCode = bootstrapManager.executeAllBootstrapTasksSequentially(bootstrapTaskFilePath);
            if (!bootstrapManager.hasNext()) {
                logger.atInfo().log("Completed all bootstrap tasks. Continue to activate deployment changes");
            }
            // If exitCode is 0, which happens when all bootstrap tasks are completed, restart in new launch
            // directories and verify handover is complete. As a result, exit code 0 is treated as 100 here.
            logger.atInfo().log((exitCode == REQUEST_REBOOT ? "device reboot" : "kernel restart")
                    + " requested to complete bootstrap task");

            kernel.shutdown(30, exitCode == REQUEST_REBOOT ? REQUEST_REBOOT : REQUEST_RESTART);
        } catch (ServiceUpdateException | IOException e) {
            rollback(deployment, e);
        }
    }

    void rollback(Deployment deployment, Throwable failureCause) {
        logger.atInfo(MERGE_CONFIG_EVENT_KEY, failureCause)
                .kv(DEPLOYMENT_ID_LOG_KEY, deployment.getDeploymentDocumentObj().getDeploymentId())
                .log("Rolling back failed deployment");
        deployment.setStageDetails(failureCause.getMessage());
        deployment.setDeploymentStage(KERNEL_ROLLBACK);

        try {
            deploymentDirectoryManager.writeDeploymentMetadata(deployment);
        } catch (IOException ioException) {
            logger.atError().setCause(ioException).log("Failed to persist deployment details");
        }
        try {
            kernelAlternatives.prepareRollback();
        } catch (IOException e) {
            logger.atError().setCause(e).log("Failed to set up rollback directory");
        }
        // Restart Kernel regardless and rely on loader orchestration
        kernel.shutdown(30, REQUEST_RESTART);
    }
}
