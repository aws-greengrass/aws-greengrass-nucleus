/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;


import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.activator.DeploymentActivator;
import com.aws.greengrass.deployment.activator.DeploymentActivatorFactory;
import com.aws.greengrass.deployment.activator.KernelUpdateActivator;
import com.aws.greengrass.deployment.exceptions.ComponentConfigurationValidationException;
import com.aws.greengrass.deployment.exceptions.ServiceUpdateException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.UpdateAction;
import com.aws.greengrass.lifecyclemanager.UpdateSystemPolicyService;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import software.amazon.awssdk.services.greengrassv2.model.DeploymentComponentUpdatePolicyAction;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import javax.inject.Inject;

import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_AWS_REGION;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_IOT_CRED_ENDPOINT;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_IOT_DATA_ENDPOINT;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_NAME_KEY;

@AllArgsConstructor(onConstructor = @__(@Inject))
public class DeploymentConfigMerger {
    public static final String MERGE_CONFIG_EVENT_KEY = "merge-config";
    public static final String MERGE_ERROR_LOG_EVENT_KEY = "config-update-error";
    public static final String DEPLOYMENT_ID_LOG_KEY = "deploymentId";
    protected static final int WAIT_SVC_START_POLL_INTERVAL_MILLISEC = 1000;

    private static final Logger logger = LogManager.getLogger(DeploymentConfigMerger.class);

    private Kernel kernel;
    private DeviceConfiguration deviceConfiguration;
    private DynamicComponentConfigurationValidator validator;

    /**
     * Merge in new configuration values and new services.
     *
     * @param deployment deployment object
     * @param newConfig  the map of new configuration
     * @return future which completes only once the config is merged and all the services in the config are running
     */
    public Future<DeploymentResult> mergeInNewConfig(Deployment deployment,
                                                     Map<String, Object> newConfig) {
        CompletableFuture<DeploymentResult> totallyCompleteFuture = new CompletableFuture<>();
        DeploymentActivator activator;
        try {
            activator = kernel.getContext().get(DeploymentActivatorFactory.class).getDeploymentActivator(newConfig);
        } catch (ServiceUpdateException | ComponentConfigurationValidationException e) {
            // Failed to pre-process new config, no rollback needed
            logger.atError().setEventType(MERGE_ERROR_LOG_EVENT_KEY).setCause(e)
                    .log("Failed to process new configuration for activation");
            totallyCompleteFuture
                    .complete(new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, e));
            return totallyCompleteFuture;
        }

        boolean ggcRestart = false;
        if (activator instanceof KernelUpdateActivator) {
            ggcRestart = true;
        }

        DeploymentDocument deploymentDocument = deployment.getDeploymentDocumentObj();
        if (DeploymentComponentUpdatePolicyAction.NOTIFY_COMPONENTS
                .equals(deploymentDocument.getComponentUpdatePolicy().getComponentUpdatePolicyAction())) {
            kernel.getContext().get(UpdateSystemPolicyService.class)
                    .addUpdateAction(deploymentDocument.getDeploymentId(),
                            new UpdateAction(deploymentDocument.getDeploymentId(),
                                    ggcRestart, deploymentDocument.getComponentUpdatePolicy().getTimeout(),
                                    () -> updateActionForDeployment(newConfig, deployment, activator,
                                            totallyCompleteFuture)));
        } else {
            logger.atInfo().log("Deployment is configured to skip update policy check,"
                    + " not waiting for disruptable time to update");
            updateActionForDeployment(newConfig, deployment, activator, totallyCompleteFuture);
        }

        return totallyCompleteFuture;
    }

    private void updateActionForDeployment(Map<String, Object> newConfig, Deployment deployment,
                                           DeploymentActivator activator,
                                           CompletableFuture<DeploymentResult> totallyCompleteFuture) {
        String deploymentId = deployment.getDeploymentDocumentObj().getDeploymentId();

        // if the update is cancelled, don't perform merge
        if (totallyCompleteFuture.isCancelled()) {
            logger.atInfo(MERGE_CONFIG_EVENT_KEY).kv("deployment", deploymentId)
                    .log("Future was cancelled so no need to go through with the update");
            return;
        }
        Map<String, Object> serviceConfig;
        Map<String, Object> kernelConfig = null;
        if (newConfig.containsKey(SERVICES_NAMESPACE_TOPIC)) {
            serviceConfig = (Map<String, Object>) newConfig.get(SERVICES_NAMESPACE_TOPIC);
            if (serviceConfig.containsKey(deviceConfiguration.getNucleusComponentName())) {
                kernelConfig = (Map<String, Object>) serviceConfig.get(deviceConfiguration.getNucleusComponentName());
            }
        } else {
            serviceConfig = new HashMap<>();
        }

        // Ask all customer components who have signed up for dynamic component configuration changes
        // without restarting the component to validate their own proposed component configuration.
        if (!validator.validate(serviceConfig, deployment, totallyCompleteFuture)) {
            return;
        }

        if (kernelConfig != null) {
            String awsRegion = tryGetAwsRegionFromNewConfig(kernelConfig);
            String iotCredEndpoint = tryGetIoTCredEndpointFromNewConfig(kernelConfig);
            String iotDataEndpoint = tryGetIoTDataEndpointFromNewConfig(kernelConfig);
            try {
                deviceConfiguration.validateEndpoints(awsRegion, iotCredEndpoint, iotDataEndpoint);
            } catch (ComponentConfigurationValidationException e) {
                logger.atError().cause(e).log("Error validating IoT endpoints");
                totallyCompleteFuture
                        .complete(new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, e));
                return;
            }
        }

        logger.atInfo(MERGE_CONFIG_EVENT_KEY).kv("deployment", deploymentId)
                .log("Applying deployment changes, deployment cannot be cancelled now");
        activator.activate(newConfig, deployment, totallyCompleteFuture);
    }

    /**
     * Completes the provided future when all of the listed services are running.
     *
     * @param servicesToTrack       services to track
     * @param mergeTime             time the merge was started, used to check if a service is broken due to the merge
     * @throws InterruptedException   if the thread is interrupted while waiting here
     * @throws ServiceUpdateException if a service could not be updated
     */
    public static void waitForServicesToStart(Collection<GreengrassService> servicesToTrack, long mergeTime)
            throws InterruptedException, ServiceUpdateException {
        // Relying on the fact that all service lifecycle steps should have timeouts,
        // assuming this loop will not get stuck waiting forever
        while (true) {
            boolean allServicesRunning = true;
            for (GreengrassService service : servicesToTrack) {
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
                    continue;
                }
                if (State.RUNNING.equals(state) || State.FINISHED.equals(state) || !service.shouldAutoStart()
                        && service.reachedDesiredState()) {
                    continue;
                }
                allServicesRunning = false;
            }
            if (allServicesRunning) {
                return;
            }
            Thread.sleep(WAIT_SVC_START_POLL_INTERVAL_MILLISEC); // hardcoded
        }
    }

    private String tryGetAwsRegionFromNewConfig(Map<String, Object> kernelConfig) {
        String awsRegion = Coerce.toString(deviceConfiguration.getAWSRegion());
        if (kernelConfig.containsKey(DEVICE_PARAM_AWS_REGION)) {
            awsRegion = Coerce.toString(kernelConfig.get(DEVICE_PARAM_AWS_REGION));
        }
        return awsRegion;
    }

    private String tryGetIoTCredEndpointFromNewConfig(Map<String, Object> kernelConfig) {
        String iotCredEndpoint = Coerce.toString(deviceConfiguration.getIotCredentialEndpoint());
        if (kernelConfig.containsKey(DEVICE_PARAM_IOT_CRED_ENDPOINT)) {
            iotCredEndpoint = Coerce.toString(kernelConfig.get(DEVICE_PARAM_IOT_CRED_ENDPOINT));
        }
        return iotCredEndpoint;
    }

    private String tryGetIoTDataEndpointFromNewConfig(Map<String, Object> kernelConfig) {
        String iotDataEndpoint = Coerce.toString(deviceConfiguration.getIotDataEndpoint());
        if (kernelConfig.containsKey(DEVICE_PARAM_IOT_DATA_ENDPOINT)) {
            iotDataEndpoint = Coerce.toString(kernelConfig.get(DEVICE_PARAM_IOT_DATA_ENDPOINT));
        }
        return iotDataEndpoint;
    }


    @Getter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class AggregateServicesChangeManager {
        private Kernel kernel;
        private Set<String> servicesToAdd;
        private Set<String> servicesToUpdate;
        private Set<String> servicesToRemove;
        private Set<String> alreadyBrokenServices;

        /**
         * Constructs an object based on the current Kernel state and the config to be merged.
         *
         * @param kernel           Greengrass kernel
         * @param newServiceConfig new config to be merged for deployment
         */
        public AggregateServicesChangeManager(Kernel kernel, Map<String, Object> newServiceConfig) {
            // No builtin services should be modified in any way by deployments outside of
            //  Nucleus component update
            Set<String> runningDeployableServices =
                    kernel.orderedDependencies().stream().filter(s -> !s.isBuiltin()).map(GreengrassService::getName)
                            .collect(Collectors.toSet());

            this.kernel = kernel;

            this.servicesToAdd = newServiceConfig.keySet().stream()
                    .filter(serviceName -> !runningDeployableServices.contains(serviceName))
                    .collect(Collectors.toSet());

            this.servicesToUpdate = newServiceConfig.keySet().stream().filter(runningDeployableServices::contains)
                    .collect(Collectors.toSet());

            this.servicesToRemove =
                    runningDeployableServices.stream().filter(serviceName -> !newServiceConfig.containsKey(serviceName))
                            .collect(Collectors.toSet());
            this.alreadyBrokenServices = runningDeployableServices.stream().filter(name -> {
                try {
                    return kernel.locate(name).currentOrReportedStateIs(State.BROKEN);
                } catch (ServiceLoadException e) {
                    return false;
                }
            }).collect(Collectors.toSet());
        }

        /**
         * Get the service change manager that can be used for rolling back the current merge.
         *
         * @return An instance of service change manager to be used for rollback
         */
        public AggregateServicesChangeManager createRollbackManager() {
            // For rollback, services the deployment originally intended to add should be removed
            // and services it intended to remove should be added back
            return new AggregateServicesChangeManager(kernel, servicesToRemove, servicesToUpdate, servicesToAdd,
                    alreadyBrokenServices);
        }

        /**
         * Start the new services the merge intends to add.
         *
         * @throws ServiceLoadException when any service to be started could not be located
         */
        public void startNewServices() throws ServiceLoadException {
            for (String serviceName : servicesToAdd) {
                GreengrassService service = kernel.locate(serviceName);
                if (service.shouldAutoStart()) {
                    service.requestStart();
                }
            }
        }

        /**
         * Used during rollback, ensures any services that were broken during the update are able to restart.
         *
         * @throws ServiceLoadException when any service to be started could not be located
         */
        public void reinstallBrokenServices() throws ServiceLoadException {
            for (String serviceName : servicesToUpdate) {
                GreengrassService service = kernel.locate(serviceName);
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
                    GreengrassService eg = kernel.locate(serviceName);

                    // If the service is builtin, then do not close it and do not
                    // remove it from the config
                    if (eg.isBuiltin()) {
                        return false;
                    }

                    serviceClosedFutures.add(eg.close());
                } catch (ServiceLoadException e) {
                    logger.atError(MERGE_ERROR_LOG_EVENT_KEY).setCause(e).addKeyValue("serviceName", serviceName)
                            .log("Could not locate Greengrass service to close service");
                    // Even though we couldn't find it, we might still need to drop it from the context, so return true
                    return true;
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
                Topics serviceTopic = kernel.findServiceTopic(serviceName);
                if (serviceTopic == null) {
                    logger.atWarn().kv(SERVICE_NAME_KEY, serviceName).log("Service topics node doesn't exist.");
                    return;
                }
                serviceTopic.remove();
            });
        }

        /**
         * Get the set of services whose state change needs to be tracked to assess the outcome of the merge.
         *
         * @return set of services to track state change for
         * @throws ServiceLoadException when any service whose state change needs to be tracked cannot be located
         */
        public Set<GreengrassService> servicesToTrack() throws ServiceLoadException {
            Set<String> serviceNames = new HashSet<>(servicesToAdd);
            serviceNames.addAll(servicesToUpdate);

            Set<GreengrassService> servicesToTrack = new HashSet<>();
            for (String serviceName : serviceNames) {
                GreengrassService eg = kernel.locate(serviceName);
                servicesToTrack.add(eg);
            }
            servicesToTrack.remove(kernel.getMain());
            return servicesToTrack;
        }
    }
}
