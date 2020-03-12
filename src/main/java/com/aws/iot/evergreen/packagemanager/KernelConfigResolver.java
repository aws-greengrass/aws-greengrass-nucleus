/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentPackageConfiguration;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.packagemanager.models.Package;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.models.PackageParameter;
import lombok.AllArgsConstructor;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@AllArgsConstructor
public class KernelConfigResolver {

    private static final String SERVICE_DEPENDENCIES_CONFIG_KEY = "dependencies";
    private static final String VERSION_CONFIG_KEY = "version";
    private static final String VERSION_CONSTRAINT_CONFIG_KEY = "versionconstraint";
    private static final String SERVICE_NAMESPACE_CONFIG_KEY = "services";
    private static final String PARAMETER_REFERENCE_FORMAT = "{{params:%s.value}}";

    private final PackageCache packageCache;
    private final Kernel kernel;

    /**
     * Create a kernel config map from a list of package identifiers and deployment document.
     * For each package, it first retrieves its recipe, then merges the parameter values into the recipe, and last
     * transform it to a kernel config key-value pair.
     *
     * @param packagesToDeploy     package identifiers for resolved packages that are to be deployed
     * @param document             deployment document
     * @param rootPackagesToRemove top level packages that need to be removed as part of current deployment
     * @return a kernel config map
     * @throws InterruptedException when the running thread is interrupted
     */
    public Map<Object, Object> resolve(List<PackageIdentifier> packagesToDeploy, DeploymentDocument document,
                                       Set<PackageIdentifier> rootPackagesToRemove) throws InterruptedException {

        Map<Object, Object> servicesConfig = new HashMap<>();

        packagesToDeploy.forEach(packageIdentifier -> servicesConfig
                .put(packageIdentifier.getName(), getServiceConfig(packageIdentifier, document)));

        servicesConfig.put(kernel.getMain().getName(), getUpdatedMainConfig(rootPackagesToRemove, document));

        // Services need to be under the services namespace in kernel config
        return Collections.singletonMap(SERVICE_NAMESPACE_CONFIG_KEY, servicesConfig);
    }

    /*
     * Processe lifecycle section of each package and add it to the config.
     */
    private Map<Object, Object> getServiceConfig(PackageIdentifier packageIdentifier, DeploymentDocument document) {

        Package pkg = packageCache.getRecipe(packageIdentifier);

        Map<Object, Object> resolvedServiceConfig = new HashMap<>();

        // TODO : Package recipe format is not in alignment with the changed Kernel config syntax,
        // which leads to inconsistent naming, e.g. lifecycle per new Kernel config syntax is one of several config
        // keys while per current package recipe format it's the entire config for that package
        // These incosistencies need to be addressed

        // Interpolate parameters
        Set<PackageParameter> resolvedParams = resolveParameterValuesToUse(document, pkg);
        for (Map.Entry<String, Object> configKVPair : pkg.getLifecycle().entrySet()) {
            resolvedServiceConfig.put(configKVPair.getKey(), interpolate(configKVPair.getValue(), resolvedParams));
        }

        // TODO : Update package recipe format to include all information that service dependencies config
        // expects according to the new syntax e.g. isHotPluggable, dependency service state,
        // then change the following code accordingly

        // Generate dependencies
        // TODO : Only platform specific dependencies should be added once deployment document and
        // package recipe format supports platform wise dependency specification
        Set<String> dependencyServiceNames = pkg.getDependencies().keySet();
        resolvedServiceConfig.put(SERVICE_DEPENDENCIES_CONFIG_KEY, String.join(", ", dependencyServiceNames));

        resolvedServiceConfig.put(VERSION_CONFIG_KEY, pkg.getVersion());
        return resolvedServiceConfig;
    }

    /*
     * For each lifecycle key-value pair of a package, substitute parameter values.
     */
    private Object interpolate(Object configValue, Set<PackageParameter> packageParameters) {

        Object result = configValue;
        if (configValue instanceof String) {
            String value = (String) configValue;

            // Handle package parameters
            for (final PackageParameter parameter : packageParameters) {
                value = value.replace(String.format(PARAMETER_REFERENCE_FORMAT, parameter.getName()),
                        parameter.getValue());
            }
            result = value;

            // TODO : Handle system parameters
        }
        if (configValue instanceof Map) {
            Map<String, Object> childConfigMap = (Map<String, Object>) configValue;
            Map<Object, Object> resolvedChildConfig = new HashMap<>();
            for (Map.Entry<String, Object> childLifecycle : childConfigMap.entrySet()) {
                resolvedChildConfig
                        .put(childLifecycle.getKey(), interpolate(childLifecycle.getValue(), packageParameters));
            }
            result = resolvedChildConfig;
        }
        // TODO : Do we want to support other config types than map of
        // string k,v pairs? e.g. how should lists be handled?
        return result;
    }


    /*
     * Recompute main service dependencies for deployment.
     */
    private Map<Object, Object> getUpdatedMainConfig(Set<PackageIdentifier> rootPackagesToRemove,
                                                     DeploymentDocument document) {
        Set<String> kernelDependencies =
                kernel.getMain().getDependencies().keySet().stream().map(EvergreenService::getName)
                        .collect(Collectors.toSet());
        kernelDependencies
                .removeAll(rootPackagesToRemove.stream().map(PackageIdentifier::getName).collect(Collectors.toSet()));
        kernelDependencies.addAll(document.getRootPackages());

        Map<Object, Object> mainLifecycleMap = new HashMap<>();
        mainLifecycleMap.put(SERVICE_DEPENDENCIES_CONFIG_KEY, String.join(", ", kernelDependencies));

        return mainLifecycleMap;
    }

    /*
     * Get configuration for a package-version combination from deployment document.
     */
    private Optional<DeploymentPackageConfiguration> getMatchingPackageConfigFromDeployment(DeploymentDocument document,
                                                                                            String packageName,
                                                                                            String packageVersion) {
        return document.getDeploymentPackageConfigurationList().stream()
                .filter(packageConfig -> packageName.equals(packageConfig.getPackageName()) && packageVersion.toString()
                        .equals(packageConfig.getResolvedVersion())).findAny();
    }

    /*
     * Resolve values to be used for all package parameters combining those coming from
     * deployment document and defaults for the rest.
     */
    private Set<PackageParameter> resolveParameterValuesToUse(DeploymentDocument document, Package pkg) {
        // If values for parameters were set in deployment they should be used
        Set<PackageParameter> resolvedParams = new HashSet<>(getParametersFromDeployment(document, pkg));

        // Use defaults for parameters for which no values were set in deployment
        resolvedParams.addAll(pkg.getPackageParameters());
        return resolvedParams;
    }

    /*
     * Get parameter values for a package set by customer from deployment document.
     */
    private Set<PackageParameter> getParametersFromDeployment(DeploymentDocument document, Package pkg) {
        Optional<DeploymentPackageConfiguration> packageConfigInDeployment =
                getMatchingPackageConfigFromDeployment(document, pkg.getPackageName(), pkg.getVersion().toString());
        if (packageConfigInDeployment.isPresent()) {
            return packageConfigInDeployment.get().getParameters();
        }
        return Collections.emptySet();
    }

}
