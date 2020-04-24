/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.config.ConfigurationReader;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.exceptions.ServiceUpdateException;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentResult;
import com.aws.iot.evergreen.deployment.model.DeploymentResult.DeploymentStatus;
import com.aws.iot.evergreen.deployment.model.FailureHandlingPolicy;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.GenericExternalService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.UpdateSystemSafelyService;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import javax.inject.Inject;

import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;

@AllArgsConstructor
public class DeploymentConfigMerger {
    private static final String MERGE_CONFIG_EVENT_KEY = "merge-config";
    private static final String MERGE_ERROR_LOG_EVENT_KEY = "config-update-error";
    private static final String DEPLOYMENT_ID_LOG_KEY = "deploymentId";
    private static final String ROLLBACK_SNAPSHOT_PATH_FORMAT = "rollback_snapshot_%s.tlog";

    private static final Logger logger = LogManager.getLogger(DeploymentConfigMerger.class);

    @Inject
    private Kernel kernel;

    /**
     * Merge in new configuration values and new services.
     *
     * @param deploymentDocument deployment document
     * @param newConfig          the map of new configuration
     * @return future which completes only once the config is merged and all the services in the config are running
     */
    public Future<DeploymentResult> mergeInNewConfig(DeploymentDocument deploymentDocument,
                                                     Map<Object, Object> newConfig) {
        CompletableFuture<DeploymentResult> totallyCompleteFuture = new CompletableFuture<>();
        long timestamp = deploymentDocument.getTimestamp();

        if (newConfig.get(SERVICES_NAMESPACE_TOPIC) == null) {
            kernel.getConfig().mergeMap(timestamp, newConfig);
            totallyCompleteFuture.complete(new DeploymentResult(DeploymentStatus.SUCCESSFUL, null));
            return totallyCompleteFuture;
        }

        Map<String, Object> serviceConfig = (Map<String, Object>) newConfig.get(SERVICES_NAMESPACE_TOPIC);
        AggregateServicesChangeManager servicesChangeManager =
                new AggregateServicesChangeManager(kernel, serviceConfig);

        String deploymentId = deploymentDocument.getDeploymentId();
        kernel.getContext().get(UpdateSystemSafelyService.class).addUpdateAction(deploymentId, () -> {

            // if the update is cancelled, don't perform merge
            if (totallyCompleteFuture.isCancelled()) {
                return;
            }

            FailureHandlingPolicy failureHandlingPolicy = deploymentDocument.getFailureHandlingPolicy();
            if (isAutoRollbackRequested(failureHandlingPolicy)) {
                try {
                    takeSnapshotForRollback(deploymentId);
                } catch (IOException e) {
                    // Failed to record snapshot hence did not execute merge, no rollback needed
                    logger.atError().setEventType(MERGE_ERROR_LOG_EVENT_KEY).setCause(e).log("Failed to take a "
                            + "snapshot for rollback");
                    totallyCompleteFuture.complete(new DeploymentResult(DeploymentStatus.FAILED_NO_STATE_CHANGE, e));
                    return;
                }
            }
            // Get the timestamp before mergeMap(). It will be used to check whether services have started.
            long mergeTime = System.currentTimeMillis();

            kernel.getConfig().mergeMap(timestamp, newConfig);
            // wait until topic listeners finished processing mergeMap changes.
            kernel.getContext().runOnPublishQueueAndWait(() -> {
                // polling to wait for all services to be started.
                kernel.getContext().get(ExecutorService.class).execute(() -> {
                    //TODO: Add timeout
                    try {
                        servicesChangeManager.startNewServices();

                        Set<EvergreenService> servicesToTrack = servicesChangeManager.servicesToTrack();
                        logger.atDebug(MERGE_CONFIG_EVENT_KEY).kv("serviceToTrack", servicesToTrack)
                                .log("Applied new service config. Waiting for services to complete update");

                        waitForServicesToStart(servicesToTrack, totallyCompleteFuture, mergeTime);
                        if (totallyCompleteFuture.isCancelled()) {
                            // TODO : Does this need rolling back to old config?
                            logger.atWarn(MERGE_CONFIG_EVENT_KEY).kv(DEPLOYMENT_ID_LOG_KEY, deploymentId)
                                    .log("merge-config-cancelled");
                            return;
                        }
                        servicesChangeManager.removeObsoleteServices();
                        logger.atInfo(MERGE_CONFIG_EVENT_KEY).kv(DEPLOYMENT_ID_LOG_KEY, deploymentId)
                                .log("All services updated");
                        totallyCompleteFuture.complete(new DeploymentResult(DeploymentStatus.SUCCESSFUL, null));
                    } catch (ServiceLoadException | InterruptedException | ServiceUpdateException
                            | ExecutionException e) {
                        logger.atError(MERGE_CONFIG_EVENT_KEY).kv(DEPLOYMENT_ID_LOG_KEY, deploymentId).setCause(e)
                                .log("Deployment failed");
                        if (isAutoRollbackRequested(failureHandlingPolicy)) {
                            rollback(deploymentId, totallyCompleteFuture, e, servicesChangeManager.toRollback());
                        } else {
                            totallyCompleteFuture
                                    .complete(new DeploymentResult(DeploymentStatus.FAILED_ROLLBACK_NOT_REQUESTED, e));
                        }
                    }
                });
            });

        });
        return totallyCompleteFuture;
    }

    /*
     * Rollback kernel to the state recorded before merging deployment config
     */
    private void rollback(String deploymentId, CompletableFuture<DeploymentResult> totallyCompleteFuture,
                          Throwable failureCause, AggregateServicesChangeManager servicesChangeManager) {

        logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY, deploymentId).log("Rolling back failed deployment");

        // Get the timestamp before merging snapshot. It will be used to check whether services have started.
        long mergeTime;
        try {
            mergeTime = System.currentTimeMillis();
            ConfigurationReader.mergeTlogIntoConfig(kernel.getConfig(),
                    kernel.getConfigPath().resolve(String.format(ROLLBACK_SNAPSHOT_PATH_FORMAT, deploymentId)), true);
        } catch (IOException e) {
            // Could not merge old snapshot transaction log, rollback failed
            logger.atError().setEventType(MERGE_ERROR_LOG_EVENT_KEY).setCause(e).log("Failed to rollback deployment");
            // TODO : Run user provided script to reach user defined safe state
            //  set deployment status based on the success of the script run
            totallyCompleteFuture
                    .complete(new DeploymentResult(DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK, failureCause));
            return;
        }
        // wait until topic listeners finished processing read changes.
        kernel.getContext().runOnPublishQueueAndWait(() -> {
            // polling to wait for all services to be started.
            kernel.getContext().get(ExecutorService.class).execute(() -> {
                // TODO: Add timeout
                try {
                    servicesChangeManager.startNewServices();

                    Set<EvergreenService> servicesToTrackForRollback = servicesChangeManager.servicesToTrack();

                    waitForServicesToStart(servicesToTrackForRollback, totallyCompleteFuture, mergeTime);

                    servicesChangeManager.removeObsoleteServices();
                    logger.atInfo(MERGE_CONFIG_EVENT_KEY).kv(DEPLOYMENT_ID_LOG_KEY, deploymentId)
                            .log("All services rolled back");

                    cleanUpSnapshot(deploymentId);

                    totallyCompleteFuture
                            .complete(new DeploymentResult(DeploymentStatus.FAILED_ROLLBACK_COMPLETE, failureCause));
                } catch (InterruptedException | ServiceUpdateException | ExecutionException | ServiceLoadException e) {
                    // Rollback execution failed
                    logger.atError().setEventType(MERGE_ERROR_LOG_EVENT_KEY).setCause(e)
                            .log("Failed to rollback deployment");
                    // TODO : Run user provided script to reach user defined safe state and
                    //  set deployment status based on the success of the script run
                    totallyCompleteFuture
                            .complete(new DeploymentResult(DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK, failureCause));
                }
            });
        });
    }

    /**
     * Completes the provided future when all of the listed services are running.
     *
     * @param servicesToTrack       service to track
     * @param totallyCompleteFuture future to complete
     * @param mergeTime             time the merge was started, used to check if a service is broken due to the merge
     * @throws InterruptedException   if the thread is interrupted while waiting here
     * @throws ServiceUpdateException if a service could not be updated
     */
    public static void waitForServicesToStart(Set<EvergreenService> servicesToTrack,
                                              CompletableFuture<?> totallyCompleteFuture, long mergeTime)
            throws InterruptedException, ServiceUpdateException {
        while (!totallyCompleteFuture.isCancelled()) {
            boolean allServicesRunning = true;
            for (EvergreenService service : servicesToTrack) {
                State state = service.getState();

                // If a service is previously BROKEN, its state might have not been updated yet when this check
                // executes. Therefore we first check the service state has been updated since merge map occurs.
                if (service.getStateModTime() > mergeTime && State.BROKEN.equals(state)) {
                    logger.atWarn(MERGE_CONFIG_EVENT_KEY).kv("serviceName", service.getName())
                            .log("merge-config-service BROKEN");
                    throw new ServiceUpdateException(
                            String.format("Service %s in broken state after deployment", service.getName()));
                }
                if (!service.reachedDesiredState()) {
                    allServicesRunning = false;
                }
                if (State.RUNNING.equals(state) || State.FINISHED.equals(state)) {
                    continue;
                }
                allServicesRunning = false;
            }
            if (allServicesRunning) {
                return;
            }
            Thread.sleep(1000); // hardcoded
        }
    }

    /*
     * Take a snapshot in a transaction log file before rollback if rollback is applicable for deployment
     */
    private void takeSnapshotForRollback(String deploymentId) throws IOException {
        // record kernel snapshot
        kernel.writeEffectiveConfigAsTransactionLog(
                kernel.getConfigPath().resolve(String.format(ROLLBACK_SNAPSHOT_PATH_FORMAT, deploymentId)));

    }

    /*
     * Clean up snapshot file
     */
    private void cleanUpSnapshot(String deploymentId) {
        try {
            Files.delete(kernel.getConfigPath().resolve(String.format(ROLLBACK_SNAPSHOT_PATH_FORMAT, deploymentId)));
        } catch (IOException e) {
            logger.atError().setEventType(MERGE_ERROR_LOG_EVENT_KEY).setCause(e)
                    .log("Error cleaning up kernel snapshot");
        }
    }

    /*
     * Evaluate if the customer specified failure handling policy is to auto-rollback
     */
    private boolean isAutoRollbackRequested(FailureHandlingPolicy failureHandlingPolicy) {
        return FailureHandlingPolicy.ROLLBACK.equals(failureHandlingPolicy);
    }

    @Getter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    private class AggregateServicesChangeManager {
        private Kernel kernel;
        private Set<String> servicesToAdd;
        private Set<String> servicesToUpdate;
        private Set<String> servicesToRemove;

        /**
         * Constructs an object based on the current Kernel state and the config to be merged.
         *
         * @param kernel           evergreen kernel
         * @param newServiceConfig new config to be merged for deployment
         */
        public AggregateServicesChangeManager(Kernel kernel, Map<String, Object> newServiceConfig) {
            Set<String> runningUserServices = kernel.orderedDependencies().stream()
                    .filter(evergreenService -> evergreenService instanceof GenericExternalService)
                    .map(EvergreenService::getName).collect(Collectors.toSet());

            this.kernel = kernel;

            this.servicesToAdd =
                    newServiceConfig.keySet().stream().filter(serviceName -> !runningUserServices.contains(serviceName))
                            .collect(Collectors.toSet());

            this.servicesToUpdate =
                    newServiceConfig.keySet().stream().filter(serviceName -> runningUserServices.contains(serviceName))
                            .collect(Collectors.toSet());

            // TODO: handle removing services that are running within the JVM but defined via config
            this.servicesToRemove =
                    runningUserServices.stream().filter(serviceName -> !newServiceConfig.containsKey(serviceName))
                            .collect(Collectors.toSet());
        }

        /**
         * Get the service change manager that can be used for rolling back the current merge.
         *
         * @return An instance of service change manager to be used for rollback
         */
        public AggregateServicesChangeManager toRollback() {
            // For rollback, services the deployment originally intended to add should be removed
            // and services it intended to remove should be added back
            return new AggregateServicesChangeManager(kernel, servicesToRemove, servicesToUpdate, servicesToAdd);
        }

        /**
         * Start the new services the merge intends to add.
         *
         * @throws ServiceLoadException when any service to be started could not be located
         */
        public void startNewServices() throws ServiceLoadException {
            for (String serviceName : servicesToAdd) {
                EvergreenService service = kernel.locate(serviceName);
                service.requestStart();
            }
        }

        /**
         * Clean up services that the merge intends to remove.
         *
         * @throws InterruptedException when the merge is interrupted
         * @throws ExecutionException   when error is encountered while trying to close any service
         */
        public void removeObsoleteServices() throws InterruptedException, ExecutionException {
            Set<Future<Void>> serviceClosedFutures = new HashSet<>();
            servicesToRemove.forEach(serviceName -> {
                try {
                    EvergreenService eg = kernel.locate(serviceName);
                    serviceClosedFutures.add(eg.close());
                } catch (ServiceLoadException e) {
                    logger.atError().setCause(e).addKeyValue("serviceName", serviceName)
                            .log("Could not locate EvergreenService to close service");
                    // No need to handle the error when trying to stop a non-existing service.
                }
            });
            // waiting for removed service to close before removing reference and config entry
            for (Future<?> serviceClosedFuture : serviceClosedFutures) {
                serviceClosedFuture.get();
            }
            servicesToRemove.forEach(serviceName -> {
                kernel.getContext().remove(serviceName);
                kernel.findServiceTopic(serviceName).remove();
            });
        }

        /**
         * Get the set of services whose state change needs to be tracked to assess the outcome of the merge.
         *
         * @return set of services to track state change for
         * @throws ServiceLoadException when any service whose state change needs to be tracked cannot be located
         */
        public Set<EvergreenService> servicesToTrack() throws ServiceLoadException {
            Set<String> serviceNames = new HashSet<>(servicesToAdd);
            serviceNames.addAll(servicesToUpdate);

            Set<EvergreenService> servicesToTrack = new HashSet<>();
            for (String serviceName : serviceNames) {
                EvergreenService eg = kernel.locate(serviceName);
                servicesToTrack.add(eg);
            }
            return servicesToTrack;
        }

    }
}
