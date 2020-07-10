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
import com.aws.iot.evergreen.deployment.model.DeploymentSafetyPolicy;
import com.aws.iot.evergreen.deployment.model.FailureHandlingPolicy;
import com.aws.iot.evergreen.kernel.EvergreenService;
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
import java.nio.file.Path;
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

        if (newConfig.get(SERVICES_NAMESPACE_TOPIC) == null) {
            kernel.getConfig().mergeMap(deploymentDocument.getTimestamp(), newConfig);
            totallyCompleteFuture.complete(new DeploymentResult(DeploymentStatus.SUCCESSFUL, null));
            return totallyCompleteFuture;
        }

        if (DeploymentSafetyPolicy.CHECK_SAFETY.equals(deploymentDocument.getDeploymentSafetyPolicy())) {
            kernel.getContext().get(UpdateSystemSafelyService.class)
                    .addUpdateAction(deploymentDocument.getDeploymentId(),
                            () -> updateActionForDeployment(newConfig, deploymentDocument, totallyCompleteFuture));
        } else {
            logger.atInfo().log("Deployment is configured to skip safety check, not waiting for safe time to update");
            updateActionForDeployment(newConfig, deploymentDocument, totallyCompleteFuture);
        }

        return totallyCompleteFuture;
    }

    private void updateActionForDeployment(Map<Object, Object> newConfig, DeploymentDocument deploymentDocument,
                                           CompletableFuture<DeploymentResult> totallyCompleteFuture) {
        String deploymentId = deploymentDocument.getDeploymentId();

        // if the update is cancelled, don't perform merge
        if (totallyCompleteFuture.isCancelled()) {
            logger.atInfo(MERGE_CONFIG_EVENT_KEY).kv("deployment", deploymentId)
                    .log("Future was cancelled so no need to go through with the update");
            return;
        }
        logger.atInfo(MERGE_CONFIG_EVENT_KEY).kv("deployment", deploymentId)
                .log("Applying deployment changes , deployment cannot be cancelled now");

        FailureHandlingPolicy failureHandlingPolicy = deploymentDocument.getFailureHandlingPolicy();
        if (isAutoRollbackRequested(failureHandlingPolicy)) {
            try {
                takeSnapshotForRollback(deploymentId);
            } catch (IOException e) {
                // Failed to record snapshot hence did not execute merge, no rollback needed
                logger.atError().setEventType(MERGE_ERROR_LOG_EVENT_KEY).setCause(e)
                        .log("Failed to take a snapshot for rollback");
                totallyCompleteFuture.complete(new DeploymentResult(DeploymentStatus.FAILED_NO_STATE_CHANGE, e));
                return;
            }
        }

        Map<String, Object> serviceConfig = (Map<String, Object>) newConfig.get(SERVICES_NAMESPACE_TOPIC);
        AggregateServicesChangeManager servicesChangeManager =
                new AggregateServicesChangeManager(kernel, serviceConfig);

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
    }

    /*
     * Rollback kernel to the state recorded before merging deployment config
     */
    private void rollback(String deploymentId, CompletableFuture<DeploymentResult> totallyCompleteFuture,
                          Throwable failureCause, AggregateServicesChangeManager servicesChangeManager) {

        logger.atInfo(MERGE_CONFIG_EVENT_KEY).kv(DEPLOYMENT_ID_LOG_KEY, deploymentId)
                .log("Rolling back failed deployment");

        // Get the timestamp before merging snapshot. It will be used to check whether services have started.
        long mergeTime;
        try {
            mergeTime = System.currentTimeMillis();
            // The lambda is set up to ignore anything that is a child of DEPLOYMENT_SAFE_NAMESPACE_TOPIC
            // Does not necessarily have to be a child of services, customers are free to put this namespace wherever
            // they like in the config
            ConfigurationReader.mergeTLogInto(kernel.getConfig(), getSnapshotFilePath(deploymentId), true,
                    s -> !s.childOf(EvergreenService.RUNTIME_STORE_NAMESPACE_TOPIC));
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
        kernel.getContext().runOnPublishQueue(() -> {
            // polling to wait for all services to be started.
            kernel.getContext().get(ExecutorService.class).execute(() -> {
                // TODO: Add timeout
                try {
                    servicesChangeManager.startNewServices();
                    servicesChangeManager.reinstallBrokenServices();

                    Set<EvergreenService> servicesToTrackForRollback = servicesChangeManager.servicesToTrack();

                    waitForServicesToStart(servicesToTrackForRollback, mergeTime);

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
     * @param mergeTime             time the merge was started, used to check if a service is broken due to the merge
     * @throws InterruptedException   if the thread is interrupted while waiting here
     * @throws ServiceUpdateException if a service could not be updated
     */
    public static void waitForServicesToStart(Set<EvergreenService> servicesToTrack, long mergeTime)
            throws InterruptedException, ServiceUpdateException {
        // Relying on the fact that all service lifecycle steps should have timeouts,
        // assuming this loop will not get stuck waiting forever
        while (true) {
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
        kernel.writeEffectiveConfigAsTransactionLog(getSnapshotFilePath(deploymentId));
    }

    /*
     * Clean up snapshot file
     */
    private void cleanUpSnapshot(String deploymentId) {
        try {
            Files.delete(getSnapshotFilePath(deploymentId));
        } catch (IOException e) {
            logger.atError().setEventType(MERGE_ERROR_LOG_EVENT_KEY).setCause(e)
                    .log("Error cleaning up kernel snapshot");
        }
    }

    /**
     * Resolve snapshot file path.
     *
     * @param deploymentId Deployment Identifier
     * @return Path to snapshot file
     */
    private Path getSnapshotFilePath(String deploymentId) {
        return kernel.getConfigPath().resolve(String.format(ROLLBACK_SNAPSHOT_PATH_FORMAT,
                deploymentId.replace(':', '.').replace('/', '+')));
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
                    .map(EvergreenService::getName).collect(Collectors.toSet());

            this.kernel = kernel;

            this.servicesToAdd =
                    newServiceConfig.keySet().stream().filter(serviceName -> !runningUserServices.contains(serviceName))
                            .collect(Collectors.toSet());

            this.servicesToUpdate =
                    newServiceConfig.keySet().stream().filter(runningUserServices::contains)
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
         * Used during rollback, ensures any services that were broken during the update are able to restart.
         *
         * @throws ServiceLoadException when any service to be started could not be located
         */
        public void reinstallBrokenServices() throws ServiceLoadException {
            for (String serviceName : servicesToUpdate) {
                EvergreenService service = kernel.locate(serviceName);
                if (service.currentOrReportedStateIs(State.BROKEN)) {
                    service.requestReinstall();
                }
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
            servicesToRemove = servicesToRemove.stream().filter(serviceName -> {
                try {
                    EvergreenService eg = kernel.locate(serviceName);

                    // If the service is an autostart service, then do not close it and do not
                    // remove it from the config
                    if (eg.isAutostart()) {
                        return false;
                    }

                    serviceClosedFutures.add(eg.close());
                } catch (ServiceLoadException e) {
                    logger.atError(MERGE_ERROR_LOG_EVENT_KEY).setCause(e).addKeyValue("serviceName", serviceName)
                            .log("Could not locate EvergreenService to close service");
                    // No need to handle the error when trying to stop a non-existing service.
                    return false;
                }
                return true;
            }).collect(Collectors.toSet());
            logger.atInfo(MERGE_CONFIG_EVENT_KEY).kv("service-to-remove", servicesToRemove).log("Removing services");
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
