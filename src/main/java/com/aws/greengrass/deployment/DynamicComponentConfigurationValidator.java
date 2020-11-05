/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.builtin.services.configstore.ConfigStoreIPCAgent;
import com.aws.greengrass.builtin.services.configstore.ConfigStoreIPCEventStreamAgent;
import com.aws.greengrass.builtin.services.configstore.exceptions.ValidateEventRegistrationException;
import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.exceptions.ComponentConfigurationValidationException;
import com.aws.greengrass.deployment.exceptions.InvalidConfigFormatException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.lifecyclemanager.GenericExternalService;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.aws.greengrass.model.ConfigurationValidityReport;
import software.amazon.awssdk.aws.greengrass.model.ConfigurationValidityStatus;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import javax.inject.Inject;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;

/**
 * Asks component processes over IPC to validate proposed component configuration a deployment brings.
 */
@AllArgsConstructor
@NoArgsConstructor
public class DynamicComponentConfigurationValidator {
    public static final String DEPLOYMENT_ID_LOG_KEY = "deploymentId";
    private static final long DEFAULT_TIMEOUT = Duration.ofSeconds(20).toMillis();
    private static final Logger logger = LogManager.getLogger(DynamicComponentConfigurationValidator.class);

    @Inject
    private Kernel kernel;

    @Inject
    private ConfigStoreIPCEventStreamAgent configStoreIPCEventStreamAgent;

    @Inject
    private ConfigStoreIPCAgent configStoreIPCAgent;

    /**
     * Dynamically validate proposed configuration for a deployment.
     *
     * @param servicesConfig         aggregate configuration map for services proposed by the deployment
     * @param deployment             deployment context
     * @param deploymentResultFuture deployment result future, completed with failure result when validation fails
     * @return if all component processes reported that their proposed configuration is valid
     */
    public boolean validate(Map<String, Object> servicesConfig, Deployment deployment,
                            CompletableFuture<DeploymentResult> deploymentResultFuture) {
        logger.addDefaultKeyValue(DEPLOYMENT_ID_LOG_KEY, deployment.getDeploymentDocumentObj().getDeploymentId());
        Set<ComponentToValidate> componentsToValidate;
        try {
            componentsToValidate =
                    getComponentsToValidate(servicesConfig, deployment.getDeploymentDocumentObj().getTimestamp());
        } catch (InvalidConfigFormatException e) {
            deploymentResultFuture.complete(
                    new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE,
                            new ComponentConfigurationValidationException(e)));
            return false;
        }

        return validateOverIpc(deployment.getId(), componentsToValidate, deploymentResultFuture);
    }

    /**
     * A component will not be asked to validate configuration if the deployment also intends to change its version.
     * This helps prevent failures when the configuration to be validated has new keys or schema that the running
     * version of the component doesn't understand and won't be able to validate. We rely on the fact that since the
     * component will restart on version change, its startup logic will handle any configuration usage.
     */
    private Set<ComponentToValidate> getComponentsToValidate(Map<String, Object> servicesConfig, long proposedTimestamp)
            throws InvalidConfigFormatException {
        Set<ComponentToValidate> componentsToValidate = new HashSet<>();

        for (Map.Entry<String, Object> serviceConfigEntry : servicesConfig.entrySet()) {
            String serviceName = serviceConfigEntry.getKey();
            Object serviceConfig = serviceConfigEntry.getValue();
            Topics currentServiceConfig;
            try {
                GreengrassService service = kernel.locate(serviceName);
                if (!(service instanceof GenericExternalService)) {
                    // No validation for internal services since currently all customer services are external
                    continue;
                }
                currentServiceConfig = service.getServiceConfig();
                if (currentServiceConfig == null) {
                    continue;
                }
            } catch (ServiceLoadException e) {
                // service not found, service is new
                continue;
            }
            if (!(serviceConfig instanceof Map)) {
                throw new InvalidConfigFormatException("Services config must be a map");
            }
            Map<String, Object> proposedServiceConfig = (Map) serviceConfig;

            if (!willChildTopicChange(proposedServiceConfig, currentServiceConfig, VERSION_CONFIG_KEY,
                    proposedTimestamp) && willChildTopicsChange(proposedServiceConfig, currentServiceConfig,
                    PARAMETERS_CONFIG_KEY, proposedTimestamp)) {
                componentsToValidate.add(new ComponentToValidate(serviceName,
                        (Map<String, Object>) proposedServiceConfig.get(PARAMETERS_CONFIG_KEY)));
            }
        }
        return componentsToValidate;
    }

    private boolean willChildTopicsChange(Map<String, Object> proposedServiceConfig, Topics currentServiceConfig,
                                          String key, long proposedTimestamp) throws InvalidConfigFormatException {
        Object proposed = proposedServiceConfig.get(key);
        Topics current = currentServiceConfig.findTopics(key);
        // If both are null then there is no change
        if (Objects.isNull(current) && Objects.isNull(proposed)) {
            return false;
        }
        // If proposed is non null it has to be a map
        if (Objects.nonNull(proposed) && !(proposed instanceof Map)) {
            throw new InvalidConfigFormatException("Config for " + key + " must be a map");
        }

        // By now we know at least one is non empty, so timestamps and values should be compared next
        return willNodeChange(proposed, current, proposedTimestamp);
    }

    private boolean willChildTopicChange(Map<String, Object> proposedServiceConfig, Topics currentServiceConfig,
                                         String key, long proposedTimestamp) {
        return willNodeChange(proposedServiceConfig.get(key), currentServiceConfig.findNode(key), proposedTimestamp);
    }

    private boolean willNodeChange(Object proposedConfig, Node currentConfig, long proposedTimestamp) {
        return Objects.isNull(currentConfig) ? Objects.nonNull(proposedConfig)
                : proposedTimestamp > currentConfig.getModtime() && !Objects
                        .deepEquals(proposedConfig, currentConfig.toPOJO());
    }

    private boolean validateOverIpc(String deploymentId, Set<ComponentToValidate> componentsToValidate,
                                    CompletableFuture<DeploymentResult> deploymentResultFuture) {
        try {
            String failureMsg = null;
            boolean validationRequested = false;
            boolean validationRequestedFromOldIpc = false;
            boolean valid = true;
            for (ComponentToValidate componentToValidate : componentsToValidate) {
                try {
                    if (configStoreIPCEventStreamAgent
                            .validateConfiguration(componentToValidate.componentName, deploymentId,
                                    componentToValidate.configuration,
                                    componentToValidate.response)) {
                        validationRequested = true;
                    }
                    if (configStoreIPCAgent
                            .validateConfiguration(componentToValidate.componentName, componentToValidate.configuration,
                                    componentToValidate.oldResponse)) {
                        validationRequestedFromOldIpc = true;
                    }
                    // Do nothing if service has not subscribed for validation
                } catch (ValidateEventRegistrationException e) {
                    validationRequested = false;
                    failureMsg = "Error requesting validation from component " + componentToValidate.componentName;
                    valid = false;
                    break;
                }
            }
            if (validationRequested) {
                try {
                    // TODO: [P41179329] Use configurable timeout from deployment document
                    CompletableFuture.allOf(componentsToValidate.stream().map(ComponentToValidate::getResponse)
                            .collect(Collectors.toSet()).toArray(new CompletableFuture[0]))
                            .get(DEFAULT_TIMEOUT, TimeUnit.MILLISECONDS);

                    failureMsg = "Components reported that their to-be-deployed configuration is invalid";
                    for (ComponentToValidate componentToValidate : componentsToValidate) {

                        // The aggregate future above has a timeout so at this point we will always have a report
                        // already received from all components otherwise the aggregate future would have failed,
                        // so we will no longer be blocked on any of the response futures
                        ConfigurationValidityReport report = componentToValidate.response.join();

                        if (ConfigurationValidityStatus.REJECTED.equals(report.getStatus())) {
                            failureMsg = String.format("%s { name = %s, message = %s }", failureMsg,
                                    componentToValidate.componentName, report.getMessage());
                            logger.atError().kv("component", componentToValidate.componentName)
                                    .kv("message", report.getMessage())
                                    .log("Component reported that its to-be-deployed configuration is invalid");
                            valid = false;
                        }
                    }
                } catch (InterruptedException | ExecutionException | TimeoutException | CancellationException
                        | CompletionException e) {
                    failureMsg =
                            "Error while waiting for validation report for one or more components:" + e.getMessage();
                    logger.atError().setCause(e).log(failureMsg);
                    valid = false;
                }
            }
            // GG_NEEDS_REVIEW: TODO: Remove when all UATs move to new IPC
            if (validationRequestedFromOldIpc) {
                try {
                    // TODO: [P41179329] Use configurable timeout from deployment document
                    CompletableFuture.allOf(componentsToValidate.stream().map(ComponentToValidate::getOldResponse)
                            .collect(Collectors.toSet()).toArray(new CompletableFuture[0]))
                            .get(DEFAULT_TIMEOUT, TimeUnit.MILLISECONDS);

                    failureMsg = "Components reported that their to-be-deployed configuration is invalid";
                    for (ComponentToValidate componentToValidate : componentsToValidate) {

                        // The aggregate future above has a timeout so at this point we will always have a report
                        // already received from all components otherwise the aggregate future would have failed,
                        // so we will no longer be blocked on any of the response futures
                        com.aws.greengrass.ipc.services.configstore.ConfigurationValidityReport oldReport =
                                componentToValidate.oldResponse.join();

                        if (com.aws.greengrass.ipc.services.configstore.ConfigurationValidityStatus.INVALID
                                .equals(oldReport.getStatus())) {
                            failureMsg = String.format("%s { name = %s, message = %s }", failureMsg,
                                    componentToValidate.componentName, oldReport.getMessage());
                            logger.atError().kv("component", componentToValidate.componentName)
                                    .kv("message", oldReport.getMessage())
                                    .log("Component reported that its to-be-deployed configuration is invalid");
                            valid = false;
                        }

                    }
                } catch (InterruptedException | ExecutionException | TimeoutException | CancellationException
                        | CompletionException e) {
                    failureMsg =
                            "Error while waiting for validation report for one or more components:" + e.getMessage();
                    logger.atError().setCause(e).log(failureMsg);
                    valid = false;
                }
            }
            //------------------Remove when all tests moved to new IPC-------------------
            if (!valid) {
                deploymentResultFuture.complete(
                        new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE,
                                new ComponentConfigurationValidationException(failureMsg)));
            }
            return valid;
        } finally {
            componentsToValidate.forEach(c -> {
                configStoreIPCEventStreamAgent.discardValidationReportTracker(deploymentId, c.componentName,
                        c.response);
                c.response.cancel(true);

                // GG_NEEDS_REVIEW: TODO: Remove when all tests moved to new IPC
                configStoreIPCAgent.discardValidationReportTracker(c.componentName, c.oldResponse);
                c.oldResponse.cancel(true);
                //------------------Remove when all tests moved to new IPC-------------------
            });
        }
    }

    @RequiredArgsConstructor
    @Getter
    @EqualsAndHashCode(onlyExplicitlyIncluded = true)
    private static final class ComponentToValidate {
        @EqualsAndHashCode.Include
        private final String componentName;
        private final Map<String, Object> configuration;
        private final CompletableFuture<ConfigurationValidityReport> response = new CompletableFuture<>();
        private final CompletableFuture<com.aws.greengrass.ipc.services.configstore.ConfigurationValidityReport>
                oldResponse = new CompletableFuture<>();
    }
}
