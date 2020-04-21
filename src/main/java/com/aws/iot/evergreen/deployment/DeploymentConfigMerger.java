/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.exceptions.ServiceUpdateException;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.GenericExternalService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.UpdateSystemSafelyService;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import lombok.AllArgsConstructor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
    public static final String MERGE_CONFIG_EVENT_KEY = "merge-config";

    private static final Logger logger = LogManager.getLogger(DeploymentConfigMerger.class);

    @Inject
    private Kernel kernel;

    /**
     * Merge in new configuration values and new services.
     *
     * @param deploymentId give an ID to the task to run
     * @param timestamp    timestamp for all configuration values to use when merging (newer timestamps win)
     * @param newConfig    the map of new configuration
     * @return future which completes only once the config is merged and all the services in the config are running
     */
    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    public Future<Void> mergeInNewConfig(String deploymentId, long timestamp, Map<Object, Object> newConfig) {
        CompletableFuture<Void> totallyCompleteFuture = new CompletableFuture<>();

        if (newConfig.get(SERVICES_NAMESPACE_TOPIC) == null) {
            kernel.getConfig().mergeMap(timestamp, newConfig);
            totallyCompleteFuture.complete(null);
            return totallyCompleteFuture;
        }

        Map<String, Object> serviceConfig = (Map<String, Object>) newConfig.get(SERVICES_NAMESPACE_TOPIC);
        List<String> removedServices = getRemovedServicesNames(serviceConfig);
        logger.atDebug(MERGE_CONFIG_EVENT_KEY).kv("removedServices", removedServices).log();

        Set<EvergreenService> servicesToTrack = new HashSet<>();
        kernel.getContext().get(UpdateSystemSafelyService.class).addUpdateAction(deploymentId, () -> {
            try {
                // Get the timestamp before mergeMap(). It will be used to check whether services have started.
                long mergeTime = System.currentTimeMillis();

                kernel.getConfig().mergeMap(timestamp, newConfig);
                for (String serviceName : serviceConfig.keySet()) {
                    EvergreenService eg = kernel.locate(serviceName);
                    if (State.NEW.equals(eg.getState())) {
                        eg.requestStart();
                    }
                    servicesToTrack.add(eg);
                }

                // wait until topic listeners finished processing mergeMap changes.
                kernel.getContext().runOnPublishQueueAndWait(() -> {
                    logger.atInfo(MERGE_CONFIG_EVENT_KEY)
                            .kv("serviceToTrack", servicesToTrack)
                            .log("applied new service config. Waiting for services to complete update");

                    // polling to wait for all services started.
                    kernel.getContext().get(ExecutorService.class).execute(() -> {
                        //TODO: Add timeout
                        try {
                            waitForServicesToStart(servicesToTrack, totallyCompleteFuture, mergeTime);
                            if (totallyCompleteFuture.isCompletedExceptionally()) {
                                return;
                            }
                            if (totallyCompleteFuture.isCancelled()) {
                                logger.atWarn(MERGE_CONFIG_EVENT_KEY).kv("deploymentId", deploymentId)
                                        .log("merge-config-cancelled");
                                return;
                            }

                            removeServices(removedServices);
                            logger.atInfo(MERGE_CONFIG_EVENT_KEY).kv("deploymentId", deploymentId)
                                    .log("All services updated");

                            totallyCompleteFuture.complete(null);
                        } catch (Throwable t) {
                            //TODO: handle different throwables. Revert changes if applicable.
                            totallyCompleteFuture.completeExceptionally(t);
                        }
                    });
                });
            } catch (Throwable e) {
                totallyCompleteFuture.completeExceptionally(e);
            }
        });

        return totallyCompleteFuture;
    }

    /**
     * Completes the provided future when all of the listed services are running.
     *
     * @param servicesToTrack service to track
     * @param totallyCompleteFuture future to complete
     * @param mergeTime time that the merge was started, used to check if a service is broken due to the merge
     * @throws InterruptedException if the thread is interrupted while waiting here
     */
    public static void waitForServicesToStart(Set<EvergreenService> servicesToTrack,
                                          CompletableFuture<?> totallyCompleteFuture,
                                          long mergeTime) throws InterruptedException {
        while (!totallyCompleteFuture.isCancelled()) {
            boolean allServicesRunning = true;
            for (EvergreenService service : servicesToTrack) {
                State state = service.getState();

                // If a service is previously BROKEN, its state might have not been updated yet when this check
                // executes. Therefore we first check the service state has been updated since merge map occurs.
                if (service.getStateModTime() > mergeTime && State.BROKEN.equals(state)) {
                    logger.atWarn(MERGE_CONFIG_EVENT_KEY).kv("serviceName", service.getName())
                            .log("merge-config-service BROKEN");
                    totallyCompleteFuture.completeExceptionally(new ServiceUpdateException(
                            String.format("Service %s in broken state after deployment", service.getName())));
                    return;
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

    private void removeServices(List<String> serviceToRemove) throws InterruptedException, ExecutionException {
        List<Future<Void>> serviceClosedFutures = new ArrayList<>();
        serviceToRemove.forEach(serviceName -> {
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
        serviceToRemove.forEach(serviceName -> {
            kernel.getContext().remove(serviceName);
            kernel.findServiceTopic(serviceName).remove();
        });
    }

    //TODO: handle removing services that are running within in the JVM but defined via config
    private List<String> getRemovedServicesNames(Map<String, Object> serviceConfig) {
        return kernel.orderedDependencies().stream()
                .filter(evergreenService -> evergreenService instanceof GenericExternalService)
                .map(EvergreenService::getName).filter(serviceName -> !serviceConfig.containsKey(serviceName))
                .collect(Collectors.toList());

    }
}
