/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.config.ConfigurationReader;
import com.aws.iot.evergreen.deployment.DeploymentConfigMerger.AggregateServicesChangeManager;
import com.aws.iot.evergreen.deployment.exceptions.ServiceUpdateException;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentResult;
import com.aws.iot.evergreen.deployment.model.FailureHandlingPolicy;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.KernelLifecycle;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

import static com.aws.iot.evergreen.deployment.DeploymentConfigMerger.DEPLOYMENT_ID_LOG_KEY;
import static com.aws.iot.evergreen.deployment.DeploymentConfigMerger.MERGE_CONFIG_EVENT_KEY;
import static com.aws.iot.evergreen.deployment.DeploymentConfigMerger.MERGE_ERROR_LOG_EVENT_KEY;
import static com.aws.iot.evergreen.deployment.DeploymentConfigMerger.waitForServicesToStart;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;

public abstract class DeploymentConfigActivation {
    protected final Kernel kernel;
    protected static final Logger logger = LogManager.getLogger(DeploymentConfigActivation.class);
    protected final DeploymentDocument deploymentDocument;
    protected final Map<Object, Object> newConfig;

    protected DeploymentConfigActivation(Kernel kernel, DeploymentDocument deploymentDocument,
                                         Map<Object, Object> newConfig) {
        this.kernel = kernel;
        this.deploymentDocument = deploymentDocument;
        this.newConfig = newConfig;
    }

    static DeploymentConfigActivation getActivationManager(Kernel kernel, DeploymentDocument deploymentDocument,
                                                           Map<Object, Object> newConfig) {
        BootstrapManager bootstrapManager = new BootstrapManager(kernel, newConfig);
        if (bootstrapManager.isBootstrapRequired()) {
            logger.atInfo().log("Continue with component bootstrap tasks");
            return new KernelUpdateActivation(kernel, deploymentDocument, newConfig, bootstrapManager);
        }
        logger.atInfo().log("Continue with component config activation");
        return new DefaultActivation(kernel, deploymentDocument, newConfig);
    }

    abstract void activate(CompletableFuture<DeploymentResult> totallyCompleteFuture);

    protected boolean takeConfigSnapshot(CompletableFuture<DeploymentResult> totallyCompleteFuture) {
        try {
            ConfigSnapshotUtils.takeSnapshot(kernel,
                    ConfigSnapshotUtils.getSnapshotFilePath(kernel, deploymentDocument.getDeploymentId()));
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
                    ConfigSnapshotUtils.getSnapshotFilePath(kernel, deploymentDocument.getDeploymentId()), true,
                    s -> !s.childOf(EvergreenService.RUNTIME_STORE_NAMESPACE_TOPIC));
            return mergeTime;
        } catch (IOException e) {
            // Could not merge old snapshot transaction log, rollback failed
            logger.atError().setEventType(MERGE_ERROR_LOG_EVENT_KEY).setCause(e).log("Failed to rollback deployment");
            // TODO : Run user provided script to reach user defined safe state
            //  set deployment status based on the success of the script run
            totallyCompleteFuture.complete(new DeploymentResult(
                    DeploymentResult.DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK, failureCause));
            return -1;
        }
    }

    /*
     * Evaluate if the customer specified failure handling policy is to auto-rollback
     */
    protected boolean isAutoRollbackRequested() {
        return FailureHandlingPolicy.ROLLBACK.equals(deploymentDocument.getFailureHandlingPolicy());
    }

    /**
     * Activation and rollback of default deployments.
     */
    private static class DefaultActivation extends DeploymentConfigActivation {
        protected DefaultActivation(Kernel kernel, DeploymentDocument deploymentDocument,
                                    Map<Object, Object> newConfig) {
            super(kernel, deploymentDocument, newConfig);
        }

        @Override
        void activate(CompletableFuture<DeploymentResult> totallyCompleteFuture) {
            if (isAutoRollbackRequested() && !takeConfigSnapshot(totallyCompleteFuture)) {
                return;
            }

            String deploymentId = deploymentDocument.getDeploymentId();

            AggregateServicesChangeManager servicesChangeManager;
            if (newConfig.containsKey(SERVICES_NAMESPACE_TOPIC)) {
                Map<String, Object> serviceConfig = (Map<String, Object>) newConfig.get(SERVICES_NAMESPACE_TOPIC);
                servicesChangeManager = new AggregateServicesChangeManager(kernel, serviceConfig);
            } else {
                servicesChangeManager = new AggregateServicesChangeManager(kernel, new HashMap<>());
            }

            // Get the timestamp before mergeMap(). It will be used to check whether services have started.
            long mergeTime = System.currentTimeMillis();

            // when deployment adds a new dependency (component B) to component A
            // the config for component B has to be merged in before externalDependenciesTopic of component A trigger
            // executing mergeMap using publish thread ensures this
            //TODO: runOnPublishQueueAndWait does not wait because updateActionForDeployment itself is run on the
            // publish queue. There needs to be another mechanism to ensure that mergemap completes and
            // all listeners trigger before rest of deployment work flow is executed.
            kernel.getContext().runOnPublishQueueAndWait(() ->
                    kernel.getConfig().mergeMap(deploymentDocument.getTimestamp(), newConfig));

            // wait until topic listeners finished processing mergeMap changes.
            kernel.getContext().runOnPublishQueue(() -> {
                // polling to wait for all services to be started.
                kernel.getContext().get(ExecutorService.class).execute(() -> {
                    //TODO: Add timeout
                    try {
                        servicesChangeManager.startNewServices();

                        // Restart any services that may have been broken before this deployment
                        // This is added to allow deployments to fix broken services
                        servicesChangeManager.reinstallBrokenServices();

                        Set<EvergreenService> servicesToTrack = servicesChangeManager.servicesToTrack();
                        logger.atDebug(MERGE_CONFIG_EVENT_KEY).kv("serviceToTrack", servicesToTrack)
                                .log("Applied new service config. Waiting for services to complete update");

                        waitForServicesToStart(servicesToTrack, mergeTime);
                        logger.atDebug(MERGE_CONFIG_EVENT_KEY).log("new/updated services are running, will now remove"
                                + " old services");
                        servicesChangeManager.removeObsoleteServices();
                        logger.atInfo(MERGE_CONFIG_EVENT_KEY).kv(DEPLOYMENT_ID_LOG_KEY, deploymentId)
                                .log("All services updated");
                        totallyCompleteFuture.complete(new DeploymentResult(
                                DeploymentResult.DeploymentStatus.SUCCESSFUL, null));
                    } catch (ServiceLoadException | InterruptedException | ServiceUpdateException
                            | ExecutionException e) {
                        logger.atError(MERGE_CONFIG_EVENT_KEY).kv(DEPLOYMENT_ID_LOG_KEY, deploymentId).setCause(e)
                                .log("Deployment failed");
                        if (isAutoRollbackRequested()) {
                            rollback(totallyCompleteFuture, e, servicesChangeManager.createRollbackManager());
                        } else {
                            totallyCompleteFuture.complete(new DeploymentResult(
                                    DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_NOT_REQUESTED, e));
                        }
                    }
                });
            });
        }

        void rollback(CompletableFuture<DeploymentResult> totallyCompleteFuture, Throwable failureCause,
                AggregateServicesChangeManager rollbackManager) {
            String deploymentId = deploymentDocument.getDeploymentId();
            logger.atInfo(MERGE_CONFIG_EVENT_KEY).kv(DEPLOYMENT_ID_LOG_KEY, deploymentId)
                    .log("Rolling back failed deployment");

            // Get the timestamp before merging snapshot. It will be used to check whether services have started.
            long mergeTime = rollbackConfig(totallyCompleteFuture, failureCause);
            if (mergeTime == -1) {
                return;
            }
            // wait until topic listeners finished processing read changes.
            kernel.getContext().runOnPublishQueue(() -> {
                // polling to wait for all services to be started.
                kernel.getContext().get(ExecutorService.class).execute(() -> {
                    // TODO: Add timeout
                    try {
                        rollbackManager.startNewServices();
                        rollbackManager.reinstallBrokenServices();

                        Set<EvergreenService> servicesToTrackForRollback = rollbackManager.servicesToTrack();

                        waitForServicesToStart(servicesToTrackForRollback, mergeTime);

                        rollbackManager.removeObsoleteServices();
                        logger.atInfo(MERGE_CONFIG_EVENT_KEY).kv(DEPLOYMENT_ID_LOG_KEY, deploymentId)
                                .log("All services rolled back");

                        ConfigSnapshotUtils.cleanUpSnapshot(
                                ConfigSnapshotUtils.getSnapshotFilePath(kernel, deploymentId), logger);

                        totallyCompleteFuture.complete(new DeploymentResult(
                                DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_COMPLETE, failureCause));
                    } catch (InterruptedException | ServiceUpdateException | ExecutionException
                            | ServiceLoadException e) {
                        // Rollback execution failed
                        logger.atError().setEventType(MERGE_ERROR_LOG_EVENT_KEY).setCause(e)
                                .log("Failed to rollback deployment");
                        // TODO : Run user provided script to reach user defined safe state and
                        //  set deployment status based on the success of the script run
                        totallyCompleteFuture.complete(new DeploymentResult(
                                DeploymentResult.DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK, failureCause));
                    }
                });
            });
        }
    }

    /**
     * Activation and rollback of Kernel update deployments.
     */
    private static class KernelUpdateActivation extends DeploymentConfigActivation {
        private final BootstrapManager bootstrapManager;

        protected KernelUpdateActivation(Kernel kernel, DeploymentDocument deploymentDocument,
                                         Map<Object, Object> newConfig, BootstrapManager bootstrapManager) {
            super(kernel, deploymentDocument, newConfig);
            this.bootstrapManager = bootstrapManager;
        }

        @Override
        void activate(CompletableFuture<DeploymentResult> totallyCompleteFuture) {
            if (!takeConfigSnapshot(totallyCompleteFuture)) {
                return;
            }
            String deploymentId = deploymentDocument.getDeploymentId();

            // Wait for all services to close
            kernel.getContext().get(KernelLifecycle.class).stopAllServices(-1);
            kernel.getConfig().mergeMap(deploymentDocument.getTimestamp(), newConfig);
            try {
                // TODO: use kernel alts to resolve deployment folder and save to target.tlog
                Path path = kernel.getConfigPath().resolve(String.format("target_%s.tlog",
                        deploymentId.replace(':', '.').replace('/', '+')));
                ConfigSnapshotUtils.takeSnapshot(kernel, path);
            } catch (IOException e) {
                rollback(totallyCompleteFuture, e);
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
                rollback(totallyCompleteFuture, e);
                return;
            }
        }

        void rollback(CompletableFuture<DeploymentResult> totallyCompleteFuture, Throwable failureCause) {
            String deploymentId = deploymentDocument.getDeploymentId();
            logger.atInfo(MERGE_CONFIG_EVENT_KEY).kv(DEPLOYMENT_ID_LOG_KEY, deploymentId)
                    .log("Rolling back failed deployment");

            // Get the timestamp before merging snapshot. It will be used to check whether services have started.
            long mergeTime = rollbackConfig(totallyCompleteFuture, failureCause);
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
}
