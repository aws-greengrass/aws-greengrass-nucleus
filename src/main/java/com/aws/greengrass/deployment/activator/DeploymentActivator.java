/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.activator;

import com.aws.greengrass.config.ConfigurationReader;
import com.aws.greengrass.config.UpdateBehaviorTree;
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

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeploymentConfigMerger.MERGE_ERROR_LOG_EVENT_KEY;
import static com.aws.greengrass.ipc.AuthenticationHandler.AUTHENTICATION_TOKEN_LOOKUP_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;

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
            ConfigurationReader.mergeTLogInto(kernel.getConfig(),
                    deploymentDirectoryManager.getSnapshotFilePath(), true, null);
            return mergeTime;
        } catch (IOException e) {
            // Could not merge old snapshot transaction log, rollback failed
            logger.atError().setEventType(MERGE_ERROR_LOG_EVENT_KEY).setCause(e).log("Failed to rollback deployment");
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

    protected void updateConfiguration(long timestamp, Map<String, Object> newConfig) {
        // when deployment adds a new dependency (component B) to component A
        // the config for component B has to be merged in before externalDependenciesTopic of component A trigger
        // executing mergeMap using publish thread ensures this
        kernel.getContext().runOnPublishQueueAndWait(() -> kernel.getConfig().updateMap(
                newConfig, createDeploymentMergeBehavior(timestamp, newConfig)));
    }

    protected UpdateBehaviorTree createDeploymentMergeBehavior(long deploymentTimestamp,
                                                               Map<String, Object> newConfig) {
        // root: MERGE
        //   services: MERGE
        //     *: REPLACE
        //       runtime: MERGE
        //       _private: MERGE
        //       configuration: REPLACE with deployment timestamp
        //     AUTH_TOKEN: MERGE

        long now = System.currentTimeMillis();
        UpdateBehaviorTree rootMergeBehavior = new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.MERGE, now);
        UpdateBehaviorTree servicesMergeBehavior = new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.MERGE, now);
        UpdateBehaviorTree insideServiceMergeBehavior =
                new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.REPLACE, now);
        UpdateBehaviorTree serviceRuntimeMergeBehavior =
                new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.MERGE, now);
        UpdateBehaviorTree servicePrivateMergeBehavior =
                new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.MERGE, now);

        rootMergeBehavior.getChildOverride().put(SERVICES_NAMESPACE_TOPIC, servicesMergeBehavior);
        servicesMergeBehavior.getChildOverride().put(UpdateBehaviorTree.WILDCARD, insideServiceMergeBehavior);
        servicesMergeBehavior.getChildOverride().put(AUTHENTICATION_TOKEN_LOOKUP_KEY,
                new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.MERGE, now));

        // Set merge mode for all builtin services
        kernel.orderedDependencies().stream()
                .filter(GreengrassService::isBuiltin)
                // If the builtin service is somehow in the new config, then keep the default behavior of
                // replacing the existing values
                .filter(s -> !((Map) newConfig.get(SERVICES_NAMESPACE_TOPIC)).containsKey(s.getServiceName()))
                .forEach(s -> servicesMergeBehavior.getChildOverride()
                        .put(s.getServiceName(), new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.MERGE, now)));

        insideServiceMergeBehavior.getChildOverride().put(
                GreengrassService.RUNTIME_STORE_NAMESPACE_TOPIC, serviceRuntimeMergeBehavior);
        insideServiceMergeBehavior.getChildOverride().put(
                GreengrassService.PRIVATE_STORE_NAMESPACE_TOPIC, servicePrivateMergeBehavior);
        UpdateBehaviorTree serviceConfigurationMergeBehavior =
                new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.REPLACE, deploymentTimestamp);
        insideServiceMergeBehavior.getChildOverride().put(
                CONFIGURATION_CONFIG_KEY, serviceConfigurationMergeBehavior);

        return rootMergeBehavior;
    }
}
