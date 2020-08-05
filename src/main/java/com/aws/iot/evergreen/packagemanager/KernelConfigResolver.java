/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentPackageConfiguration;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageLoadingException;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.models.PackageParameter;
import com.aws.iot.evergreen.packagemanager.models.PackageRecipe;
import com.aws.iot.evergreen.util.Utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.inject.Inject;

import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.Kernel.SERVICE_TYPE_TOPIC_KEY;

public class KernelConfigResolver {

    public static final String VERSION_CONFIG_KEY = "version";
    public static final String PARAMETERS_CONFIG_KEY = "parameters";
    private static final String INTERPOLATION_FORMAT = "{{%s:%s}}";
    private static final Pattern CROSS_INTERPOLATION_REGEX =
            Pattern.compile("\\{\\{([\\.\\w]+):([\\.\\w+]+):([\\.\\w]+)}}");
    private static final String PARAM_NAMESPACE = "params";
    private static final String PARAM_VALUE_SUFFIX = ".value";
    private static final String PARAMETER_REFERENCE_FORMAT =
            String.format(INTERPOLATION_FORMAT, PARAM_NAMESPACE, "%s" + PARAM_VALUE_SUFFIX);
    // Map from Namespace -> Key -> Function which returns the replacement value
    private final Map<String, Map<String, Function<PackageIdentifier, String>>> systemParameters = new HashMap<>();

    private final PackageStore packageStore;
    private final Kernel kernel;
    private final Map<PackageIdentifier, PackageRecipe> recipeCache = new ConcurrentHashMap<>();

    /**
     * Constructor.
     *
     * @param packageStore package store used to look up packages
     * @param kernel       kernel
     */
    @Inject
    public KernelConfigResolver(PackageStore packageStore, Kernel kernel) {
        this.packageStore = packageStore;
        this.kernel = kernel;

        // More system parameters can be added over time by extending this map with new namespaces/keys
        HashMap<String, Function<PackageIdentifier, String>> artifactNamespace = new HashMap<>();
        artifactNamespace
                .put("path", (id) -> packageStore.resolveArtifactDirectoryPath(id).toAbsolutePath().toString());
        systemParameters.put("artifacts", artifactNamespace);
    }

    /**
     * Create a kernel config map from a list of package identifiers and deployment document. For each package, it first
     * retrieves its recipe, then merges the parameter values into the recipe, and last transform it to a kernel config
     * key-value pair.
     *
     * @param packagesToDeploy package identifiers for resolved packages that are to be deployed
     * @param document         deployment document
     * @param rootPackages     root level packages
     * @return a kernel config map
     * @throws PackageLoadingException if any service package was unable to be loaded
     */
    public Map<Object, Object> resolve(List<PackageIdentifier> packagesToDeploy, DeploymentDocument document,
                                       List<String> rootPackages) throws PackageLoadingException {
        Map<Object, Object> servicesConfig = new HashMap<>();
        for (PackageIdentifier packageToDeploy : packagesToDeploy) {
            servicesConfig
                    .put(packageToDeploy.getName(), getServiceConfig(packageToDeploy, document, packagesToDeploy));
        }

        servicesConfig.put(kernel.getMain().getName(), getMainConfig(rootPackages));

        // Services need to be under the services namespace in kernel config

        Map<Object, Object> resolved = Collections.singletonMap(SERVICES_NAMESPACE_TOPIC, servicesConfig);

        // Clear recipe cache to keep our memory usage lower
        recipeCache.clear();
        return resolved;
    }

    /*
     * Processes lifecycle section of each package and add it to the config.
     */
    private Map<Object, Object> getServiceConfig(PackageIdentifier packageIdentifier, DeploymentDocument document,
                                                 List<PackageIdentifier> packagesToDeploy)
            throws PackageLoadingException {

        PackageRecipe packageRecipe = getPackageRecipe(packageIdentifier);

        Map<Object, Object> resolvedServiceConfig = new HashMap<>();

        // Interpolate parameters
        Map<Object, Object> resolvedLifecycleConfig = new HashMap<>();
        Set<PackageParameter> resolvedParams = resolveParameterValuesToUse(document, packageRecipe);
        for (Map.Entry<String, Object> configKVPair : packageRecipe.getLifecycle().entrySet()) {
            resolvedLifecycleConfig.put(configKVPair.getKey(),
                    interpolate(configKVPair.getValue(), resolvedParams, packageIdentifier, packagesToDeploy,
                            document));
        }
        resolvedServiceConfig.put(EvergreenService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC, resolvedLifecycleConfig);

        if (!Utils.isEmpty(packageRecipe.getComponentType())) {
            resolvedServiceConfig.put(SERVICE_TYPE_TOPIC_KEY, packageRecipe.getComponentType());
        }

        // Generate dependencies
        List<String> dependencyConfig = new ArrayList<>();
        packageRecipe.getDependencies().forEach((name, prop) -> dependencyConfig
                .add(prop.getDependencyType() == null ? name : name + ":" + prop.getDependencyType()));
        resolvedServiceConfig.put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, dependencyConfig);

        // State information for deployments
        resolvedServiceConfig.put(VERSION_CONFIG_KEY, packageRecipe.getVersion().getValue());
        resolvedServiceConfig.put(PARAMETERS_CONFIG_KEY, resolvedParams.stream()
                .collect(Collectors.toMap(PackageParameter::getName, PackageParameter::getValue)));

        return resolvedServiceConfig;
    }

    private synchronized PackageRecipe getPackageRecipe(PackageIdentifier packageIdentifier)
            throws PackageLoadingException {
        if (!recipeCache.containsKey(packageIdentifier)) {
            recipeCache.put(packageIdentifier, packageStore.getPackageRecipe(packageIdentifier));
        }
        return recipeCache.get(packageIdentifier);
    }

    /*
     * For each lifecycle key-value pair of a package, substitute parameter values.
     */
    private Object interpolate(Object configValue, Set<PackageParameter> packageParameters,
                               PackageIdentifier packageIdentifier, List<PackageIdentifier> packagesToDeploy,
                               DeploymentDocument document) {
        Object result = configValue;

        if (configValue instanceof String) {
            result = replace((String) configValue, packageIdentifier, packageParameters, packagesToDeploy, document);
        }
        if (configValue instanceof Map) {
            Map<String, Object> childConfigMap = (Map<String, Object>) configValue;
            Map<Object, Object> resolvedChildConfig = new HashMap<>();
            for (Map.Entry<String, Object> childLifecycle : childConfigMap.entrySet()) {
                resolvedChildConfig.put(childLifecycle.getKey(),
                        interpolate(childLifecycle.getValue(), packageParameters, packageIdentifier, packagesToDeploy,
                                document));
            }
            result = resolvedChildConfig;
        }
        // TODO : Do we want to support other config types than map of
        // string k,v pairs? e.g. how should lists be handled?
        return result;
    }

    private String replace(String stringValue, PackageIdentifier packageIdentifier,
                           Set<PackageParameter> packageParameters, List<PackageIdentifier> packagesToDeploy,
                           DeploymentDocument document) {
        // Handle package parameters
        for (final PackageParameter parameter : packageParameters) {
            stringValue = stringValue
                    .replace(String.format(PARAMETER_REFERENCE_FORMAT, parameter.getName()), parameter.getValue());
        }

        // Handle system parameter replacement
        for (Map.Entry<String, Map<String, Function<PackageIdentifier, String>>> namespaceEntry : systemParameters
                .entrySet()) {
            for (Map.Entry<String, Function<PackageIdentifier, String>> keyEntry : namespaceEntry.getValue()
                    .entrySet()) {
                String toReplace = String.format(INTERPOLATION_FORMAT, namespaceEntry.getKey(), keyEntry.getKey());
                if (stringValue.contains(toReplace)) {
                    stringValue = stringValue.replace(toReplace, keyEntry.getValue().apply(packageIdentifier));
                }
            }
        }

        // Handle cross-component parameters
        Matcher matcher = CROSS_INTERPOLATION_REGEX.matcher(stringValue);

        while (matcher.find()) {
            String crossComponent = matcher.group(1);
            Optional<PackageIdentifier> crossComponentIdentifier =
                    packagesToDeploy.stream().filter(t -> t.getName().equals(crossComponent)).findFirst();

            if (crossComponentIdentifier.isPresent() && componentCanReadParameterFrom(packageIdentifier,
                    crossComponentIdentifier.get())) {
                String replacement = crossComponentLookup(document, crossComponentIdentifier.get(), matcher.group(2),
                        matcher.group(3));
                if (replacement != null) {
                    stringValue = stringValue.replace(matcher.group(), replacement);
                }
            }
        }

        return stringValue;
    }

    private boolean componentCanReadParameterFrom(PackageIdentifier component, PackageIdentifier canReadFrom) {
        try {
            PackageRecipe recipe = getPackageRecipe(component);
            return recipe.getDependencies().containsKey(canReadFrom.getName());
        } catch (PackageLoadingException e) {
            return false;
        }
    }

    @Nullable
    private String crossComponentLookup(DeploymentDocument document, PackageIdentifier crossedComponent,
                                        String namespace, String key) {
        // Handle cross-component system parameters
        Map<String, Function<PackageIdentifier, String>> systemParams =
                systemParameters.getOrDefault(namespace, Collections.emptyMap());
        if (systemParams.containsKey(key)) {
            return systemParams.get(key).apply(crossedComponent);
        }

        // Handle cross-component component parameters
        if (namespace.equals(PARAM_NAMESPACE)) {
            try {
                PackageRecipe packageRecipe = getPackageRecipe(crossedComponent);
                Set<PackageParameter> resolvedParams = resolveParameterValuesToUse(document, packageRecipe);
                Optional<PackageParameter> potentialParameter =
                        resolvedParams.stream().filter(p -> (p.getName() + PARAM_VALUE_SUFFIX).equals(key)).findFirst();
                if (potentialParameter.isPresent()) {
                    return potentialParameter.get().getValue();
                }
            } catch (PackageLoadingException e) {
                return null;
            }
        }
        return null;
    }


    /*
     * Compute the config for main service
     */
    private Map<Object, Object> getMainConfig(List<String> rootPackages) {
        Map<Object, Object> mainServiceConfig = new HashMap<>();
        ArrayList<String> mainDependencies = new ArrayList<>(rootPackages);
        kernel.getMain().getDependencies().forEach((evergreenService, dependencyType) -> {
            // Add all autostart dependencies
            if (evergreenService.isAutostart()) {
                mainDependencies.add(evergreenService.getName() + ":" + dependencyType);
            }
        });
        mainServiceConfig.put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, mainDependencies);
        return mainServiceConfig;
    }

    /*
     * Get configuration for a package-version combination from deployment document.
     */
    private Optional<DeploymentPackageConfiguration> getMatchingPackageConfigFromDeployment(DeploymentDocument document,
                                                                                            String packageName,
                                                                                            String packageVersion) {
        return document.getDeploymentPackageConfigurationList().stream()
                .filter(packageConfig -> packageName.equals(packageConfig.getPackageName()) && packageVersion
                        .equals(packageConfig.getResolvedVersion())).findAny();
    }

    /*
     * Resolve values to be used for all package parameters combining those coming from
     * deployment document, if not, those stored in the kernel config for previous
     * deployments and defaults for the rest.
     */
    private Set<PackageParameter> resolveParameterValuesToUse(DeploymentDocument document,
                                                              PackageRecipe packageRecipe) {
        // If values for parameters were set in deployment they should be used
        Set<PackageParameter> resolvedParams = new HashSet<>(getParametersFromDeployment(document, packageRecipe));

        // If not set in deployment, use values from previous deployments that were stored in config
        resolvedParams.addAll(getParametersStoredInConfig(packageRecipe));

        // Use defaults for parameters for which no values were set in current or previous deployment
        resolvedParams.addAll(packageRecipe.getPackageParameters());
        return resolvedParams;
    }

    /*
     * Get parameter values for a package set by customer from deployment document.
     */
    private Set<PackageParameter> getParametersFromDeployment(DeploymentDocument document,
                                                              PackageRecipe packageRecipe) {
        Optional<DeploymentPackageConfiguration> packageConfigInDeployment =
                getMatchingPackageConfigFromDeployment(document, packageRecipe.getComponentName(),
                        packageRecipe.getVersion().toString());
        return packageConfigInDeployment.map(deploymentPackageConfiguration -> PackageParameter
                .fromMap(deploymentPackageConfiguration.getConfiguration())).orElse(Collections.emptySet());
    }

    /*
     * Get parameter values for a package stored in config that were set by customer in previous deployment.
     */
    private Set<PackageParameter> getParametersStoredInConfig(PackageRecipe packageRecipe) {
        try {
            EvergreenService service = kernel.locate(packageRecipe.getComponentName());
            Set<PackageParameter> parametersStoredInConfig = new HashSet<>();

            // Get only those parameters which are still valid for the current version of the package
            packageRecipe.getPackageParameters().forEach(parameterFromRecipe -> {
                Optional<String> parameterValueStoredInConfig =
                        getParameterValueFromServiceConfig(service, parameterFromRecipe.getName());
                if (parameterValueStoredInConfig.isPresent()) {
                    parametersStoredInConfig
                            .add(new PackageParameter(parameterFromRecipe.getName(), parameterValueStoredInConfig.get(),
                                    parameterFromRecipe.getType()));
                }
            });
            return parametersStoredInConfig;
        } catch (ServiceLoadException e) {
            // Service does not exist in config i.e. is new
            return Collections.emptySet();
        }
    }

    /*
     * Lookup parameter value from service config by parameter name
     */
    private Optional<String> getParameterValueFromServiceConfig(EvergreenService service, String parameterName) {
        Topic parameterConfig = service.getServiceConfig().find(PARAMETERS_CONFIG_KEY, parameterName);
        return parameterConfig == null ? Optional.empty() : Optional.of(parameterConfig.getOnce().toString());
    }
}
