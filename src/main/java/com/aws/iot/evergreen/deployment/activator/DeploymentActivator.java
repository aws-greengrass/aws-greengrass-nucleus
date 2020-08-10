/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment.activator;

import com.aws.iot.evergreen.config.ConfigurationReader;
import com.aws.iot.evergreen.deployment.ConfigSnapshotUtils;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentResult;
import com.aws.iot.evergreen.deployment.model.FailureHandlingPolicy;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.aws.iot.evergreen.deployment.DeploymentConfigMerger.MERGE_ERROR_LOG_EVENT_KEY;

public abstract class DeploymentActivator {
    protected final Kernel kernel;
    protected static final Logger logger = LogManager.getLogger(DeploymentActivator.class);

    protected DeploymentActivator(Kernel kernel) {
        this.kernel = kernel;
    }

    public abstract void activate(Map<Object, Object> newConfig, DeploymentDocument deploymentDocument,
                                  CompletableFuture<DeploymentResult> totallyCompleteFuture);

    protected boolean takeConfigSnapshot(String deploymentId,
                                         CompletableFuture<DeploymentResult> totallyCompleteFuture) {
        try {
            ConfigSnapshotUtils.takeSnapshot(kernel,
                    ConfigSnapshotUtils.getSnapshotFilePath(kernel, deploymentId));
            return true;
        } catch (IOException e) {
            // Failed to record snapshot hence did not execute merge, no rollback needed
            logger.atError().setEventType(MERGE_ERROR_LOG_EVENT_KEY).setCause(e)
                    .log("Failed to take a snapshot for rollback");
            totallyCompleteFuture.complete(new DeploymentResult(
                    DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, e));
            return false;
        }
    }

    @SuppressWarnings("PMD.PrematureDeclaration")
    protected long rollbackConfig(String deploymentId, CompletableFuture<DeploymentResult> totallyCompleteFuture,
                                  Throwable failureCause) {
        long mergeTime = System.currentTimeMillis();
        // The lambda is set up to ignore anything that is a child of DEPLOYMENT_SAFE_NAMESPACE_TOPIC
        // Does not necessarily have to be a child of services, customers are free to put this namespace wherever
        // they like in the config
        Throwable error = kernel.getContext().runOnPublishQueueAndWait(() ->
                ConfigurationReader.mergeTLogInto(kernel.getConfig(),
                        ConfigSnapshotUtils.getSnapshotFilePath(kernel, deploymentId), true,
                        s -> !s.childOf(EvergreenService.RUNTIME_STORE_NAMESPACE_TOPIC)));
        if (error != null) {
            // Could not merge old snapshot transaction log, rollback failed
            logger.atError(MERGE_ERROR_LOG_EVENT_KEY, error).log("Failed to rollback deployment");
            // TODO : Run user provided script to reach user defined safe state
            //  set deployment status based on the success of the script run
            totallyCompleteFuture.complete(new DeploymentResult(
                    DeploymentResult.DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK, failureCause));
            return -1;
        }
        return mergeTime;
    }

    /*
     * Evaluate if the customer specified failure handling policy is to auto-rollback
     */
    protected boolean isAutoRollbackRequested(DeploymentDocument deploymentDocument) {
        return FailureHandlingPolicy.ROLLBACK.equals(deploymentDocument.getFailureHandlingPolicy());
    }

}
