/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.deployment.exceptions.ServiceUpdateException;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.exceptions.InputValidationException;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.DependencyOrder;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import javax.inject.Inject;

import static com.aws.iot.evergreen.deployment.BootstrapManager.BootstrapTaskStatus.ExecutionStatus.DONE;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.VERSION_CONFIG_KEY;

public class BootstrapManager implements Iterator<BootstrapManager.BootstrapTaskStatus>  {
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
     * Check if any bootstrap tasks are pending. Meanwhile resolve a list of bootstrap tasks if not done already.
     *
     * @param newConfig new configuration from deployment
     * @return true if there are bootstrap tasks, false otherwise
     */
    public boolean isBootstrapRequired(Map<Object, Object> newConfig) {
        if (hasNext()) {
            return true;
        }
        bootstrapTaskStatusList.clear();

        if (newConfig == null || !newConfig.containsKey(SERVICES_NAMESPACE_TOPIC)) {
            logger.atError().log(
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
        // Figure out the dependency order within the subset of components which require changes
        final LinkedHashSet<String> dependencyFound =
                new DependencyOrder<String>().computeOrderedDependencies(componentsRequiresBootstrapTask,
                        name -> getRelevantDependencies(name, componentsRequiresBootstrapTask,
                                (Map<String, Object>) serviceConfig.get(name)));
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
    private Set<String> getRelevantDependencies(String componentName, Set<String> subset,
                                                Map<String, Object> componentConfig) {
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
        Map<String, Object> serviceLifecycle =
                (Map<String, Object>) newServiceConfig.get(SERVICE_LIFECYCLE_NAMESPACE_TOPIC);
        if (!serviceLifecycle.containsKey(LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC)
                || serviceLifecycle.get(LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC) == null) {
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
        Topic serviceBootstrap = service.getConfig().find(SERVICE_LIFECYCLE_NAMESPACE_TOPIC,
                LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC);
        return serviceBootstrap == null
                || !serviceBootstrap.toPOJO().equals(serviceLifecycle.get(LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC));
    }

    public void persistBootstrapTaskList() {
        // TODO: write bootstrapTaskStatusList to writer
        // TODO: add file validation
    }

    public void loadBootstrapTaskList() {
        // TODO: read bootstrapTaskStatusList
        // TODO: validate file
    }

    /**
     * Execute the given bootstrap task.
     *
     * @param next BootstrapTaskStatus object
     * @return 100 if kernel restart is needed, 101 if device reboot is needed, 0 if no-op.
     */
    public int executeOneBootstrapTask(BootstrapTaskStatus next) {
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
            logger.atInfo().kv("componentName", next.componentName).log("Execute component bootstrap step");
            exitCode = executeOneBootstrapTask(next);
            switch (exitCode) {
                case 0:
                case 100:
                case 101:
                    persistBootstrapTaskList();
                    break;
                default:
                    throw new ServiceUpdateException("Fail to execute bootstrap step for " + next.componentName);
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
            if (!DONE.equals(bootstrapTaskStatusList.get(cursor).status)) {
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

    @Data
    @EqualsAndHashCode
    @AllArgsConstructor(access = AccessLevel.PACKAGE)
    public static class BootstrapTaskStatus {
        private String componentName;
        private ExecutionStatus status;
        private int exitCode;

        public BootstrapTaskStatus(String name) {
            this.componentName = name;
            this.status = ExecutionStatus.PENDING;
        }

        public enum ExecutionStatus {
            PENDING, DONE
        }
    }
}
