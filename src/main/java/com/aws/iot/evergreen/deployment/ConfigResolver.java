/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.models.Package;
import com.aws.iot.evergreen.packagemanager.models.PackageParameter;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * For deployment with a map of package dependency trees, generates
 * Kernel config to be merged and interpolates parameter values into the configs.
 */
@RequiredArgsConstructor
public class ConfigResolver {

    private static final Logger logger = LogManager.getLogger(ConfigResolver.class);

    private static final String REQUIRES_CONFIG_KEY = "requires";
    private static final String VERSION_CONFIG_KEY = "version";
    private static final String PARAMETER_REFERENCE_FORMAT = "{{params:%s.value}}";

    private final Kernel kernel;

    // Mapping of top level package name -> resolved dependency tree
    private final Set<Package> packagesToDeploy;

    private final Set<String> removedTopLevelPackageNames;

    /**
     * Generates config to be merged with kernel.
     *
     * @return kernel config to be merged
     */
    public Map<Object, Object> resolveConfig() {
        Map<Object, Object> newConfig = new HashMap<>();

        packagesToDeploy.forEach(pkg -> {
            processPackage(newConfig, pkg);
        });

        newConfig.put(kernel.getMain().getName(), getUpdatedMainConfig());

        return newConfig;
    }

    /**
     * Processes lifecycle section of each packaage and adds it to the config.
     *
     * @param newConfig instance of config that services are to be added to
     * @param pkg       package object with package information
     */
    // TODO : Revisit after the Kernel config syntax is updated, DA currently does not understand the lifecycle syntax
    private void processPackage(Map<Object, Object> newConfig, Package pkg) {
        Map<Object, Object> lifecycle = new HashMap<>(pkg.getLifecycle());

        // Interpolate parameters
        for (Object lifecycleKey : lifecycle.keySet()) {
            interpolate(lifecycleKey, lifecycle, pkg.getPackageParameters());
        }

        // Generate requires list
        Set<String> dependencyServiceNames =
                pkg.getDependencyPackages().stream().map(Package::getPackageName).collect(Collectors.toSet());
        addServiceDependencies(lifecycle, dependencyServiceNames);

        lifecycle.put(VERSION_CONFIG_KEY, pkg.getVersion());
        newConfig.put(pkg.getPackageName(), lifecycle);

        // Process dependency packages
        pkg.getDependencyPackages().forEach(dependency -> processPackage(newConfig, dependency));
    }

    /**
     * For lifecycle key-value pair of a package, substitutes parameter values.
     *
     * @param lifecycleKey      key of the key value pair in lifecycle map
     * @param lifecycle         lifecycle map
     * @param packageParameters all parameters configured for package
     */
    private void interpolate(Object lifecycleKey, Map<Object, Object> lifecycle,
                             Set<PackageParameter> packageParameters) {
        if (lifecycle.get(lifecycleKey) instanceof String) {
            String value = (String) lifecycle.get(lifecycleKey);

            // Handle package parameters
            for (final PackageParameter parameter : packageParameters) {
                value = value.replace(String.format(PARAMETER_REFERENCE_FORMAT, parameter.getName()),
                        parameter.getValue());
            }
            lifecycle.put(lifecycleKey, value);

            // TODO : Handle system parameters
        } else {
            Map<Object, Object> childLifecycleMap = (Map<Object, Object>) lifecycle.get(lifecycleKey);
            for (Object childLifecycleKey : childLifecycleMap.keySet()) {
                interpolate(childLifecycleKey, childLifecycleMap, packageParameters);
            }
        }
    }


    /**
     * Recompute main service dependencies for deployment.
     *
     * @return main service with updated dependencies
     */
    private Map<Object, Object> getUpdatedMainConfig() {
        Set<String> kernelDependencies =
                kernel.getMain().getDependencies().keySet().stream().map(s -> s.getName()).collect(Collectors.toSet());
        kernelDependencies.removeAll(removedTopLevelPackageNames);
        kernelDependencies.addAll(packagesToDeploy.stream().map(Package::getPackageName).collect(Collectors.toSet()));

        Map<Object, Object> mainLifecycleMap = new HashMap<>();
        addServiceDependencies(mainLifecycleMap, kernelDependencies);

        return mainLifecycleMap;
    }

    void addServiceDependencies(Map<Object, Object> lifecycle, final Set<String> dependencies) {
        lifecycle.put(REQUIRES_CONFIG_KEY, String.join(", ", dependencies));
    }
}

