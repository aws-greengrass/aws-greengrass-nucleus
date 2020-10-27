/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.activator;

import com.aws.greengrass.config.ConfigurationReader;
import com.aws.greengrass.deployment.DeploymentDirectoryManager;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.deployment.model.FailureHandlingPolicy;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.aws.greengrass.deployment.DeploymentConfigMerger.MERGE_ERROR_LOG_EVENT_KEY;

public abstract class DeploymentActivator {
    protected final Kernel kernel;
    protected final DeploymentDirectoryManager deploymentDirectoryManager;
    protected static final Logger logger = LogManager.getLogger(DeploymentActivator.class);

    protected DeploymentActivator(Kernel kernel) {
        this.kernel = kernel;
        this.deploymentDirectoryManager = kernel.getContext().get(DeploymentDirectoryManager.class);
    }

    public abstract void activate(Map<String, Object> newConfig, Deployment deployment,
                                  CompletableFuture<DeploymentResult> totallyCompleteFuture);

    protected boolean takeConfigSnapshot(CompletableFuture<DeploymentResult> totallyCompleteFuture) {
        try {
            deploymentDirectoryManager.takeConfigSnapshot(deploymentDirectoryManager.getSnapshotFilePath());
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

    protected long rollbackConfig(CompletableFuture<DeploymentResult> totallyCompleteFuture, Throwable failureCause) {
        long mergeTime;
        try {
            mergeTime = System.currentTimeMillis();
            // The lambda is set up to ignore anything that is a child of DEPLOYMENT_SAFE_NAMESPACE_TOPIC
            // Does not necessarily have to be a child of services, customers are free to put this namespace wherever
            // they like in the config
            ConfigurationReader.mergeTLogInto(kernel.getConfig(),
                    deploymentDirectoryManager.getSnapshotFilePath(), true,
                    s -> !s.childOf(GreengrassService.RUNTIME_STORE_NAMESPACE_TOPIC));
            return mergeTime;
        } catch (IOException e) {
            // Could not merge old snapshot transaction log, rollback failed
            logger.atError().setEventType(MERGE_ERROR_LOG_EVENT_KEY).setCause(e).log("Failed to rollback deployment");
            // GG_NEEDS_REVIEW: TODO : Run user provided script to reach user defined safe state
            //  set deployment status based on the success of the script run
            totallyCompleteFuture.complete(new DeploymentResult(
                    DeploymentResult.DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK, failureCause));
            return -1;
        }
    }

    /*
     * Evaluate if the customer specified failure handling policy is to auto-rollback
     */
    protected boolean isAutoRollbackRequested(DeploymentDocument deploymentDocument) {
        return FailureHandlingPolicy.ROLLBACK.equals(deploymentDocument.getFailureHandlingPolicy());
    }

}
