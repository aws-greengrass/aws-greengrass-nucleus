/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment.activator;

import com.aws.iot.evergreen.deployment.DeploymentConfigMerger;
import com.aws.iot.evergreen.deployment.DynamicComponentConfigurationValidator;
import com.aws.iot.evergreen.deployment.exceptions.ServiceUpdateException;
import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentResult;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import javax.inject.Inject;

import static com.aws.iot.evergreen.deployment.DeploymentConfigMerger.DEPLOYMENT_ID_LOG_KEY;
import static com.aws.iot.evergreen.deployment.DeploymentConfigMerger.DEPLOYMENT_MERGE_BEHAVIOR;
import static com.aws.iot.evergreen.deployment.DeploymentConfigMerger.MERGE_CONFIG_EVENT_KEY;
import static com.aws.iot.evergreen.deployment.DeploymentConfigMerger.MERGE_ERROR_LOG_EVENT_KEY;
import static com.aws.iot.evergreen.deployment.DeploymentConfigMerger.waitForServicesToStart;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;

/**
 * Activation and rollback of default deployments.
 */
public class DefaultActivator extends DeploymentActivator {
    private DynamicComponentConfigurationValidator validator;

    @Inject
    public DefaultActivator(Kernel kernel, DynamicComponentConfigurationValidator validator) {
        super(kernel);
        this.validator = validator;
    }

    @Override
    public void activate(Map<Object, Object> newConfig, Deployment deployment,
                         CompletableFuture<DeploymentResult> totallyCompleteFuture) {
        Map<String, Object> serviceConfig;
        if (newConfig.containsKey(SERVICES_NAMESPACE_TOPIC)) {
            serviceConfig = (Map<String, Object>) newConfig.get(SERVICES_NAMESPACE_TOPIC);
        } else {
            serviceConfig = new HashMap<>();
        }

        // Ask all customer components who have signed up for dynamic component configuration changes
        // without restarting the component to validate their own proposed component configuration.
        if (!validator.validate(serviceConfig, deployment, totallyCompleteFuture)) {
            return;
        }

        DeploymentDocument deploymentDocument = deployment.getDeploymentDocumentObj();
        if (isAutoRollbackRequested(deploymentDocument) && !takeConfigSnapshot(totallyCompleteFuture)) {
            return;
        }

        String deploymentId = deploymentDocument.getDeploymentId();
        DeploymentConfigMerger.AggregateServicesChangeManager servicesChangeManager =
                new DeploymentConfigMerger.AggregateServicesChangeManager(kernel, serviceConfig);

        // Get the timestamp before mergeMap(). It will be used to check whether services have started.
        long mergeTime = System.currentTimeMillis();

        // when deployment adds a new dependency (component B) to component A
        // the config for component B has to be merged in before externalDependenciesTopic of component A trigger
        // executing mergeMap using publish thread ensures this
        kernel.getContext().runOnPublishQueueAndWait(() ->
                kernel.getConfig().updateMap(deploymentDocument.getTimestamp(), newConfig, DEPLOYMENT_MERGE_BEHAVIOR));

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
                    if (isAutoRollbackRequested(deploymentDocument)) {
                        rollback(deploymentDocument, totallyCompleteFuture, e,
                                servicesChangeManager.createRollbackManager());
                    } else {
                        totallyCompleteFuture.complete(new DeploymentResult(
                                DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_NOT_REQUESTED, e));
                    }
                }
            });
        });
    }

    void rollback(DeploymentDocument deploymentDocument, CompletableFuture<DeploymentResult> totallyCompleteFuture,
                  Throwable failureCause, DeploymentConfigMerger.AggregateServicesChangeManager rollbackManager) {
        String deploymentId = deploymentDocument.getDeploymentId();
        logger.atInfo(MERGE_CONFIG_EVENT_KEY).kv(DEPLOYMENT_ID_LOG_KEY, deploymentId)
                .log("Rolling back failed deployment");

        // Get the timestamp before merging snapshot. It will be used to check whether services have started.
        long mergeTime = rollbackConfig(deploymentId, totallyCompleteFuture, failureCause);
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
