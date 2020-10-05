/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.ComponentParameter;
import com.aws.greengrass.componentmanager.models.ComponentRecipe;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentPackageConfiguration;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.CrashableFunction;
import com.aws.greengrass.util.Pair;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.inject.Inject;

import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.Kernel.SERVICE_TYPE_TOPIC_KEY;

public class KernelConfigResolver {
    private static final Logger LOGGER = LogManager.getLogger(KernelConfigResolver.class);
    public static final String VERSION_CONFIG_KEY = "version";
    public static final String PARAMETERS_CONFIG_KEY = "parameters";
    static final String ARTIFACTS_NAMESPACE = "artifacts";
    static final String KERNEL_NAMESPACE = "kernel";
    static final String KERNEL_ROOT_PATH = "rootPath";
    private static final String WORD_GROUP = "([\\.\\w]+)";
    // Pattern matches {{otherComponentName:parameterNamespace:parameterKey}}
    private static final Pattern CROSS_INTERPOLATION_REGEX =
            Pattern.compile("\\{\\{" + WORD_GROUP + ":" + WORD_GROUP + ":" + WORD_GROUP + "}}");
    private static final Pattern SAME_INTERPOLATION_REGEX =
            Pattern.compile("\\{\\{" + WORD_GROUP + ":" + WORD_GROUP + "}}");
    static final String PARAM_NAMESPACE = "params";
    static final String PARAM_VALUE_SUFFIX = ".value";
    static final String PATH_KEY = "path";
    static final String DECOMPRESSED_PATH_KEY = "decompressedPath";

    private static final String NO_RECIPE_ERROR_FORMAT = "Failed to find component recipe for {}";

    // Map from Namespace -> Key -> Function which returns the replacement value
    private final Map<String, Map<String, CrashableFunction<ComponentIdentifier, String, PackageLoadingException>>>
            systemParameters = new HashMap<>();

    private final ComponentStore componentStore;
    private final Kernel kernel;

    /**
     * Constructor.
     *
     * @param componentStore package store used to look up packages
     * @param kernel       kernel
     */
    @Inject
    public KernelConfigResolver(ComponentStore componentStore, Kernel kernel) {
        this.componentStore = componentStore;
        this.kernel = kernel;

        // More system parameters can be added over time by extending this map with new namespaces/keys
        HashMap<String, CrashableFunction<ComponentIdentifier, String, PackageLoadingException>> artifactNamespace
                = new HashMap<>();
        artifactNamespace.put(PATH_KEY,
                (id) -> componentStore.resolveArtifactDirectoryPath(id).toAbsolutePath().toString());
        artifactNamespace.put(DECOMPRESSED_PATH_KEY,
                (id) -> componentStore.resolveAndSetupArtifactsDecompressedDirectory(id).toAbsolutePath().toString());
        systemParameters.put(ARTIFACTS_NAMESPACE, artifactNamespace);

        HashMap<String, CrashableFunction<ComponentIdentifier, String, PackageLoadingException>> kernelNamespace
                = new HashMap<>();
        kernelNamespace.put(KERNEL_ROOT_PATH, (id) -> kernel.getRootPath().toAbsolutePath().toString());
        systemParameters.put(KERNEL_NAMESPACE, kernelNamespace);
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
    public Map<String, Object> resolve(List<ComponentIdentifier> packagesToDeploy, DeploymentDocument document,
                                       List<String> rootPackages) throws PackageLoadingException {
        Map<ComponentIdentifier, Pair<Set<ComponentParameter>, Set<String>>> parameterAndDependencyCache =
                new ConcurrentHashMap<>();
        Map<String, Object> servicesConfig = new HashMap<>();
        for (ComponentIdentifier packageToDeploy : packagesToDeploy) {
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
    private Map<String, Object> getServiceConfig(ComponentIdentifier componentIdentifier, DeploymentDocument document,
                                                 List<ComponentIdentifier> packagesToDeploy,
                                                 Map<ComponentIdentifier, Pair<Set<ComponentParameter>, Set<String>>>
                                                         parameterAndDependencyCache)
            throws PackageLoadingException {
        ComponentRecipe componentRecipe = componentStore.getPackageRecipe(componentIdentifier);

        Set<ComponentParameter> resolvedParams = resolveParameterValuesToUse(document, componentRecipe);
        parameterAndDependencyCache
                .put(componentIdentifier, new Pair<>(resolvedParams, componentRecipe.getDependencies().keySet()));

        Map<String, Object> resolvedServiceConfig = new HashMap<>();

        // Interpolate parameters
        resolvedServiceConfig.put(GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC,
                interpolate(componentRecipe.getLifecycle(), componentIdentifier, packagesToDeploy, document,
                        parameterAndDependencyCache));

        resolvedServiceConfig.put(SERVICE_TYPE_TOPIC_KEY,
                componentRecipe.getComponentType() == null ? null : componentRecipe.getComponentType().name());

        // Generate dependencies
        List<String> dependencyConfig = new ArrayList<>();
        componentRecipe.getDependencies().forEach((name, prop) -> dependencyConfig
                .add(prop.getDependencyType() == null ? name : name + ":" + prop.getDependencyType()));
        resolvedServiceConfig.put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, dependencyConfig);

        // State information for deployments
        resolvedServiceConfig.put(VERSION_CONFIG_KEY, componentRecipe.getVersion().getValue());
        Map<String, String> map = new HashMap<>();
        for (ComponentParameter resolvedParam : resolvedParams) {
            map.put(resolvedParam.getName(), resolvedParam.getValue());
        }
        resolvedServiceConfig.put(PARAMETERS_CONFIG_KEY, map);

        return resolvedServiceConfig;
    }

    /*
     * For each lifecycle key-value pair of a package, substitute parameter values.
     */
    private Object interpolate(Object configValue, ComponentIdentifier componentIdentifier,
                               List<ComponentIdentifier> packagesToDeploy, DeploymentDocument document,
                               Map<ComponentIdentifier, Pair<Set<ComponentParameter>, Set<String>>>
                                       parameterAndDependencyCache) throws PackageLoadingException {
        Object result = configValue;

        if (configValue instanceof String) {
            result = replace((String) configValue, componentIdentifier, packagesToDeploy, document,
                    parameterAndDependencyCache);
        }
        if (configValue instanceof Map) {
            Map<String, Object> childConfigMap = (Map<String, Object>) configValue;
            Map<String, Object> resolvedChildConfig = new HashMap<>();
            for (Entry<String, Object> childLifecycle : childConfigMap.entrySet()) {
                resolvedChildConfig.put(childLifecycle.getKey(),
                        interpolate(childLifecycle.getValue(), componentIdentifier, packagesToDeploy, document,
                                parameterAndDependencyCache));
            }
            result = resolvedChildConfig;
        }
        // TODO : Do we want to support other config types than map of
        // string k,v pairs? e.g. how should lists be handled?
        return result;
    }

    private String replace(String stringValue, ComponentIdentifier componentIdentifier,
                           List<ComponentIdentifier> packagesToDeploy, DeploymentDocument document,
                           Map<ComponentIdentifier, Pair<Set<ComponentParameter>, Set<String>>>
                                   parameterAndDependencyCache) throws PackageLoadingException {
        // Handle some-component parameters
        Matcher matcher = SAME_INTERPOLATION_REGEX.matcher(stringValue);
        while (matcher.find()) {
            String replacement =
                    lookupParameterValueForComponent(parameterAndDependencyCache, document, componentIdentifier,
                            matcher.group(1), matcher.group(2));
            if (replacement != null) {
                stringValue = stringValue.replace(matcher.group(), replacement);
            }
        }

        // Handle cross-component parameters
        matcher = CROSS_INTERPOLATION_REGEX.matcher(stringValue);

        while (matcher.find()) {
            String crossComponent = matcher.group(1);
            Optional<ComponentIdentifier> crossComponentIdentifier =
                    packagesToDeploy.stream().filter(t -> t.getName().equals(crossComponent)).findFirst();

            if (crossComponentIdentifier.isPresent() && componentCanReadParameterFrom(componentIdentifier,
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

    private boolean componentCanReadParameterFrom(ComponentIdentifier component, ComponentIdentifier canReadFrom,
                                                  Map<ComponentIdentifier, Pair<Set<ComponentParameter>, Set<String>>>
                                                          parameterAndDependencyCache) {
        Set<String> depSet;
        if (parameterAndDependencyCache.containsKey(component)
                && parameterAndDependencyCache.get(component).getRight() != null) {
            depSet = parameterAndDependencyCache.get(component).getRight();
        } else {
            try {
                ComponentRecipe recipe = componentStore.getPackageRecipe(component);
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
            Map<ComponentIdentifier, Pair<Set<ComponentParameter>, Set<String>>> parameterAndDependencyCache,
            DeploymentDocument document, ComponentIdentifier component, String namespace, String key)
            throws PackageLoadingException {
        // Handle cross-component system parameters
        Map<String, CrashableFunction<ComponentIdentifier, String, PackageLoadingException>> systemParams =
                systemParameters.getOrDefault(namespace, Collections.emptyMap());
        if (systemParams.containsKey(key)) {
            return systemParams.get(key).apply(component);
        }

        // Handle component parameters
        if (namespace.equals(PARAM_NAMESPACE)) {
            try {
                Set<ComponentParameter> resolvedParams =
                        resolveParameterValuesToUseWithCache(parameterAndDependencyCache, component, document);
                Optional<ComponentParameter> potentialParameter =
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
    private Map<String, Object> getMainConfig(List<String> rootPackages) {
        Map<String, Object> mainServiceConfig = new HashMap<>();
        ArrayList<String> mainDependencies = new ArrayList<>(rootPackages);
        kernel.getMain().getDependencies().forEach((greengrassService, dependencyType) -> {
            // Add all autostart dependencies
            if (greengrassService.isBuiltin()) {
                mainDependencies.add(greengrassService.getName() + ":" + dependencyType);
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
                       .filter(packageConfig ->
                               packageName.equals(packageConfig.getPackageName())
                                       // TODO packageConfig.getResolvedVersion() should be strongly typed when created
                                       && Requirement.buildNPM(packageConfig.getResolvedVersion())
                                                     .isSatisfiedBy(new Semver(packageVersion, Semver.SemverType.NPM)))
                       .findAny();
    }

    private Set<ComponentParameter> resolveParameterValuesToUseWithCache(
            Map<ComponentIdentifier, Pair<Set<ComponentParameter>, Set<String>>> parameterAndDependencyCache,
            ComponentIdentifier componentIdentifier, DeploymentDocument document) throws PackageLoadingException {
        if (parameterAndDependencyCache.containsKey(componentIdentifier)
                && parameterAndDependencyCache.get(componentIdentifier).getLeft() != null) {
            return parameterAndDependencyCache.get(componentIdentifier).getLeft();
        }
        return resolveParameterValuesToUse(document, componentStore.getPackageRecipe(componentIdentifier));
    }

    /*
     * Resolve values to be used for all package parameters combining those coming from
     * deployment document, if not, those stored in the kernel config for previous
     * deployments and defaults for the rest.
     */
    private Set<ComponentParameter> resolveParameterValuesToUse(DeploymentDocument document,
                                                                ComponentRecipe componentRecipe) {
        // If values for parameters were set in deployment they should be used
        Set<ComponentParameter> resolvedParams = new HashSet<>(getParametersFromDeployment(document, componentRecipe));

        // If not set in deployment, use values from previous deployments that were stored in config
        resolvedParams.addAll(getParametersStoredInConfig(componentRecipe));

        // Use defaults for parameters for which no values were set in current or previous deployment
        resolvedParams.addAll(componentRecipe.getComponentParameters());
        return resolvedParams;
    }

    /*
     * Get parameter values for a package set by customer from deployment document.
     */
    private Set<ComponentParameter> getParametersFromDeployment(DeploymentDocument document,
                                                                ComponentRecipe componentRecipe) {
        Optional<DeploymentPackageConfiguration> packageConfigInDeployment =
                getMatchingPackageConfigFromDeployment(document, componentRecipe.getComponentName(),
                        componentRecipe.getVersion().toString());
        return packageConfigInDeployment.map(deploymentPackageConfiguration -> ComponentParameter
                .fromMap(deploymentPackageConfiguration.getConfiguration())).orElse(Collections.emptySet());
    }

    /*
     * Get parameter values for a package stored in config that were set by customer in previous deployment.
     */
    private Set<ComponentParameter> getParametersStoredInConfig(ComponentRecipe componentRecipe) {
        Set<ComponentParameter> parametersStoredInConfig = new HashSet<>();

        // Get only those parameters which are still valid for the current version of the package
        componentRecipe.getComponentParameters().forEach(parameterFromRecipe -> {
            Optional<String> parameterValueStoredInConfig =
                    getParameterValueFromServiceConfig(componentRecipe.getComponentName(),
                            parameterFromRecipe.getName());
            parameterValueStoredInConfig.ifPresent(s -> parametersStoredInConfig
                    .add(new ComponentParameter(parameterFromRecipe.getName(), s, parameterFromRecipe.getType())));
        });
        return parametersStoredInConfig;
    }

    /*
     * Lookup parameter value from service config by parameter name
     */
    private Optional<String> getParameterValueFromServiceConfig(String service, String parameterName) {
        Topics serviceTopics = kernel.findServiceTopic(service);
        if (serviceTopics == null) {
            return Optional.empty();
        }
        Topic parameterConfig = serviceTopics.find(PARAMETERS_CONFIG_KEY, parameterName);
        return parameterConfig == null ? Optional.empty() : Optional.ofNullable(Coerce.toString(parameterConfig));
    }
}
