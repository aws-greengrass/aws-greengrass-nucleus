/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment.activator;

import com.aws.iot.evergreen.deployment.BootstrapManager;
import com.aws.iot.evergreen.deployment.ConfigSnapshotUtils;
import com.aws.iot.evergreen.deployment.exceptions.ServiceUpdateException;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentResult;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.KernelLifecycle;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import javax.inject.Inject;

import static com.aws.iot.evergreen.deployment.DeploymentConfigMerger.DEPLOYMENT_ID_LOG_KEY;
import static com.aws.iot.evergreen.deployment.DeploymentConfigMerger.MERGE_CONFIG_EVENT_KEY;
import static com.aws.iot.evergreen.deployment.DeploymentConfigMerger.MERGE_ERROR_LOG_EVENT_KEY;
import static com.aws.iot.evergreen.deployment.DeploymentConfigMerger.waitForServicesToStart;

/**
 * Activation and rollback of Kernel update deployments.
 */
public class KernelUpdateActivator extends DeploymentActivator {
    private final BootstrapManager bootstrapManager;

    @Inject
    protected KernelUpdateActivator(Kernel kernel, BootstrapManager bootstrapManager) {
        super(kernel);
        this.bootstrapManager = bootstrapManager;
    }

    @Override
    public void activate(Map<Object, Object> newConfig, DeploymentDocument deploymentDocument,
                         CompletableFuture<DeploymentResult> totallyCompleteFuture) {
        String deploymentId = deploymentDocument.getDeploymentId();
        if (!takeConfigSnapshot(deploymentId, totallyCompleteFuture)) {
            return;
        }

        // Wait for all services to close
        kernel.getContext().get(KernelLifecycle.class).stopAllServices(-1);
        kernel.getConfig().mergeMap(deploymentDocument.getTimestamp(), newConfig);
        try {
            // TODO: use kernel alts to resolve deployment folder and save to target.tlog
            Path path = kernel.getConfigPath().resolve(String.format("target_%s.tlog",
                    deploymentId.replace(':', '.').replace('/', '+')));
            ConfigSnapshotUtils.takeSnapshot(kernel, path);
        } catch (IOException e) {
            rollback(deploymentDocument, totallyCompleteFuture, e);
            return;
        }
        // TODO: point to correct file bootstrapManager.persistBootstrapTaskList(out);
        bootstrapManager.persistBootstrapTaskList();
        // TODO: KernelAlts prepare bootstrap

        try {
            int exitCode = bootstrapManager.executeAllBootstrapTasksSequentially();
            if (!bootstrapManager.hasNext()) {
                // TODO: flip symlinks, new to current
                logger.atInfo().log("Completed all bootstrap tasks. Continue to activate deployment changes");
            }
            logger.atInfo().log((exitCode == 101 ? "device reboot" : "kernel restart")
                    + " requested to complete bootstrap task");
            // TODO: Kernel shutdown supports exit code
            // System.exit(exitCode == 101 ? 101 : 100);
        } catch (ServiceUpdateException e) {
            rollback(deploymentDocument, totallyCompleteFuture, e);
            return;
        }
    }

    void rollback(DeploymentDocument deploymentDocument, CompletableFuture<DeploymentResult> totallyCompleteFuture,
                  Throwable failureCause) {
        String deploymentId = deploymentDocument.getDeploymentId();
        logger.atInfo(MERGE_CONFIG_EVENT_KEY).kv(DEPLOYMENT_ID_LOG_KEY, deploymentId)
                .log("Rolling back failed deployment");

        // Get the timestamp before merging snapshot. It will be used to check whether services have started.
        long mergeTime = rollbackConfig(deploymentId, totallyCompleteFuture, failureCause);
        if (mergeTime == -1) {
            return;
        }

        kernel.getContext().get(ExecutorService.class).execute(() -> {
            // TODO: Add timeout
            try {
                kernel.getContext().get(KernelLifecycle.class).startupAllServices();

                Collection<EvergreenService> servicesToTrackForRollback = kernel.orderedDependencies();

                waitForServicesToStart(servicesToTrackForRollback, mergeTime);

                logger.atInfo(MERGE_CONFIG_EVENT_KEY).kv(DEPLOYMENT_ID_LOG_KEY, deploymentId)
                        .log("All services rolled back");

                ConfigSnapshotUtils.cleanUpSnapshot(
                        ConfigSnapshotUtils.getSnapshotFilePath(kernel, deploymentId), logger);

                totallyCompleteFuture.complete(new DeploymentResult(
                        DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_COMPLETE, failureCause));
            } catch (InterruptedException | ServiceUpdateException e) {
                // Rollback execution failed
                logger.atError().setEventType(MERGE_ERROR_LOG_EVENT_KEY).setCause(e)
                        .log("Failed to rollback deployment");
                // TODO : Run user provided script to reach user defined safe state and
                //  set deployment status based on the success of the script run
                totallyCompleteFuture.complete(new DeploymentResult(
                        DeploymentResult.DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK, failureCause));
            }
        });
    }
}