/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.amazon.aws.iot.greengrass.component.common.ComponentConfiguration;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.ComponentParameter;
import com.aws.greengrass.componentmanager.models.ComponentRecipe;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.model.ConfigurationUpdateOperation;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentPackageConfiguration;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.CrashableFunction;
import com.aws.greengrass.util.Pair;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;

import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
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

    // pattern matches {group1:group2}. Note char in both group can't be }, but can be special char like / and .
    // ex. {configuration:/singleLevelKey}
    private static final Pattern SAME_COMPONENT_INTERPOLATION_REGEX = Pattern.compile("\\{([^}]+):([^}]+)}");

    // pattern matches {group1:group2:group3}. Note char in both group can't be }, but can be special char like / and .
    // ex. {ComponentConfigurationTestService:configuration:/singleLevelKey}
    private static final Pattern CROSS_COMPONENT_INTERPOLATION_REGEX = Pattern.compile("\\{([^}]+):([^}]+):([^}]+)}");

    static final String PARAM_NAMESPACE = "params";
    static final String CONFIGURATION_NAMESPACE = "configuration";
    static final String PARAM_VALUE_SUFFIX = ".value";
    static final String PATH_KEY = "path";
    static final String DECOMPRESSED_PATH_KEY = "decompressedPath";

    private static final String NO_RECIPE_ERROR_FORMAT = "Failed to find component recipe for {}";
    private static final String CONFIGURATIONS_CONFIG_KEY = "Configurations";

    // https://tools.ietf.org/html/rfc6901#section-5
    private static final String JSON_POINTER_WHOLE_DOC = "";

    // Map from Namespace -> Key -> Function which returns the replacement value
    private final Map<String, Map<String, CrashableFunction<ComponentIdentifier, String, PackageLoadingException>>>
            systemParameters = new HashMap<>();

    private final ComponentStore componentStore;
    private final Kernel kernel;


    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Constructor.
     *
     * @param componentStore package store used to look up packages
     * @param kernel         kernel
     */
    @Inject
    public KernelConfigResolver(ComponentStore componentStore, Kernel kernel) {
        this.componentStore = componentStore;
        this.kernel = kernel;

        // More system parameters can be added over time by extending this map with new namespaces/keys
        HashMap<String, CrashableFunction<ComponentIdentifier, String, PackageLoadingException>> artifactNamespace =
                new HashMap<>();
        artifactNamespace.put(PATH_KEY,
                (id) -> componentStore.resolveArtifactDirectoryPath(id).toAbsolutePath().toString());
        artifactNamespace.put(DECOMPRESSED_PATH_KEY,
                (id) -> componentStore.resolveAndSetupArtifactsDecompressedDirectory(id).toAbsolutePath().toString());
        systemParameters.put(ARTIFACTS_NAMESPACE, artifactNamespace);

        HashMap<String, CrashableFunction<ComponentIdentifier, String, PackageLoadingException>> kernelNamespace =
                new HashMap<>();
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

        Map<ComponentIdentifier, Pair<Map, Set<String>>> configAndDependencyCache = new ConcurrentHashMap<>();

        Map<String, Object> servicesConfig = new HashMap<>();

        for (ComponentIdentifier packageToDeploy : packagesToDeploy) {
            servicesConfig.put(packageToDeploy.getName(),
                    getServiceConfig(packageToDeploy, document, packagesToDeploy, parameterAndDependencyCache,
                            configAndDependencyCache));
        }

        // Interpolate configurations
        for (ComponentIdentifier packageToDeploy : packagesToDeploy) {
            ComponentRecipe componentRecipe = componentStore.getPackageRecipe(packageToDeploy);

            Object existingLifecycle =
                    ((Map) servicesConfig.get(packageToDeploy.getName())).get(SERVICE_LIFECYCLE_NAMESPACE_TOPIC);

            ((Map) servicesConfig.get(packageToDeploy.getName())).put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC,
                    interpolate(existingLifecycle, packageToDeploy,
                            new ArrayList<>(componentRecipe.getDependencies().keySet()), servicesConfig));
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
                                                 Map<ComponentIdentifier, Pair<Set<ComponentParameter>, Set<String>>> parameterAndDependencyCache,
                                                 Map<ComponentIdentifier, Pair<Map, Set<String>>> configAndDependencyCache)
            throws PackageLoadingException {
        ComponentRecipe componentRecipe = componentStore.getPackageRecipe(componentIdentifier);

        Set<ComponentParameter> resolvedParams = resolveParameterValuesToUse(document, componentRecipe);
        parameterAndDependencyCache.put(componentIdentifier,
                new Pair<>(resolvedParams, componentRecipe.getDependencies().keySet()));


        Optional<DeploymentPackageConfiguration> operationOptional = document.getDeploymentPackageConfigurationList()
                                                                             .stream()
                                                                             .filter(e -> e.getPackageName()
                                                                                           .equals(componentRecipe.getComponentName()))
                                                                             // TODO version check
                                                                             .findAny();

        Map<String, Object> resolvedConfiguration = resolveConfigurationToApply(
                operationOptional.map(DeploymentPackageConfiguration::getConfigurationUpdateOperation).orElse(null),
                componentRecipe);

//        configAndDependencyCache.put(componentIdentifier,
//                new Pair<>(resolvedConfiguration, componentRecipe.getDependencies().keySet()));

        Map<String, Object> resolvedServiceConfig = new HashMap<>();

        // Interpolate parameters
        resolvedServiceConfig.put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC,
                interpolate(componentRecipe.getLifecycle(), componentIdentifier, packagesToDeploy, document,
                        parameterAndDependencyCache, configAndDependencyCache));

        resolvedServiceConfig.put(SERVICE_TYPE_TOPIC_KEY,
                componentRecipe.getComponentType() == null ? null : componentRecipe.getComponentType().name());

        // Generate dependencies
        List<String> dependencyConfig = new ArrayList<>();
        componentRecipe.getDependencies()
                       .forEach((name, prop) -> dependencyConfig.add(
                               prop.getDependencyType() == null ? name : name + ":" + prop.getDependencyType()));
        resolvedServiceConfig.put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, dependencyConfig);

        // State information for deployments
        resolvedServiceConfig.put(VERSION_CONFIG_KEY, componentRecipe.getVersion().getValue());
        Map<String, String> map = new HashMap<>();
        for (ComponentParameter resolvedParam : resolvedParams) {
            map.put(resolvedParam.getName(), resolvedParam.getValue());
        }
        resolvedServiceConfig.put(PARAMETERS_CONFIG_KEY, map);

        resolvedServiceConfig.put(CONFIGURATIONS_CONFIG_KEY,
                resolvedConfiguration == null ? new HashMap<>() : resolvedConfiguration);

        return resolvedServiceConfig;
    }

    @SuppressWarnings("rawtypes")
    private Map<String, Object> resolveConfigurationToApply(ConfigurationUpdateOperation configurationUpdateOperation,
                                                            ComponentRecipe componentRecipe) {

        // try read the running service config
        Map<String, Object> currentRunningConfig = null;

        Topics serviceTopics = kernel.findServiceTopic(componentRecipe.getComponentName());
        if (serviceTopics != null) {
            Topics configuration = serviceTopics.lookupTopics(CONFIGURATIONS_CONFIG_KEY);
            if (configuration != null) {
                currentRunningConfig = configuration.toPOJO();
            }
        }

        // get default config
        JsonNode defaultConfig = Optional.ofNullable(componentRecipe.getComponentConfiguration())
                                         .map(ComponentConfiguration::getDefaultConfiguration)
                                         .orElse(mapper.createObjectNode()); // init null to be empty default config

        // deal with no update
        if (configurationUpdateOperation == null) {
            if (currentRunningConfig != null) {
                // no update but there is running config, so it should return running config as is.
                return currentRunningConfig;
            } else {
                // no update nor running config, so it should return return the default config.
                return mapper.convertValue(defaultConfig, Map.class);
            }
        }

        // deal with update
        return applyUpdateToCurrentConfig(currentRunningConfig, configurationUpdateOperation, defaultConfig);
    }

    @SuppressWarnings("rawtypes")
    private Map applyUpdateToCurrentConfig(Map<String, Object> currentRunningConfig,
                                           ConfigurationUpdateOperation configurationUpdateOperation,
                                           JsonNode defaultConfiguration) {

        // initialize to empty map if null because we will use this map as the base.
        if (currentRunningConfig == null) {
            currentRunningConfig = new HashMap<>();
        }

        // perform RESET first
        currentRunningConfig =
                reset(currentRunningConfig, defaultConfiguration, configurationUpdateOperation.getPathsToReset());

        // perform MERGE secondly
        deepMerge(currentRunningConfig, configurationUpdateOperation.getValueToMerge());

        return currentRunningConfig;

    }

    @SuppressWarnings("rawtypes")
    private Map reset(Map original, JsonNode defaultValue, List<String> pathsToReset) {
        if (pathsToReset == null || pathsToReset.isEmpty()) {
            return original;
        }

        // convert to JsonNode for path navigation
        JsonNode node = mapper.convertValue(original, JsonNode.class);

        for (String pointer : pathsToReset) {
            // special case handling for reset whole document
            if (pointer.equals(JSON_POINTER_WHOLE_DOC)) {
                // reset to entire default value node and return because there is no need to process further
                return mapper.convertValue(defaultValue, Map.class);
            }

            // regular pointer handling
            JsonPointer jsonPointer = JsonPointer.compile(pointer);

            JsonNode targetNode = defaultValue.at(jsonPointer);

            if ((targetNode.isMissingNode())) {
                // missing default value -> remove the entry completely
                // note: remove, rather than setting to null.
                ((ObjectNode) node.at(jsonPointer.head())).remove(jsonPointer.getMatchingProperty());

            } else {
                // target is container node, or a value node, including null node -> replace the entry
                ((ObjectNode) node.at(jsonPointer.head())).replace(jsonPointer.getMatchingProperty(), targetNode);
            }
        }


        return mapper.convertValue(node, Map.class);
    }

    @SuppressWarnings("rawtypes")
    private static Map deepMerge(@Nonnull Map original, Map newMap) {
        if (newMap == null || newMap.isEmpty()) {
            return original;
        }

        for (Object key : newMap.keySet()) {
            if (newMap.get(key) instanceof Map && original.get(key) instanceof Map) {
                // if both are container node, recursively deep merge for children
                Map originalChild = (Map) original.get(key);
                Map newChild = (Map) newMap.get(key);
                original.put(key, deepMerge(originalChild, newChild));
            } else {
                // This branch supports container node -> value node and vice versa as it just overrides.
                // This branch also handles the list with entire replacement.
                // Note: There is no support for list append or insert at index operations.
                original.put(key, newMap.get(key));
            }
        }
        return original;
    }

    /*
     * For each lifecycle key-value pair of a package, substitute parameter values.
     */
    private Object interpolate(Object configValue, ComponentIdentifier componentIdentifier, List<String> dependencies,
                               Map enitreResolvedConfig) throws PackageLoadingException {
        Object result = configValue;

        if (configValue instanceof String) {
            result = replace((String) configValue, componentIdentifier, dependencies, enitreResolvedConfig);
        }
        if (configValue instanceof Map) {
            Map<String, Object> childConfigMap = (Map<String, Object>) configValue;
            Map<String, Object> resolvedChildConfig = new HashMap<>();
            for (Entry<String, Object> childLifecycle : childConfigMap.entrySet()) {
                resolvedChildConfig.put(childLifecycle.getKey(),
                        interpolate(childLifecycle.getValue(), componentIdentifier, dependencies, enitreResolvedConfig));
            }
            result = resolvedChildConfig;
        }

        // No list handling because lists are outlawed under "Lifecycle" key

        return result;
    }

    private String lookupSystemConfig(ComponentIdentifier component, String namespace, String key)
            throws PackageLoadingException {
        // Handle system-wide configuration
        Map<String, CrashableFunction<ComponentIdentifier, String, PackageLoadingException>> systemParams =
                systemParameters.getOrDefault(namespace, Collections.emptyMap());
        if (systemParams.containsKey(key)) {
            return systemParams.get(key).apply(component);
        }
        return null;
    }

    private String replace(String stringValue, ComponentIdentifier componentIdentifier, List<String> dependencies,
                           Map resolvedConfig) {

        Matcher matcher;

        // Handle same-component interpolation
        matcher = SAME_COMPONENT_INTERPOLATION_REGEX.matcher(stringValue);

        while (matcher.find()) {
            String namespace = matcher.group(1);
            String key = matcher.group(2);

            if (namespace.equals(CONFIGURATION_NAMESPACE)) {

                // TODO safety check
                Map componentResolvedConfig =
                        (Map) ((Map) resolvedConfig.get(componentIdentifier.getName())).get(CONFIGURATIONS_CONFIG_KEY);
                String configReplacement = lookupConfigurationValue(componentResolvedConfig, matcher.group(2));
                if (configReplacement != null) {
                    stringValue = stringValue.replace(matcher.group(), configReplacement);
                }

            } else {
                // handle system config
                //                String configReplacement = lookupSystemConfig(componentIdentifier, namespace, key);
                //                if (configReplacement != null) {
                //                    stringValue = stringValue.replace(matcher.group(), configReplacement);
                //                }
            }

        }

        // Handle cross-component configuration

        // example {ComponentConfigurationTestService:configuration:/singleLevelKey}
        matcher = CROSS_COMPONENT_INTERPOLATION_REGEX.matcher(stringValue);

        while (matcher.find()) {

            String targetComponent = matcher.group(1);
            String namespace = matcher.group(2);
            String key = matcher.group(3);

            if (namespace.equals(CONFIGURATION_NAMESPACE)) {

                // dependency check
                if (!dependencies.contains(targetComponent)) {
                    // TODO more
                    LOGGER.atError().log("Don't interpolate because it's not a direct depenedency ");
                    continue;
                }

                // check if in the current resolved config
                if (resolvedConfig.containsKey(targetComponent)) {
                    if (((Map) resolvedConfig.get(targetComponent)).containsKey(CONFIGURATIONS_CONFIG_KEY)) {
                        Map componentResolvedConfig =
                                (Map) ((Map) resolvedConfig.get(targetComponent)).get(CONFIGURATIONS_CONFIG_KEY);
                        String replacement = lookupConfigurationValue(componentResolvedConfig, key);
                        if (replacement != null) {
                            stringValue = stringValue.replace(matcher.group(), replacement);

                            continue;
                            // exit
                        }
                    }
                }

                // else load from running config
                Topics serviceTopics = kernel.findServiceTopic(targetComponent);
                if (serviceTopics != null) {
                    Topics configuration = serviceTopics.findTopics(CONFIGURATIONS_CONFIG_KEY);
                    if (configuration != null) {
                        String replacement = lookupConfigurationValue(configuration.toPOJO(), key);
                        if (replacement != null) {
                            stringValue = stringValue.replace(matcher.group(), replacement);
                            continue;
                            // exit
                        }
                    }
                }

                LOGGER.atError().log("No replacement as it could be find in either deployment or existed");
            } else {
                // handle system config

                // check if in the current deployment

                // TODO

                //                String configReplacement = lookupSystemConfig(componentIdentifier, namespace, key);
                //                if (configReplacement != null) {
                //                    stringValue = stringValue.replace(matcher.group(), configReplacement);
                //                }
            }


        }

        return stringValue;
    }

    /*
     * For each lifecycle key-value pair of a package, substitute parameter values.
     */
    private Object interpolate(Object configValue, ComponentIdentifier componentIdentifier,
                               List<ComponentIdentifier> packagesToDeploy, DeploymentDocument document,
                               Map<ComponentIdentifier, Pair<Set<ComponentParameter>, Set<String>>> parameterAndDependencyCache,
                               Map<ComponentIdentifier, Pair<Map, Set<String>>> configAndDependencyCache)
            throws PackageLoadingException {
        Object result = configValue;

        if (configValue instanceof String) {
            result = replace((String) configValue, componentIdentifier, packagesToDeploy, document,
                    parameterAndDependencyCache, configAndDependencyCache);
        }
        if (configValue instanceof Map) {
            Map<String, Object> childConfigMap = (Map<String, Object>) configValue;
            Map<String, Object> resolvedChildConfig = new HashMap<>();
            for (Entry<String, Object> childLifecycle : childConfigMap.entrySet()) {
                resolvedChildConfig.put(childLifecycle.getKey(),
                        interpolate(childLifecycle.getValue(), componentIdentifier, packagesToDeploy, document,
                                parameterAndDependencyCache, configAndDependencyCache));
            }
            result = resolvedChildConfig;
        }

        // No list handling because lists are outlawed under "Lifecycle" key

        return result;
    }

    private String replace(String stringValue, ComponentIdentifier componentIdentifier,
                           List<ComponentIdentifier> packagesToDeploy, DeploymentDocument document,
                           Map<ComponentIdentifier, Pair<Set<ComponentParameter>, Set<String>>> parameterAndDependencyCache,
                           Map<ComponentIdentifier, Pair<Map, Set<String>>> configAndDependencyCache)
            throws PackageLoadingException {
        // Handle same-component parameters
        Matcher matcher = SAME_INTERPOLATION_REGEX.matcher(stringValue);
        while (matcher.find()) {
            String replacement =
                    lookupParameterValueForComponent(parameterAndDependencyCache, document, componentIdentifier,
                            matcher.group(1), matcher.group(2));
            if (replacement != null) {
                stringValue = stringValue.replace(matcher.group(), replacement);
            }
        }

        /*
        // Handle same-component configuration
        matcher = SAME_COMPONENT_INTERPOLATION_REGEX.matcher(stringValue);

        while (matcher.find()) {
            String configReplacement =
                    lookupConfigurationValueForComponent(configAndDependencyCache, document, componentIdentifier,
                            matcher.group(1), matcher.group(2));
            if (configReplacement != null) {
                stringValue = stringValue.replace(matcher.group(), configReplacement);
            }
        }
*/
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
/*
        // Handle cross-component configuration
        // example {ComponentConfigurationTestService:configuration:/singleLevelKey}
        matcher = CROSS_COMPONENT_INTERPOLATION_REGEX.matcher(stringValue);

        while (matcher.find()) {

            String targetComponent = matcher.group(1);
            String namespace = matcher.group(2);
            String key = matcher.group(3);

            Optional<ComponentIdentifier> targetComponentIdentifier =
                    packagesToDeploy.stream().filter(t -> t.getName().equals(targetComponent)).findFirst();

            if (targetComponentIdentifier.isPresent() && componentCanReadParameterFrom(componentIdentifier,
                    targetComponentIdentifier.get(), parameterAndDependencyCache)) {
                String replacement = lookupParameterValueForComponent(parameterAndDependencyCache, document,
                        targetComponentIdentifier.get(), namespace, key);
                if (replacement != null) {
                    stringValue = stringValue.replace(matcher.group(), replacement);
                }
            }
        }
*/
        return stringValue;
    }

    private boolean componentCanReadParameterFrom(ComponentIdentifier component, ComponentIdentifier canReadFrom,
                                                  Map<ComponentIdentifier, Pair<Set<ComponentParameter>, Set<String>>> parameterAndDependencyCache) {
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

    @Nullable // null means no value found for interpolation
    // TODO try to avoid using null as branching logic after cleaning up the parameters
    private String lookupConfigurationValue(Map componentResolvedConfig, String key) {
        // Handle cross-component system parameters
        //        Map<String, CrashableFunction<ComponentIdentifier, String, PackageLoadingException>> systemParams =
        //                systemParameters.getOrDefault(namespace, Collections.emptyMap());
        //        if (systemParams.containsKey(key)) {
        //            return systemParams.get(key).apply(component);
        //        }

        //        if (namespace.equals(CONFIGURATION_NAMESPACE)) {
        JsonNode targetNode = mapper.convertValue(componentResolvedConfig, JsonNode.class).at(key);

        if (targetNode.isValueNode()) {
            return targetNode.asText();
        }

        if (targetNode.isMissingNode()) {
            LOGGER.atError()
                  .addKeyValue("Path", key)
                  .log("Failed to interpolate configuration due to missing value node at given path");
            // don't perform interpolation
            return null;
        }

        if (targetNode.isContainerNode()) {
            LOGGER.atError()
                  .addKeyValue("Path", key)
                  .addKeyValue("ContainerNode", targetNode.toString())
                  .log("Failed to interpolate configuration because node at given path is a container node");
            return null;
        }
        //        }
        return null;
    }

    @Nullable // null means no value found for interpolation
    // TODO try to avoid using null as branching logic after cleaning up the parameters
    private String lookupConfigurationValueForComponent(Map componentResolvedConfig, String key)
            throws PackageLoadingException {
        // Handle cross-component system parameters
        //        Map<String, CrashableFunction<ComponentIdentifier, String, PackageLoadingException>> systemParams =
        //                systemParameters.getOrDefault(namespace, Collections.emptyMap());
        //        if (systemParams.containsKey(key)) {
        //            return systemParams.get(key).apply(component);
        //        }

        //        if (namespace.equals(CONFIGURATION_NAMESPACE)) {
        JsonNode targetNode = mapper.convertValue(componentResolvedConfig, JsonNode.class).at(key);

        if (targetNode.isValueNode()) {
            return targetNode.asText();
        }

        if (targetNode.isMissingNode()) {
            LOGGER.atError()
                  .addKeyValue("Path", key)
                  .log("Failed to interpolate configuration due to missing value node at given path");
            // don't perform interpolation
            return null;
        }

        if (targetNode.isContainerNode()) {
            LOGGER.atError()
                  .addKeyValue("Path", key)
                  .addKeyValue("ContainerNode", targetNode.toString())
                  .log("Failed to interpolate configuration because node at given path is a container node");
            return null;
        }
        //        }
        return null;
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
        return document.getDeploymentPackageConfigurationList()
                       .stream()
                       .filter(packageConfig -> packageName.equals(packageConfig.getPackageName())
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
        return packageConfigInDeployment.map(deploymentPackageConfiguration -> ComponentParameter.fromMap(
                deploymentPackageConfiguration.getConfiguration())).orElse(Collections.emptySet());
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
            parameterValueStoredInConfig.ifPresent(s -> parametersStoredInConfig.add(
                    new ComponentParameter(parameterFromRecipe.getName(), s, parameterFromRecipe.getType())));
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
