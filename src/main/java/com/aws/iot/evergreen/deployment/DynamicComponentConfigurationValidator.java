package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.builtin.services.configstore.ConfigStoreIPCAgent;
import com.aws.iot.evergreen.builtin.services.configstore.exceptions.ValidateEventRegistrationException;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.deployment.exceptions.DynamicConfigurationValidationException;
import com.aws.iot.evergreen.deployment.exceptions.InvalidConfigFormatException;
import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.deployment.model.DeploymentResult;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigurationValidityReport;
import com.aws.iot.evergreen.ipc.services.configstore.ConfigurationValidityStatus;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.GenericExternalService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import javax.inject.Inject;

import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.VERSION_CONFIG_KEY;

/**
 * Asks component processes over IPC to validate proposed component configuration a deployment brings.
 */
public class DynamicComponentConfigurationValidator {
    public static final String DEPLOYMENT_ID_LOG_KEY = "deploymentId";
    private static final long DEFAULT_TIMEOUT = Duration.ofSeconds(60).toMillis();
    private static final Logger logger = LogManager.getLogger(DeploymentConfigMerger.class);

    @Inject
    private Kernel kernel;

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
                            CompletableFuture deploymentResultFuture) {
        logger.addDefaultKeyValue(DEPLOYMENT_ID_LOG_KEY, deployment.getDeploymentDocumentObj().getDeploymentId());
        Set<ComponentToValidate> componentsToValidate;
        try {
            componentsToValidate =
                    getComponentsToValidate(servicesConfig, deployment.getDeploymentDocumentObj().getTimestamp());
        } catch (InvalidConfigFormatException e) {
            deploymentResultFuture.complete(
                    new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE,
                            new DynamicConfigurationValidationException(e)));
            return false;
        }

        return validateOverIpc(componentsToValidate, deploymentResultFuture);
    }

    /**
     * A component will not be asked to validate configuration if the deployment also intends to change its
     * version. This helps prevent failures when the configuration to be validated has new keys or schema
     * that the running version of the component doesn't understand and won't be able to validate. We rely
     * on the fact that since the component will restart on version change, its startup logic will handle
     * any configuration usage.
     */
    private Set<ComponentToValidate> getComponentsToValidate(Map<String, Object> servicesConfig, long proposedTimestamp)
            throws InvalidConfigFormatException {
        Set<ComponentToValidate> componentsToValidate = new HashSet<>();

        for (String serviceName : servicesConfig.keySet()) {
            Topics currentServiceConfig;
            try {
                EvergreenService service = kernel.locate(serviceName);
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
            if (!(servicesConfig.get(serviceName) instanceof Map)) {
                throw new InvalidConfigFormatException("Services config must be a map");
            }
            Map<String, Object> proposedServiceConfig = (Map) servicesConfig.get(serviceName);

            // TODO: Check recipe flag for if service can handle dynamic configuration if not, it'll be restarted
            //  since it's likely if services can't handle dynamic config they are not IPC aware at all
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
        if (proposed instanceof Map) {
            return willTopicsChange((Map<String, Object>) proposed, current, proposedTimestamp);
        }
        throw new InvalidConfigFormatException("Config for " + key + " must be a map");
    }

    private boolean willChildTopicChange(Map<String, Object> proposedServiceConfig, Topics currentServiceConfig,
                                         String key, long proposedTimestamp) {
        return willTopicChange(proposedServiceConfig.get(key), currentServiceConfig.find(key), proposedTimestamp);
    }

    private boolean willTopicsChange(Map<String, Object> proposedConfig, Topics currentConfig, long proposedTimestamp) {
       return proposedTimestamp > currentConfig.getModtime() && !Objects.deepEquals(proposedConfig, currentConfig);
    }

    private boolean willTopicChange(Object proposedConfig, Topic currentConfig, long proposedTimestamp) {
        return proposedTimestamp > currentConfig.getModtime() && !currentConfig.toPOJO().equals(proposedConfig);
    }

    private boolean validateOverIpc(Set<ComponentToValidate> componentsToValidate,
                                    CompletableFuture deploymentResultFuture) {
        try {
            String failureMsg = "Components reported that their to-be-deployed configuration is invalid";
            boolean validationRequested = true;
            boolean valid = true;
            for (ComponentToValidate componentToValidate : componentsToValidate) {
                try {
                    validationRequested = configStoreIPCAgent
                            .validateConfiguration(componentToValidate.componentName, componentToValidate.configuration,
                                    componentToValidate.response);
                    if (!validationRequested) {
                        failureMsg = "Error requesting validation from component" + componentToValidate.componentName;
                        valid = false;
                        break;
                    }
                } catch (ValidateEventRegistrationException e) {
                    validationRequested = false;
                    failureMsg = "Error requesting validation from component" + componentToValidate.componentName;
                    valid = false;
                    break;
                }
            }
            if (validationRequested) {
                try {
                    // TODO : Use configurable timeout from deployment document
                    CompletableFuture.allOf(componentsToValidate.stream().map(ComponentToValidate::getResponse)
                            .collect(Collectors.toSet()).toArray(new CompletableFuture[0]))
                            .get(DEFAULT_TIMEOUT, TimeUnit.MILLISECONDS);
                    for (ComponentToValidate componentToValidate : componentsToValidate) {
                        ConfigurationValidityReport report = componentToValidate.response.join();
                        if (ConfigurationValidityStatus.INVALID.equals(report.getStatus())) {
                            failureMsg = String.format("%s { name = %s, message = %s }", failureMsg,
                                    componentToValidate.componentName, report.getMessage());
                            logger.atError().kv("component", componentToValidate.componentName)
                                    .kv("message", report.getMessage())
                                    .log("Component reported that its to-be-deployed configuration is invalid");
                            valid = false;
                        }
                    }
                } catch (InterruptedException | ExecutionException | TimeoutException e) {
                    failureMsg =
                            "Error while waiting for validation report for one or more components:" + e.getMessage();
                    logger.atError().setCause(e).log(failureMsg);
                    valid = false;
                }
            }
            if (!valid) {
                deploymentResultFuture.complete(
                        new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE,
                                new DynamicConfigurationValidationException(failureMsg)));
            }
            return valid;
        } finally {
            componentsToValidate
                    .forEach(c -> configStoreIPCAgent.discardValidationReportTracker(c.componentName, c.response));
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
    }
}
