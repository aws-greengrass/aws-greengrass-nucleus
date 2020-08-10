/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment.bootstrap;

import com.aws.iot.evergreen.config.Topic;
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
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.VERSION_CONFIG_KEY;

/**
 * Generates a list of bootstrap tasks from deployments, manages the execution and persists status.
 */
@NotThreadSafe
public class BootstrapManager implements Iterator<BootstrapTaskStatus>  {
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
                logger.atError().kv("componentName", componentName).setCause(e).log(
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
     * @return true if component has a bootstrap step under one of the following conditions:
     *      1. component is newly added, 2. component version changes, 3. component bootstrap step changes.
     *      false otherwise
     */
    boolean serviceBootstrapRequired(String componentName, Map<String, Object> newServiceConfig) {
        if (!newServiceConfig.containsKey(SERVICE_LIFECYCLE_NAMESPACE_TOPIC)) {
            return false;
        }
        Map<String, Object> newServiceLifecycle =
                (Map<String, Object>) newServiceConfig.get(SERVICE_LIFECYCLE_NAMESPACE_TOPIC);
        if (!newServiceLifecycle.containsKey(LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC)
                || newServiceLifecycle.get(LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC) == null) {
            return false;
        }
        EvergreenService service;
        try {
            service = kernel.locate(componentName);
        } catch (ServiceLoadException e) {
            return true;
        }
        if (!service.getConfig().find(VERSION_CONFIG_KEY).getOnce()
                .equals(newServiceConfig.get(VERSION_CONFIG_KEY))) {
            return true;
        }
        // TODO: Support bootstrap node to be Topics
        Topic serviceOldBootstrap = service.getConfig().find(SERVICE_LIFECYCLE_NAMESPACE_TOPIC,
                LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC);
        return serviceOldBootstrap == null
                || !serviceOldBootstrap.toPOJO().equals(newServiceLifecycle.get(LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC));
    }

    public void persistBootstrapTaskList() {
        // TODO: write bootstrapTaskStatusList to Path persistedTaskFilePath
        // TODO: add file validation
    }

    /**
     * Persist the bootstrap task list from file.
     *
     * @param persistedTaskFilePath path to the persisted file for bootstrap tasks
     * @throws IOException on I/O error or when file path to persist bootstrap task list is not set (wrong usage)
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
    protected int executeOneBootstrapTask(BootstrapTaskStatus next) {
        Objects.requireNonNull(next);
        // TODO: support bootstrap step in Evergreen Services.
        // Load service BootstrapTaskStatus.componentName and call bootstrap here.

        return 0;
    }

    /**
     * Execute all bootstrap steps one by one, until kernel restart or device reboot is requested to complete any one
     * of the bootstrap steps.
     *
     * @return 100 if kernel restart is needed, 101 if device reboot is needed, 0 if no-op.
     * @throws ServiceUpdateException if a bootstrap step fails
     */
    public int executeAllBootstrapTasksSequentially() throws ServiceUpdateException {
        int exitCode;
        while (hasNext()) {
            BootstrapTaskStatus next = next();
            logger.atInfo().kv("componentName", next.getComponentName()).log("Execute component bootstrap step");
            exitCode = executeOneBootstrapTask(next);

            switch (exitCode) {
                case NO_OP:
                case REQUEST_RESTART:
                case REQUEST_REBOOT:
                    persistBootstrapTaskList();
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
