/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentPackageConfiguration;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageLoadingException;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.models.PackageParameter;
import com.aws.iot.evergreen.packagemanager.models.PackageRecipe;
import com.aws.iot.evergreen.util.Pair;
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
    private static final Logger LOGGER = LogManager.getLogger(KernelConfigResolver.class);
    public static final String VERSION_CONFIG_KEY = "version";
    public static final String PARAMETERS_CONFIG_KEY = "parameters";
    private static final String WORD_GROUP = "([\\.\\w]+)";
    // Pattern matches {{otherComponentName:parameterNamespace:parameterKey}}
    private static final Pattern CROSS_INTERPOLATION_REGEX =
            Pattern.compile("\\{\\{" + WORD_GROUP + ":" + WORD_GROUP + ":" + WORD_GROUP + "}}");
    private static final Pattern SAME_INTERPOLATION_REGEX =
            Pattern.compile("\\{\\{" + WORD_GROUP + ":" + WORD_GROUP + "}}");
    static final String PARAM_NAMESPACE = "params";
    static final String PARAM_VALUE_SUFFIX = ".value";
    static final String ARTIFACTS_NAMESPACE = "artifacts";
    static final String PATH_KEY = "path";
    private static final String NO_RECIPE_ERROR_FORMAT = "Failed to find component recipe for {}";
    // Map from Namespace -> Key -> Function which returns the replacement value
    private final Map<String, Map<String, Function<PackageIdentifier, String>>> systemParameters = new HashMap<>();

    private final PackageStore packageStore;
    private final Kernel kernel;

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
                .put(PATH_KEY, (id) -> packageStore.resolveArtifactDirectoryPath(id).toAbsolutePath().toString());
        systemParameters.put(ARTIFACTS_NAMESPACE, artifactNamespace);
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
        Map<PackageIdentifier, Pair<Set<PackageParameter>, Set<String>>> parameterAndDependencyCache =
                new ConcurrentHashMap<>();
        Map<Object, Object> servicesConfig = new HashMap<>();
        for (PackageIdentifier packageToDeploy : packagesToDeploy) {
            servicesConfig.put(packageToDeploy.getName(),
                    getServiceConfig(packageToDeploy, document, packagesToDeploy, parameterAndDependencyCache));
        }

        servicesConfig.put(kernel.getMain().getName(), getMainConfig(rootPackages));

        // Services need to be under the services namespace in kernel config
        return Collections.singletonMap(SERVICES_NAMESPACE_TOPIC, servicesConfig);
    }

    /*
     * Processes lifecycle section of each package and add it to the config.
     */
    private Map<Object, Object> getServiceConfig(PackageIdentifier packageIdentifier, DeploymentDocument document,
                                                 List<PackageIdentifier> packagesToDeploy,
                                                 Map<PackageIdentifier, Pair<Set<PackageParameter>, Set<String>>>
                                                         parameterAndDependencyCache)
            throws PackageLoadingException {
        PackageRecipe packageRecipe = packageStore.getPackageRecipe(packageIdentifier);

        Set<PackageParameter> resolvedParams = resolveParameterValuesToUse(document, packageRecipe);
        parameterAndDependencyCache
                .put(packageIdentifier, new Pair<>(resolvedParams, packageRecipe.getDependencies().keySet()));

        Map<Object, Object> resolvedServiceConfig = new HashMap<>();

        // Interpolate parameters
        resolvedServiceConfig.put(EvergreenService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC,
                interpolate(packageRecipe.getLifecycle(), packageIdentifier, packagesToDeploy, document,
                        parameterAndDependencyCache));

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

    /*
     * For each lifecycle key-value pair of a package, substitute parameter values.
     */
    private Object interpolate(Object configValue, PackageIdentifier packageIdentifier,
                               List<PackageIdentifier> packagesToDeploy, DeploymentDocument document,
                               Map<PackageIdentifier, Pair<Set<PackageParameter>, Set<String>>>
                                       parameterAndDependencyCache) {
        Object result = configValue;

        if (configValue instanceof String) {
            result = replace((String) configValue, packageIdentifier, packagesToDeploy, document,
                    parameterAndDependencyCache);
        }
        if (configValue instanceof Map) {
            Map<String, Object> childConfigMap = (Map<String, Object>) configValue;
            Map<Object, Object> resolvedChildConfig = new HashMap<>();
            for (Map.Entry<String, Object> childLifecycle : childConfigMap.entrySet()) {
                resolvedChildConfig.put(childLifecycle.getKey(),
                        interpolate(childLifecycle.getValue(), packageIdentifier, packagesToDeploy, document,
                                parameterAndDependencyCache));
            }
            result = resolvedChildConfig;
        }
        // TODO : Do we want to support other config types than map of
        // string k,v pairs? e.g. how should lists be handled?
        return result;
    }

    private String replace(String stringValue, PackageIdentifier packageIdentifier,
                           List<PackageIdentifier> packagesToDeploy, DeploymentDocument document,
                           Map<PackageIdentifier, Pair<Set<PackageParameter>, Set<String>>>
                                   parameterAndDependencyCache) {
        // Handle some-component parameters
        Matcher matcher = SAME_INTERPOLATION_REGEX.matcher(stringValue);
        while (matcher.find()) {
            String replacement =
                    lookupParameterValueForComponent(parameterAndDependencyCache, document, packageIdentifier,
                            matcher.group(1), matcher.group(2));
            if (replacement != null) {
                stringValue = stringValue.replace(matcher.group(), replacement);
            }
        }

        // Handle cross-component parameters
        matcher = CROSS_INTERPOLATION_REGEX.matcher(stringValue);

        while (matcher.find()) {
            String crossComponent = matcher.group(1);
            Optional<PackageIdentifier> crossComponentIdentifier =
                    packagesToDeploy.stream().filter(t -> t.getName().equals(crossComponent)).findFirst();

            if (crossComponentIdentifier.isPresent() && componentCanReadParameterFrom(packageIdentifier,
                    crossComponentIdentifier.get(), parameterAndDependencyCache)) {
                String replacement = lookupParameterValueForComponent(parameterAndDependencyCache, document,
                        crossComponentIdentifier.get(), matcher.group(2), matcher.group(3));
                if (replacement != null) {
                    stringValue = stringValue.replace(matcher.group(), replacement);
                }
            }
        }

        return stringValue;
    }

    private boolean componentCanReadParameterFrom(PackageIdentifier component, PackageIdentifier canReadFrom,
                                                  Map<PackageIdentifier, Pair<Set<PackageParameter>, Set<String>>>
                                                          parameterAndDependencyCache) {
        Set<String> depSet;
        if (parameterAndDependencyCache.containsKey(component)
                && parameterAndDependencyCache.get(component).getRight() != null) {
            depSet = parameterAndDependencyCache.get(component).getRight();
        } else {
            try {
                PackageRecipe recipe = packageStore.getPackageRecipe(component);
                return recipe.getDependencies().containsKey(canReadFrom.getName());
            } catch (PackageLoadingException e) {
                LOGGER.atWarn().log(NO_RECIPE_ERROR_FORMAT, component, e);
                return false;
            }
        }
        return depSet.contains(canReadFrom.getName());
    }

    @Nullable
    private String lookupParameterValueForComponent(
            Map<PackageIdentifier, Pair<Set<PackageParameter>, Set<String>>> parameterAndDependencyCache,
            DeploymentDocument document, PackageIdentifier component, String namespace, String key) {
        // Handle cross-component system parameters
        Map<String, Function<PackageIdentifier, String>> systemParams =
                systemParameters.getOrDefault(namespace, Collections.emptyMap());
        if (systemParams.containsKey(key)) {
            return systemParams.get(key).apply(component);
        }

        // Handle component parameters
        if (namespace.equals(PARAM_NAMESPACE)) {
            try {
                Set<PackageParameter> resolvedParams =
                        resolveParameterValuesToUseWithCache(parameterAndDependencyCache, component, document);
                Optional<PackageParameter> potentialParameter =
                        resolvedParams.stream().filter(p -> (p.getName() + PARAM_VALUE_SUFFIX).equals(key)).findFirst();
                if (potentialParameter.isPresent()) {
                    return potentialParameter.get().getValue();
                }
            } catch (PackageLoadingException e) {
                LOGGER.atWarn().log(NO_RECIPE_ERROR_FORMAT, component, e);
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

    private Set<PackageParameter> resolveParameterValuesToUseWithCache(
            Map<PackageIdentifier, Pair<Set<PackageParameter>, Set<String>>> parameterAndDependencyCache,
            PackageIdentifier packageIdentifier, DeploymentDocument document) throws PackageLoadingException {
        if (parameterAndDependencyCache.containsKey(packageIdentifier)
                && parameterAndDependencyCache.get(packageIdentifier).getLeft() != null) {
            return parameterAndDependencyCache.get(packageIdentifier).getLeft();
        }
        return resolveParameterValuesToUse(document, packageStore.getPackageRecipe(packageIdentifier));
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
                parameterValueStoredInConfig.ifPresent(s -> parametersStoredInConfig
                        .add(new PackageParameter(parameterFromRecipe.getName(), s, parameterFromRecipe.getType())));
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
