/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.activator;

import com.aws.greengrass.deployment.DeploymentConfigMerger;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
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
import javax.inject.Inject;

import static com.aws.greengrass.deployment.DeploymentConfigMerger.DEPLOYMENT_ID_LOG_KEY;
import static com.aws.greengrass.deployment.DeploymentConfigMerger.MERGE_CONFIG_EVENT_KEY;
import static com.aws.greengrass.deployment.DeploymentConfigMerger.MERGE_ERROR_LOG_EVENT_KEY;
import static com.aws.greengrass.deployment.DeploymentConfigMerger.waitForServicesToStart;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;

/**
 * Activation and rollback of default deployments.
 */
public class DefaultActivator extends DeploymentActivator {

    @Inject
    public DefaultActivator(Kernel kernel, DeviceConfiguration deviceConfiguration) {
        super(kernel, deviceConfiguration);
    }

    @Override
    @SuppressWarnings("PMD.PrematureDeclaration")
    public void activate(Map<String, Object> newConfig, Deployment deployment,
                         CompletableFuture<DeploymentResult> totallyCompleteFuture) {
        Map<String, Object> serviceConfig;
        Map<String, Object> kernelConfig = null;
        if (newConfig.containsKey(SERVICES_NAMESPACE_TOPIC)) {
            serviceConfig = (Map<String, Object>) newConfig.get(SERVICES_NAMESPACE_TOPIC);
            if (serviceConfig.containsKey(deviceConfiguration.getNucleusComponentName())) {
                kernelConfig = (Map<String, Object>) serviceConfig.get(DEFAULT_NUCLEUS_COMPONENT_NAME);
            }
        } else {
            serviceConfig = new HashMap<>();
        }

        DeploymentDocument deploymentDocument = deployment.getDeploymentDocumentObj();
        if (isAutoRollbackRequested(deploymentDocument) && !takeConfigSnapshot(totallyCompleteFuture)) {
            return;
        }

        DeploymentConfigMerger.AggregateServicesChangeManager servicesChangeManager =
                new DeploymentConfigMerger.AggregateServicesChangeManager(kernel, serviceConfig);

        // Get the timestamp before updateMap(). It will be used to check whether services have started.
        long mergeTime = System.currentTimeMillis();

        if (kernelConfig != null) {
            String awsRegion = tryGetAwsRegionFromNewConfig(kernelConfig);
            String iotCredEndpoint = tryGetIoTCredEndpointFromNewConfig(kernelConfig);
            String iotDataEndpoint = tryGetIoTDataEndpointFromNewConfig(kernelConfig);
            try {
                deviceConfiguration.validateEndpoints(awsRegion, iotCredEndpoint, iotDataEndpoint);
            } catch (DeviceConfigurationException e) {
                handleFailure(servicesChangeManager, deploymentDocument, totallyCompleteFuture, e);
                return;
            }
        }

        updateConfiguration(deploymentDocument.getTimestamp(), newConfig);

        // wait until topic listeners finished processing mergeMap changes.
        Throwable setDesiredStateFailureCause = kernel.getContext().runOnPublishQueueAndWait(() -> {
            // polling to wait for all services to be started.
            servicesChangeManager.startNewServices();
            // Restart any services that may have been broken before this deployment
            // This is added to allow deployments to fix broken services
            servicesChangeManager.reinstallBrokenServices();
        });
        if (setDesiredStateFailureCause != null) {
            handleFailure(servicesChangeManager, deploymentDocument, totallyCompleteFuture,
                    setDesiredStateFailureCause);
            return;
        }

        try {
            Set<GreengrassService> servicesToTrack = servicesChangeManager.servicesToTrack();
            logger.atDebug(MERGE_CONFIG_EVENT_KEY).kv("serviceToTrack", servicesToTrack).kv("mergeTime", mergeTime)
                    .log("Applied new service config. Waiting for services to complete update");
            waitForServicesToStart(servicesToTrack, mergeTime);
            logger.atDebug(MERGE_CONFIG_EVENT_KEY)
                    .log("new/updated services are running, will now remove old services");
            servicesChangeManager.removeObsoleteServices();
            logger.atInfo(MERGE_CONFIG_EVENT_KEY).kv(DEPLOYMENT_ID_LOG_KEY, deploymentDocument.getDeploymentId())
                    .log("All services updated");
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
        Throwable setDesiredStateFailureCause = kernel.getContext().runOnPublishQueueAndWait(() -> {
                rollbackManager.startNewServices();
                rollbackManager.reinstallBrokenServices();
        });
        if (setDesiredStateFailureCause != null) {
            handleFailureRollback(totallyCompleteFuture, failureCause, setDesiredStateFailureCause);
            return;
        }

        try {
            Set<GreengrassService> servicesToTrackForRollback = rollbackManager.servicesToTrack();
            // Don't track services if they were already broken before the rolled back deployment, because they'd
            // be expected to still be broken
            servicesToTrackForRollback.removeIf((s) ->
                    rollbackManager.getAlreadyBrokenServices().contains(s.getName()));
            logger.atDebug(MERGE_CONFIG_EVENT_KEY)
                    .kv("previouslyBrokenServices", rollbackManager.getAlreadyBrokenServices())
                    .kv("serviceToTrackForRollback", servicesToTrackForRollback)
                    .kv("mergeTime", mergeTime)
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
        totallyCompleteFuture.complete(new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK,
                deploymentFailureCause));
    }

}
