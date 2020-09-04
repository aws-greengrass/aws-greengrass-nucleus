/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment.bootstrap;

import com.aws.iot.evergreen.deployment.exceptions.ServiceUpdateException;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.exceptions.InputValidationException;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.DependencyOrder;
import com.aws.iot.evergreen.util.SerializerFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import javax.annotation.concurrent.NotThreadSafe;
import javax.inject.Inject;

import static com.aws.iot.evergreen.deployment.bootstrap.BootstrapSuccessCode.NO_OP;
import static com.aws.iot.evergreen.deployment.bootstrap.BootstrapSuccessCode.REQUEST_REBOOT;
import static com.aws.iot.evergreen.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;
import static com.aws.iot.evergreen.deployment.bootstrap.BootstrapTaskStatus.ExecutionStatus.DONE;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC;

/**
 * Generates a list of bootstrap tasks from deployments, manages the execution and persists status.
 */
@NotThreadSafe
public class BootstrapManager implements Iterator<BootstrapTaskStatus>  {
    private static final String COMPONENT_NAME_LOG_KEY_NAME = "componentName";
    private static final Logger logger = LogManager.getLogger(BootstrapManager.class);
    @Setter(AccessLevel.PACKAGE)
    @Getter(AccessLevel.PACKAGE)
    private List<BootstrapTaskStatus> bootstrapTaskStatusList = new ArrayList<>();
    private final Kernel kernel;
    private int cursor;

    /**
     * Constructor for BootstrapManager to process bootstrap tasks from deployments.
     *
     * @param kernel Kernel instance
     */
    @Inject
    public BootstrapManager(Kernel kernel) {
        this.kernel = kernel;
        this.cursor = 0;
    }

    /**
     * Check if any bootstrap tasks are pending based on new configuration.
     * Meanwhile resolve a list of bootstrap tasks.
     *
     * @param newConfig new configuration from deployment
     * @return true if there are bootstrap tasks, false otherwise
     * @throws ServiceUpdateException if parsing bootstrap tasks from new configuration fails
     */
    @SuppressWarnings("PMD.PrematureDeclaration")
    public boolean isBootstrapRequired(Map<Object, Object> newConfig) throws ServiceUpdateException {
        bootstrapTaskStatusList.clear();
        cursor = 0;

        if (newConfig == null || !newConfig.containsKey(SERVICES_NAMESPACE_TOPIC)) {
            logger.atInfo().log(
                    "No bootstrap tasks found: Deployment configuration is missing or has no service changes");
            return false;
        }
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
            return false;
        }
        List<String> errors = new ArrayList<>();
        // Figure out the dependency order within the subset of components which require changes
        LinkedHashSet<String> dependencyFound =
                new DependencyOrder<String>().computeOrderedDependencies(componentsRequiresBootstrapTask,
                        name -> getDependenciesWithinSubset(name, componentsRequiresBootstrapTask,
                                (Map<String, Object>) serviceConfig.get(name), errors));
        if (!errors.isEmpty()) {
            throw new ServiceUpdateException(errors.toString());
        }
        logger.atInfo().kv("list", dependencyFound).log("Found a list of bootstrap tasks in dependency order");
        dependencyFound.forEach(name -> bootstrapTaskStatusList.add(new BootstrapTaskStatus(name)));

        return !bootstrapTaskStatusList.isEmpty();
    }

    /**
     * Find out dependencies of the given component within a subset of components.
     *
     * @param componentName name of the component
     * @param subset a subset of components
     * @param componentConfig config of the component
     * @return
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
                String depName = EvergreenService.parseSingleDependency(dependency).getLeft();
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
            EvergreenService service = kernel.locate(componentName);
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
        if (!newServiceLifecycle.containsKey(LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC)
                || newServiceLifecycle.get(LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC) == null) {
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
        // TODO: add file validation
        Objects.requireNonNull(persistedTaskFilePath);
        logger.atInfo().kv("filePath", persistedTaskFilePath).log("Saving bootstrap task list to file");
        Files.deleteIfExists(persistedTaskFilePath);
        Files.createFile(persistedTaskFilePath);

        try (OutputStream out = Files.newOutputStream(persistedTaskFilePath)) {
            SerializerFactory.getJsonObjectMapper().writeValue(out, bootstrapTaskStatusList);
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
    public void loadBootstrapTaskList(Path persistedTaskFilePath) throws IOException {
        // TODO: validate file
        Objects.requireNonNull(persistedTaskFilePath);

        try (InputStream input = Files.newInputStream(persistedTaskFilePath)) {
            bootstrapTaskStatusList.clear();
            bootstrapTaskStatusList.addAll(SerializerFactory.getJsonObjectMapper()
                    .readValue(input, new TypeReference<List<BootstrapTaskStatus>>(){}));
        }
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
            return exitCode;
        } catch (InterruptedException | TimeoutException | ServiceLoadException e) {
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
                    throw new ServiceUpdateException(String.format(
                            "Fail to execute bootstrap step for %s, exit code: %d", next.getComponentName(), exitCode));
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
            if (!DONE.equals(bootstrapTaskStatusList.get(cursor).getStatus())) {
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
