/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.bootstrap;

import com.amazon.aws.iot.greengrass.component.common.ComponentType;
import com.aws.greengrass.componentmanager.KernelConfigResolver;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCodeUtils;
import com.aws.greengrass.deployment.exceptions.ComponentConfigurationValidationException;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.deployment.exceptions.ServiceUpdateException;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.PluginService;
import com.aws.greengrass.lifecyclemanager.exceptions.InputValidationException;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.mqttclient.spool.SpoolerStorageType;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.CommitableReader;
import com.aws.greengrass.util.CommitableWriter;
import com.aws.greengrass.util.DependencyOrder;
import com.aws.greengrass.util.SerializerFactory;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.Platform;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import javax.annotation.concurrent.NotThreadSafe;
import javax.inject.Inject;

import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_MQTT_NAMESPACE;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_NETWORK_PROXY_NAMESPACE;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_NO_PROXY_ADDRESSES;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_PROXY_PASSWORD;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_PROXY_URL;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_PROXY_USERNAME;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PROXY_NAMESPACE;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_SPOOLER_NAMESPACE;
import static com.aws.greengrass.deployment.DeviceConfiguration.RUN_WITH_DEFAULT_POSIX_SHELL;
import static com.aws.greengrass.deployment.DeviceConfiguration.RUN_WITH_DEFAULT_POSIX_SHELL_VALUE;
import static com.aws.greengrass.deployment.DeviceConfiguration.RUN_WITH_DEFAULT_POSIX_USER;
import static com.aws.greengrass.deployment.DeviceConfiguration.RUN_WITH_TOPIC;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.NO_OP;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_REBOOT;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;
import static com.aws.greengrass.deployment.bootstrap.BootstrapTaskStatus.ExecutionStatus.DONE;
import static com.aws.greengrass.lifecyclemanager.GenericExternalService.serviceLifecycleDefined;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.Kernel.SERVICE_TYPE_TOPIC_KEY;
import static com.aws.greengrass.lifecyclemanager.Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC;
import static com.aws.greengrass.mqttclient.MqttClient.DEFAULT_MQTT_VERSION;
import static com.aws.greengrass.mqttclient.spool.Spool.DEFAULT_SPOOL_STORAGE_TYPE;
import static com.aws.greengrass.mqttclient.spool.Spool.SPOOL_STORAGE_TYPE_KEY;

/**
 * Generates a list of bootstrap tasks from deployments, manages the execution and persists status.
 */
@NotThreadSafe
public class BootstrapManager implements Iterator<BootstrapTaskStatus>  {
    private static final String COMPONENT_NAME_LOG_KEY_NAME = "componentName";
    private static final String RESTART_REQUIRED_MESSAGE = "Restart required due to configuration change";

    private static final Logger logger = LogManager.getLogger(BootstrapManager.class);
    @Setter(AccessLevel.PACKAGE)
    @Getter(AccessLevel.PACKAGE)
    private List<BootstrapTaskStatus> bootstrapTaskStatusList = new ArrayList<>();
    private final Kernel kernel;
    private final Platform platform;
    private int cursor;

    /**
     * Constructor for BootstrapManager to process bootstrap tasks from deployments.
     *
     * @param kernel Kernel instance
     */
    @Inject
    public BootstrapManager(Kernel kernel) {
        this(kernel, Platform.getInstance());
    }

    BootstrapManager(Kernel kernel, Platform platform) {
        this.kernel = kernel;
        this.cursor = 0;
        this.platform = platform;
    }

    /**
     * Check if any bootstrap tasks are pending based on new configuration. Meanwhile resolve a list of bootstrap
     * tasks.
     *
     * @param newConfig new configuration from deployment
     * @return true if there are bootstrap tasks, false otherwise
     * @throws ServiceUpdateException                    if parsing bootstrap tasks from new configuration fails
     * @throws ComponentConfigurationValidationException If changed nucleus component configuration is invalid
     */
    @SuppressWarnings("PMD.PrematureDeclaration")
    public boolean isBootstrapRequired(Map<String, Object> newConfig)
            throws ServiceUpdateException, ComponentConfigurationValidationException {
        bootstrapTaskStatusList.clear();
        cursor = 0;

        if (newConfig == null || !newConfig.containsKey(SERVICES_NAMESPACE_TOPIC)) {
            logger.atInfo().log(
                    "No bootstrap tasks found: Deployment configuration is missing or has no service changes");
            return false;
        }

        // Validate nucleus config early, then proceed with evaluating bootstrap tasks
        final boolean nucleusConfigValidAndNeedsRestart = nucleusConfigValidAndNeedsRestart(newConfig);

        // Compare newConfig with kernel and find out all that changed
        Set<String> componentsRequiresBootstrapTask = new HashSet<>();
        Map<String, Object> serviceConfig = (Map<String, Object>) newConfig.get(SERVICES_NAMESPACE_TOPIC);
        serviceConfig.forEach((name, config) -> {
            if (serviceBootstrapRequired(name, (Map<String, Object>) config)) {
                logger.atDebug().kv(COMPONENT_NAME_LOG_KEY_NAME, name).log("Found pending bootstrap task");
                componentsRequiresBootstrapTask.add(name);
            }
        });
        if (componentsRequiresBootstrapTask.isEmpty()) {
            // Force restart if
            // 1. any nucleus config change requires restart or
            // 2. if any plugin will be removed in the deployment to ensure plugin cleanup
            return nucleusConfigValidAndNeedsRestart || willRemovePlugins(serviceConfig);
        }
        List<String> errors = new ArrayList<>();
        // Figure out the dependency order within the subset of components which require changes
        LinkedHashSet<String> dependencyFound =
                new DependencyOrder<String>().computeOrderedDependencies(componentsRequiresBootstrapTask,
                        name -> getDependenciesWithinSubset(name, componentsRequiresBootstrapTask,
                                (Map<String, Object>) serviceConfig.get(name), errors));
        if (!errors.isEmpty()) {
            throw new ServiceUpdateException(errors.toString(), DeploymentErrorCode.COMPONENT_DEPENDENCY_NOT_VALID);
        }
        logger.atInfo().kv("list", dependencyFound).log("Found a list of bootstrap tasks in dependency order");
        dependencyFound.forEach(name -> bootstrapTaskStatusList.add(new BootstrapTaskStatus(name)));

        return nucleusConfigValidAndNeedsRestart || !bootstrapTaskStatusList.isEmpty();
    }

    private boolean willRemovePlugins(Map<String, Object> serviceConfig) {
        Set<String> pluginsToRemove = kernel.orderedDependencies().stream()
                .filter(s -> s instanceof PluginService)
                .filter(s -> !s.isBuiltin())
                .filter(s -> !serviceConfig.containsKey(s.getName()))
                .map(GreengrassService::getName)
                .collect(Collectors.toSet());
        if (!pluginsToRemove.isEmpty()) {
            logger.atInfo().kv("plugins-to-remove", pluginsToRemove)
                    .log("Bootstrap required for cleaning up plugin(s)");
        }
        return !pluginsToRemove.isEmpty();
    }

    private boolean mqttVersionHasChanged(Map<String, Object> newNucleusParameters,
                                          DeviceConfiguration currentDeviceConfiguration) {
        String currentMqttVersion = Coerce.toString(
                currentDeviceConfiguration.getMQTTNamespace().findOrDefault(DEFAULT_MQTT_VERSION, "version"));
        Map<String, Object> newMqtt = (Map<String, Object>) newNucleusParameters.get(DEVICE_MQTT_NAMESPACE);
        Object newVersion = newMqtt == null ? null : newMqtt.get("version");
        if (newVersion == null && !DEFAULT_MQTT_VERSION.equalsIgnoreCase(currentMqttVersion)
                || newVersion instanceof String && !currentMqttVersion.equalsIgnoreCase((String) newVersion)) {
            logger.atInfo().kv(DEVICE_MQTT_NAMESPACE, newMqtt).log(RESTART_REQUIRED_MESSAGE);
            return true;
        }
        return false;
    }

    private boolean spoolerStorageTypeHasChanged(Map<String, Object> newNucleusParameters,
                                          DeviceConfiguration currentDeviceConfiguration) {
        String currentSpoolerStorageType = Coerce.toString(
                currentDeviceConfiguration.getSpoolerNamespace().findOrDefault(DEFAULT_SPOOL_STORAGE_TYPE,
                        SPOOL_STORAGE_TYPE_KEY));
        Map<String, Object> newSpooler = (Map<String, Object>) newNucleusParameters.get(DEVICE_SPOOLER_NAMESPACE);
        Object newStorageType = newSpooler == null ? null : newSpooler.get(SPOOL_STORAGE_TYPE_KEY);
        if (newStorageType == null
                && !(DEFAULT_SPOOL_STORAGE_TYPE.toString().equalsIgnoreCase(currentSpoolerStorageType))
                || newStorageType instanceof String
                && !currentSpoolerStorageType.equalsIgnoreCase((String) newStorageType)
                || newStorageType instanceof SpoolerStorageType
                && !currentSpoolerStorageType.equalsIgnoreCase(newStorageType.toString())) {
            logger.atInfo().kv(DEVICE_SPOOLER_NAMESPACE, newSpooler).log(RESTART_REQUIRED_MESSAGE);
            return true;
        }
        return false;
    }

    private boolean networkProxyHasChanged(Map<String, Object> newNucleusParameters,
                                           DeviceConfiguration currentDeviceConfiguration) {
        Map<String, Object> newNetworkProxy =
                (Map<String, Object>) newNucleusParameters.get(DEVICE_NETWORK_PROXY_NAMESPACE);
        if (newNetworkProxy == null) {
            return Utils.isNotEmpty(currentDeviceConfiguration.getProxyUrl());
        }

        // deviceconfig defaults to empty string on null for network proxy parameters so we must do the same
        String newNoProxyAddresses = Coerce.toString(newNetworkProxy.getOrDefault(DEVICE_PARAM_NO_PROXY_ADDRESSES, ""));
        String currentNoProxyAddresses = Coerce.toString(currentDeviceConfiguration.getNoProxyAddresses());
        if (Utils.stringHasChanged(newNoProxyAddresses, currentNoProxyAddresses)) {
            logger.atInfo().kv(DEVICE_PARAM_NO_PROXY_ADDRESSES, newNoProxyAddresses).log(RESTART_REQUIRED_MESSAGE);
            return true;
        }

        Map<String, Object> newProxy = (Map<String, Object>) newNetworkProxy.get(DEVICE_PROXY_NAMESPACE);
        if (newProxy == null) {
            return Utils.isNotEmpty(currentDeviceConfiguration.getProxyUrl());
        }
        String newProxyUrl = Coerce.toString(newProxy.getOrDefault(DEVICE_PARAM_PROXY_URL, ""));
        String currentProxyUrl = Coerce.toString(currentDeviceConfiguration.getProxyUrl());
        if (Utils.stringHasChanged(newProxyUrl, currentProxyUrl)) {
            logger.atInfo().kv(DEVICE_PARAM_PROXY_URL, newProxyUrl).log(RESTART_REQUIRED_MESSAGE);
            return true;
        }

        String newProxyUsername = Coerce.toString(newProxy.getOrDefault(DEVICE_PARAM_PROXY_USERNAME, ""));
        String currentProxyUsername = Coerce.toString(currentDeviceConfiguration.getProxyUsername());
        if (Utils.stringHasChanged(newProxyUsername, currentProxyUsername)) {
            logger.atInfo().kv(DEVICE_PARAM_PROXY_USERNAME, newProxyUsername).log(RESTART_REQUIRED_MESSAGE);
            return true;
        }

        String newProxyPassword = Coerce.toString(newProxy.getOrDefault(DEVICE_PARAM_PROXY_PASSWORD, ""));
        String currentProxyPassword = Coerce.toString(currentDeviceConfiguration.getProxyPassword());
        if (Utils.stringHasChanged(newProxyPassword, currentProxyPassword)) {
            logger.atInfo().kv(DEVICE_PARAM_PROXY_PASSWORD, newProxyPassword).log(RESTART_REQUIRED_MESSAGE);
            return true;
        }

        return false;
    }

    private boolean defaultRunWithChanged(Map<String, Object> newNucleusParameters,
            DeviceConfiguration currentDeviceConfiguration) throws ComponentConfigurationValidationException {
        Map<String, Object> runWithDefault = (Map<String, Object>)newNucleusParameters.getOrDefault(RUN_WITH_TOPIC,
                Collections.emptyMap());

        Map<String, Object> currentValues = currentDeviceConfiguration.getRunWithTopic().toPOJO();

        boolean changed = false;
        if (Utils.stringHasChanged(Coerce.toString(currentValues.get(RUN_WITH_DEFAULT_POSIX_USER)),
                Coerce.toString(runWithDefault.get(RUN_WITH_DEFAULT_POSIX_USER)))) {
            logger.atInfo().kv(RUN_WITH_TOPIC + "." + RUN_WITH_DEFAULT_POSIX_USER,
                    runWithDefault.get(RUN_WITH_DEFAULT_POSIX_USER))
                    .log(RESTART_REQUIRED_MESSAGE);
            changed = true;
        }
        if (Utils.stringHasChanged(Coerce.toString(currentValues.getOrDefault(RUN_WITH_DEFAULT_POSIX_SHELL,
                RUN_WITH_DEFAULT_POSIX_SHELL_VALUE)),
                Coerce.toString(runWithDefault.getOrDefault(RUN_WITH_DEFAULT_POSIX_SHELL,
                        RUN_WITH_DEFAULT_POSIX_SHELL_VALUE)))) {
            logger.atInfo().kv(RUN_WITH_TOPIC + "." + RUN_WITH_DEFAULT_POSIX_SHELL,
                    runWithDefault.get(RUN_WITH_DEFAULT_POSIX_SHELL))
                    .log(RESTART_REQUIRED_MESSAGE);
            changed = true;
        }

        if (changed) {
            try {
                platform.getRunWithGenerator().validateDefaultConfiguration(runWithDefault);
            } catch (DeviceConfigurationException e) {
                throw new ComponentConfigurationValidationException(e, DeploymentErrorCode.RUN_WITH_CONFIG_NOT_VALID);
            }
            try {
                logger.atInfo().kv("changed", RUN_WITH_TOPIC)
                        .kv("old", SerializerFactory.getFailSafeJsonObjectMapper().writeValueAsString(currentValues))
                        .kv("new", SerializerFactory.getFailSafeJsonObjectMapper().writeValueAsString(runWithDefault))
                        .log(RESTART_REQUIRED_MESSAGE);
            } catch (JsonProcessingException e) {
                throw new ComponentConfigurationValidationException(e);
            }
            return true;
        }
        return false;
    }

    private boolean nucleusConfigChangeRequiresRestart(Map<String, Object> newNucleusParameters,
                                                       DeviceConfiguration currentDeviceConfiguration)
            throws ComponentConfigurationValidationException {
        // validation must not be skipped - otherwise the nucleus will be restarted with invalid config
        boolean proxyChanged =  networkProxyHasChanged(newNucleusParameters, currentDeviceConfiguration);
        boolean runWithChanged = defaultRunWithChanged(newNucleusParameters, currentDeviceConfiguration);
        boolean mqttVersionChanged = mqttVersionHasChanged(newNucleusParameters, currentDeviceConfiguration);
        boolean spoolerStorageTypeChanged = spoolerStorageTypeHasChanged(newNucleusParameters,
                currentDeviceConfiguration);

        return proxyChanged || runWithChanged || mqttVersionChanged || spoolerStorageTypeChanged;
    }

    private boolean nucleusConfigValidAndNeedsRestart(Map<String, Object> deploymentConfig)
            throws ComponentConfigurationValidationException {
        boolean needsRestart = false;
        Map<String, Object> proposedNucleusConfig = getProposedNucleusConfig(deploymentConfig);

        needsRestart = nucleusConfigChangeRequiresRestart(proposedNucleusConfig,
                kernel.getContext().get(DeviceConfiguration.class));

        for (GreengrassService s : kernel.orderedDependencies()) {
            // For now, only let builtin Greengrass services decide
            if (s.isBuiltin()) {
                // Don't short-circuit validation - restartNucleusOnNucleusConfigChange throws
                // ComponentConfigurationValidationException. Each component should check that the config is valid
                // before accepting the change and restarting the Nucleus. The exception is handled by the
                // DeploymentActivatorFactory to reject the deployment.
                boolean componentChanged = s.restartNucleusOnNucleusConfigChange(proposedNucleusConfig);
                needsRestart = needsRestart || componentChanged;
            }
        }

        return needsRestart;
    }

    private Map<String, Object> getProposedNucleusConfig(Map<String, Object> deploymentConfig) {
        Map<String, Object> services = (Map<String, Object>) deploymentConfig.getOrDefault(SERVICES_NAMESPACE_TOPIC,
                Collections.emptyMap());
        for (Map.Entry<String, Object> serviceConfig : services.entrySet()) {
            if (serviceConfig.getValue() instanceof Map) {
                Map<String, Object> serviceConfigMap = (Map<String, Object>) serviceConfig.getValue();
                String componentType = Coerce.toString(serviceConfigMap.get(SERVICE_TYPE_TOPIC_KEY));
                Object componentConfiguration = serviceConfigMap.get(KernelConfigResolver.CONFIGURATION_CONFIG_KEY);
                if (ComponentType.NUCLEUS.name().equals(componentType) && componentConfiguration instanceof Map) {
                    return (Map<String, Object>) componentConfiguration;
                }
            }
        }
        return Collections.emptyMap();
    }

    /**
     * Find out dependencies of the given component within a subset of components.
     *
     * @param componentName name of the component
     * @param subset a subset of components
     * @param componentConfig config of the component
     */
    private Set<String> getDependenciesWithinSubset(String componentName, Set<String> subset,
                                                    Map<String, Object> componentConfig, List<String> errors) {
        Set<String> relevantDependencies = new HashSet<>();
        if (!componentConfig.containsKey(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC)) {
            return relevantDependencies;
        }
        Iterable<String> dependencyList = (Iterable<String>) componentConfig.get(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC);
        for (String dependency : dependencyList) {
            try {
                String depName = GreengrassService.parseSingleDependency(dependency).getLeft();
                if (subset.contains(depName)) {
                    relevantDependencies.add(depName);
                }
            } catch (InputValidationException e) {
                logger.atError().kv(COMPONENT_NAME_LOG_KEY_NAME, componentName).setCause(e).log(
                        "Ignore component with invalid dependency setting");
                errors.add(e.getMessage());
            }
        }
        return relevantDependencies;
    }

    /**
     * Check if Kernel needs to run bootstrap task of the given component.
     *
     * @param componentName the name of the component
     * @return true if a new component has bootstrap step defined, or existing component update requires bootstrap,
     *      false otherwise
     */
    boolean serviceBootstrapRequired(String componentName, Map<String, Object> newServiceConfig) {
        // For existing components, call service to decide
        try {
            GreengrassService service = kernel.locate(componentName);
            return service.isBootstrapRequired(newServiceConfig);
        } catch (ServiceLoadException ignore) {
        }
        // For newly added components, check if bootstrap is specified in config map
        if (!newServiceConfig.containsKey(SERVICE_LIFECYCLE_NAMESPACE_TOPIC)) {
            logger.atDebug().kv(COMPONENT_NAME_LOG_KEY_NAME, componentName)
                    .log("Bootstrap is not required: service lifecycle config not found");
            return false;
        }
        Map<String, Object> newServiceLifecycle =
                (Map<String, Object>) newServiceConfig.get(SERVICE_LIFECYCLE_NAMESPACE_TOPIC);
        if (serviceLifecycleDefined(newServiceLifecycle, LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC).isEmpty()) {
            logger.atDebug().kv(COMPONENT_NAME_LOG_KEY_NAME, componentName)
                    .log("Bootstrap is not required: service lifecycle bootstrap not found");
            return false;
        }
        logger.atInfo().kv(COMPONENT_NAME_LOG_KEY_NAME, componentName)
                .log("Bootstrap is required: new service with bootstrap defined");
        return true;
    }

    /**
     * Persist the bootstrap task list to file.
     *
     * @param persistedTaskFilePath Path to the persisted file of bootstrap tasks
     * @throws IOException on I/O error
     */
    public void persistBootstrapTaskList(Path persistedTaskFilePath) throws IOException {
        Objects.requireNonNull(persistedTaskFilePath);
        logger.atInfo().kv("filePath", persistedTaskFilePath).log("Saving bootstrap task list to file");
        Files.deleteIfExists(persistedTaskFilePath);
        Files.createFile(persistedTaskFilePath);

        try (CommitableWriter out = CommitableWriter.commitOnClose(persistedTaskFilePath)) {
            SerializerFactory.getFailSafeJsonObjectMapper().writeValue(out, bootstrapTaskStatusList);
        }
        logger.atInfo().kv("filePath", persistedTaskFilePath).log("Bootstrap task list is saved to file");
    }

    /**
     * Persist the bootstrap task list from file.
     *
     * @param persistedTaskFilePath path to the persisted file of bootstrap tasks
     * @throws IOException on I/O error
     * @throws ClassNotFoundException deserialization of the file content fails
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    public void loadBootstrapTaskList(Path persistedTaskFilePath) throws IOException {
        Objects.requireNonNull(persistedTaskFilePath);

        CommitableReader.of(persistedTaskFilePath).read(in -> {
            bootstrapTaskStatusList.clear();
            bootstrapTaskStatusList.addAll(SerializerFactory.getFailSafeJsonObjectMapper()
                    .readValue(in, new TypeReference<List<BootstrapTaskStatus>>(){}));
            return null;
        });
    }

    /**
     * Execute the given bootstrap task.
     *
     * @param next BootstrapTaskStatus object
     * @return 100 if kernel restart is needed, 101 if device reboot is needed, 0 if no-op.
     */
    protected int executeOneBootstrapTask(BootstrapTaskStatus next) throws ServiceUpdateException {
        Objects.requireNonNull(next);
        try {
            int exitCode = kernel.locate(next.getComponentName()).bootstrap();
            next.setStatus(DONE);
            next.setExitCode(exitCode);
            return exitCode;
        } catch (TimeoutException e) {
            throw new ServiceUpdateException(e, DeploymentErrorCode.COMPONENT_BOOTSTRAP_TIMEOUT,
                    DeploymentErrorCodeUtils.classifyComponentError(next.getComponentName(), kernel));
        } catch (InterruptedException | ServiceLoadException e) {
            throw new ServiceUpdateException(e);
        }
    }

    /**
     * Execute all bootstrap steps one by one, until kernel restart or device reboot is requested to complete any one
     * of the bootstrap steps.
     *
     * @param persistedTaskFilePath Path to the persisted file of bootstrap task list
     * @return 100 if kernel restart is needed, 101 if device reboot is needed, 0 if no-op.
     * @throws ServiceUpdateException if a bootstrap step fails
     * @throws IOException on I/O error
     */
    public int executeAllBootstrapTasksSequentially(Path persistedTaskFilePath)
            throws ServiceUpdateException, IOException {
        Objects.requireNonNull(persistedTaskFilePath);
        int exitCode;
        while (hasNext()) {
            BootstrapTaskStatus next = next();
            logger.atInfo().kv(COMPONENT_NAME_LOG_KEY_NAME, next.getComponentName())
                    .log("Execute component bootstrap step");
            exitCode = executeOneBootstrapTask(next);

            switch (exitCode) {
                case NO_OP:
                case REQUEST_RESTART:
                case REQUEST_REBOOT:
                    persistBootstrapTaskList(persistedTaskFilePath);
                    break;
                default:
                    persistBootstrapTaskList(persistedTaskFilePath);
                    throw new ServiceUpdateException(
                            String.format("Fail to execute bootstrap step for %s, exit code: %d",
                                    next.getComponentName(), exitCode), DeploymentErrorCode.COMPONENT_BOOTSTRAP_ERROR,
                            DeploymentErrorCodeUtils.classifyComponentError(next.getComponentName(), kernel));
            }
            if (exitCode != 0) {
                return exitCode;
            }
        }
        return 0;
    }

    @Override
    public boolean hasNext() {
        while (cursor < bootstrapTaskStatusList.size()) {
            BootstrapTaskStatus next = bootstrapTaskStatusList.get(cursor);
            if (!DONE.equals(next.getStatus()) || BootstrapSuccessCode.isErrorCode(next.getExitCode())) {
                return true;
            }
            cursor++;
        }
        return false;
    }

    @Override
    public BootstrapTaskStatus next() {
        if (cursor >= bootstrapTaskStatusList.size()) {
            throw new NoSuchElementException();
        }
        cursor++;
        return bootstrapTaskStatusList.get(cursor - 1);
    }
}
