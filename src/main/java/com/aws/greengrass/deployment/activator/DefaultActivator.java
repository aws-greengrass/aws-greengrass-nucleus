/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.activator;

import com.aws.greengrass.deployment.DeploymentConfigMerger;
import com.aws.greengrass.deployment.DynamicComponentConfigurationValidator;
import com.aws.greengrass.deployment.exceptions.ServiceUpdateException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;

import static com.aws.greengrass.deployment.DeploymentConfigMerger.DEPLOYMENT_ID_LOG_KEY;
import static com.aws.greengrass.deployment.DeploymentConfigMerger.DEPLOYMENT_MERGE_BEHAVIOR;
import static com.aws.greengrass.deployment.DeploymentConfigMerger.MERGE_CONFIG_EVENT_KEY;
import static com.aws.greengrass.deployment.DeploymentConfigMerger.MERGE_ERROR_LOG_EVENT_KEY;
import static com.aws.greengrass.deployment.DeploymentConfigMerger.waitForServicesToStart;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;

/**
 * Activation and rollback of default deployments.
 */
public class DefaultActivator extends DeploymentActivator {
    private final DynamicComponentConfigurationValidator validator;

    @Inject
    public DefaultActivator(Kernel kernel, DynamicComponentConfigurationValidator validator) {
        super(kernel);
        this.validator = validator;
    }

    @Override
    public void activate(Map<String, Object> newConfig, Deployment deployment,
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
        kernel.getContext().runOnPublishQueueAndWait(() -> {});

        AtomicReference<Throwable> setDesiredStateFailureCause = new AtomicReference<>();
        kernel.getContext().runOnPublishQueue(() -> {
            // polling to wait for all services to be started.
            try {
                servicesChangeManager.startNewServices();
                // Restart any services that may have been broken before this deployment
                // This is added to allow deployments to fix broken services
                servicesChangeManager.reinstallBrokenServices();
            } catch (ServiceLoadException e) {
                setDesiredStateFailureCause.set(e);
            }
        });
        // Do not block the publish queue to handle failure in setting desired states for services
        if (setDesiredStateFailureCause.get() != null) {
            handleFailure(servicesChangeManager, deploymentDocument, totallyCompleteFuture,
                    setDesiredStateFailureCause.get());
        }

        try {
            Set<GreengrassService> servicesToTrack = servicesChangeManager.servicesToTrack();
            logger.atDebug(MERGE_CONFIG_EVENT_KEY).kv("serviceToTrack", servicesToTrack)
                    .log("Applied new service config. Waiting for services to complete update");
            waitForServicesToStart(servicesToTrack, mergeTime);
            logger.atDebug(MERGE_CONFIG_EVENT_KEY)
                    .log("new/updated services are running, will now remove old services");
            servicesChangeManager.removeObsoleteServices();
            logger.atInfo(MERGE_CONFIG_EVENT_KEY).kv(DEPLOYMENT_ID_LOG_KEY, deploymentId).log("All services updated");
            totallyCompleteFuture.complete(new DeploymentResult(DeploymentResult.DeploymentStatus.SUCCESSFUL, null));
        } catch (InterruptedException | ExecutionException | ServiceUpdateException | ServiceLoadException e) {
            handleFailure(servicesChangeManager, deploymentDocument, totallyCompleteFuture, e);
        }
    }

    private void handleFailure(DeploymentConfigMerger.AggregateServicesChangeManager servicesChangeManager,
                               DeploymentDocument deploymentDocument, CompletableFuture totallyCompleteFuture,
                               Throwable failureCause) {
        logger.atError(MERGE_CONFIG_EVENT_KEY).kv(DEPLOYMENT_ID_LOG_KEY, deploymentDocument.getDeploymentId())
                .setCause(failureCause).log("Deployment failed");
        if (isAutoRollbackRequested(deploymentDocument)) {
            rollback(deploymentDocument, totallyCompleteFuture, failureCause,
                    servicesChangeManager.createRollbackManager());
        } else {
            totallyCompleteFuture.complete(
                    new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_NOT_REQUESTED,
                            failureCause));
        }
    }

    void rollback(DeploymentDocument deploymentDocument, CompletableFuture<DeploymentResult> totallyCompleteFuture,
                  Throwable failureCause, DeploymentConfigMerger.AggregateServicesChangeManager rollbackManager) {
        String deploymentId = deploymentDocument.getDeploymentId();
        logger.atInfo(MERGE_CONFIG_EVENT_KEY).kv(DEPLOYMENT_ID_LOG_KEY, deploymentId)
                .log("Rolling back failed deployment");

        // Get the timestamp before merging snapshot. It will be used to check whether services have started.
        long mergeTime = rollbackConfig(totallyCompleteFuture, failureCause);
        if (mergeTime == -1) {
            return;
        }
        // wait until topic listeners finished processing read changes.
        kernel.getContext().runOnPublishQueueAndWait(() -> {});

        AtomicReference<Throwable> setDesiredStateFailureCause = new AtomicReference<>();
        kernel.getContext().runOnPublishQueue(() -> {
            // polling to wait for all services to be started.
            try {
                rollbackManager.startNewServices();
                rollbackManager.reinstallBrokenServices();
            } catch (ServiceLoadException e) {
                setDesiredStateFailureCause.set(e);
            }
        });
        // Do not block the publish queue to handle failure in setting desired states for services
        if (setDesiredStateFailureCause.get() != null) {
            handleFailureRollback(totallyCompleteFuture, failureCause, setDesiredStateFailureCause.get());
        }

        try {
            Set<GreengrassService> servicesToTrackForRollback = rollbackManager.servicesToTrack();
            logger.atDebug(MERGE_CONFIG_EVENT_KEY).kv("serviceToTrackForRollback", servicesToTrackForRollback)
                    .log("Applied rollback service config. Waiting for services to complete update");
            waitForServicesToStart(servicesToTrackForRollback, mergeTime);

            rollbackManager.removeObsoleteServices();
            logger.atInfo(MERGE_CONFIG_EVENT_KEY).kv(DEPLOYMENT_ID_LOG_KEY, deploymentId)
                    .log("All services rolled back");

            totallyCompleteFuture.complete(
                    new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_COMPLETE, failureCause));
        } catch (InterruptedException | ExecutionException | ServiceUpdateException | ServiceLoadException e) {
            handleFailureRollback(totallyCompleteFuture, failureCause, e);
        }
    }

    private void handleFailureRollback(CompletableFuture totallyCompleteFuture, Throwable deploymentFailureCause,
                                       Throwable rollbackFailureCause) {
        // Rollback execution failed
        logger.atError().setEventType(MERGE_ERROR_LOG_EVENT_KEY).setCause(rollbackFailureCause)
                .log("Failed to rollback deployment");
        // TODO : Run user provided script to reach user defined safe state and
        //  set deployment status based on the success of the script run
        totallyCompleteFuture.complete(new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK,
                deploymentFailureCause));
    }

}
