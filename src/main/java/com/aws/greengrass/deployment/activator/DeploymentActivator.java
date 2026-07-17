/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.activator;

import com.aws.greengrass.config.ConfigurationReader;
import com.aws.greengrass.config.UpdateBehaviorTree;
import com.aws.greengrass.deployment.DeploymentDirectoryManager;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.exceptions.DeploymentException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.deployment.model.FailureHandlingPolicy;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.KernelLifecycle;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

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

    public abstract void activate(Map<String, Object> newConfig, Deployment deployment, long configMergeTimestamp,
                                  CompletableFuture<DeploymentResult> totallyCompleteFuture);

    protected boolean takeConfigSnapshot(CompletableFuture<DeploymentResult> totallyCompleteFuture) {
         if (totallyCompleteFuture.isCancelled()) {
            return false;
        }
        try {
            deploymentDirectoryManager.takeConfigSnapshot(deploymentDirectoryManager.getSnapshotFilePath());
            return true;
        } catch (IOException e) {
            // Failed to record snapshot hence did not execute merge, no rollback needed
            logger.atError().setEventType(MERGE_ERROR_LOG_EVENT_KEY).setCause(e)
                    .log("Failed to take a snapshot for rollback");
            totallyCompleteFuture.complete(
                    new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE,
                            new DeploymentException("Failed to take a snapshot for rollback", e)
                                    .withErrorContext(e, DeploymentErrorCode.IO_WRITE_ERROR)));
            return false;
        }
    }

    protected long rollbackConfig(CompletableFuture<DeploymentResult> totallyCompleteFuture, Throwable failureCause) {
        AtomicLong mergeTime = new AtomicLong(-1);
        // Run on publish thread to ensure lifecycle listeners only run once all config changes go through
        kernel.getContext().runOnPublishQueueAndWait(() -> {
            try {
                mergeTime.set(System.currentTimeMillis());
                ConfigurationReader.updateFromTLog(kernel.getConfig(), deploymentDirectoryManager.getSnapshotFilePath(),
                        true, null, createRollbackMergeBehavior());
                // Immediately truncate the tlog such that the config.tlog file only contains the correct rolled back
                // information. Without this step, a nucleus reboot could cause the configuration to contain "newer"
                // values even though we wanted those values to be rolled back.
                kernel.getContext().get(KernelLifecycle.class).getTlog().truncateNow();
            } catch (IOException e) {
                mergeTime.set(-1);
                // Could not merge old snapshot transaction log, rollback failed
                logger.atError().setEventType(MERGE_ERROR_LOG_EVENT_KEY).setCause(e)
                        .log("Failed to rollback deployment");
                totallyCompleteFuture.complete(
                        new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK,
                                failureCause));
            }
        });
        return mergeTime.get();
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


    /**
     * Names of the builtin services whose config must be protected from merge/rollback removal: the
     * statically known autostart builtin names unioned with the builtins currently present in the live
     * dependency graph (which covers autostart plugin builtins, whose names are not statically known).
     *
     * @return builtin service names to protect
     */
    protected Set<String> getBuiltinServiceNamesToProtect() {
        Set<String> builtinServiceNames = new HashSet<>(KernelLifecycle.AUTOSTART_BUILTIN_SERVICE_NAMES);
        kernel.orderedDependencies().stream()
                .filter(GreengrassService::isBuiltin)
                .map(GreengrassService::getServiceName)
                .forEach(builtinServiceNames::add);
        return builtinServiceNames;
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

        insideServiceMergeBehavior.getChildOverride().put(
                GreengrassService.RUNTIME_STORE_NAMESPACE_TOPIC, serviceRuntimeMergeBehavior);
        insideServiceMergeBehavior.getChildOverride().put(
                GreengrassService.PRIVATE_STORE_NAMESPACE_TOPIC, servicePrivateMergeBehavior);
        UpdateBehaviorTree serviceConfigurationMergeBehavior =
                new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.REPLACE, deploymentTimestamp);
        insideServiceMergeBehavior.getChildOverride().put(
                CONFIGURATION_CONFIG_KEY, serviceConfigurationMergeBehavior);

        // Set merge mode for all builtin services. Names are derived from static builtin identity and
        // unioned with the live dependency graph: the graph alone is not a safe key, because a builtin
        // service can be erroneously missing from it while still running, in which case a graph-keyed
        // protection would let this merge delete the builtin's entire config. Protecting a builtin that
        // is not currently in the graph is harmless — it only retains existing config. Autostart plugin
        // builtins, whose names are not statically known, are covered by the graph-derived set.
        getBuiltinServiceNamesToProtect().stream()
                // If the builtin service is somehow in the new config, then keep the default behavior of
                // replacing the existing values
                .filter(name -> !((Map) newConfig.get(SERVICES_NAMESPACE_TOPIC)).containsKey(name))
                .forEach(name -> servicesMergeBehavior.getChildOverride()
                        .put(name, new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.MERGE, now)));

        logger.atDebug().kv("Root merge behavior", rootMergeBehavior)
                .log("Created deployment configuration root merge behavior.");
        return rootMergeBehavior;
    }

    protected UpdateBehaviorTree createRollbackMergeBehavior() {
        // root: MERGE
        //   services: MERGE
        //     *: REPLACE
        //       runtime: MERGE
        //       _private: MERGE
        //       configuration: REPLACE
        //     <builtin services>: MERGE
        //     AUTH_TOKEN: MERGE

        // For rollback the timestamp from the snapshot will be used and not this timestamp
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

        insideServiceMergeBehavior.getChildOverride().put(
                GreengrassService.RUNTIME_STORE_NAMESPACE_TOPIC, serviceRuntimeMergeBehavior);
        insideServiceMergeBehavior.getChildOverride().put(
                GreengrassService.PRIVATE_STORE_NAMESPACE_TOPIC, servicePrivateMergeBehavior);
        UpdateBehaviorTree serviceConfigurationMergeBehavior =
                new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.REPLACE, now);
        insideServiceMergeBehavior.getChildOverride().put(
                CONFIGURATION_CONFIG_KEY, serviceConfigurationMergeBehavior);

        // Set merge mode for all builtin services so that a rollback never discards a builtin service's
        // config just because it is absent from the snapshot tlog. Without this, replaying a snapshot
        // which predates a builtin's config (or a snapshot dumped while the builtin's config subtree was
        // erroneously detached from the config tree) removes the builtin's entire config while its
        // service is still running. Values present in the snapshot are still restored as before; the
        // only behavior change is that nodes newly created under a builtin during the failed deployment
        // are retained rather than discarded, which is harmless bookkeeping.
        getBuiltinServiceNamesToProtect().forEach(name -> servicesMergeBehavior.getChildOverride()
                .put(name, new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.MERGE, now)));

        return rootMergeBehavior;
    }

}
