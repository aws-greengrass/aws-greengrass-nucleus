/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.amazon.aws.iot.greengrass.component.common.ComponentConfiguration;
import com.amazon.aws.iot.greengrass.component.common.ComponentType;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.ComponentParameter;
import com.aws.greengrass.componentmanager.models.ComponentRecipe;
import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.model.ConfigurationUpdateOperation;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentPackageConfiguration;
import com.aws.greengrass.deployment.model.RunWith;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.CrashableFunction;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.Pair;
import com.aws.greengrass.util.Utils;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.inject.Inject;

import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.POSIX_USER_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.RUN_WITH_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.Kernel.SERVICE_TYPE_TOPIC_KEY;

public class KernelConfigResolver {
    private static final Logger LOGGER = LogManager.getLogger(KernelConfigResolver.class);
    public static final String VERSION_CONFIG_KEY = "version";
    public static final String PREV_VERSION_CONFIG_KEY = "previousVersion";
    public static final String PARAMETERS_CONFIG_KEY = "parameters";

    // TODO: [P41216177]: Deprecate parameters in favor of configuration
    public static final String CONFIGURATION_CONFIG_KEY = "parameters";

    static final String ARTIFACTS_NAMESPACE = "artifacts";
    static final String KERNEL_NAMESPACE = "kernel";
    static final String KERNEL_ROOT_PATH = "rootPath";
    private static final String WORD_GROUP = "([\\.\\w]+)";
    // Pattern matches {{otherComponentName:parameterNamespace:parameterKey}}
    private static final Pattern CROSS_INTERPOLATION_REGEX =
            Pattern.compile("\\{\\{" + WORD_GROUP + ":" + WORD_GROUP + ":" + WORD_GROUP + "}}");
    private static final Pattern SAME_INTERPOLATION_REGEX =
            Pattern.compile("\\{\\{" + WORD_GROUP + ":" + WORD_GROUP + "}}");

    // pattern matches {group1:group2}. ex. {configuration:/singleLevelKey}
    // Group 1 could only be word or dot (.). It is for the namespace such as "artifacts" and "configuration".
    // Group 2 is the key. For namespace "configuration", it needs to support arbitrary JSON pointer.
    // so it can take any character but not be ':' or '}', because these breaks the interpolation placeholder format.
    private static final Pattern SAME_COMPONENT_INTERPOLATION_REGEX = Pattern.compile("\\{([.\\w]+):([^:}]+)}");


    // pattern matches {group1:group2:group3}.
    // ex. {aws.iot.aws.iot.gg.test.integ.ComponentConfigTestService:configuration:/singleLevelKey}
    // Group 1 could only be word or dot (.). It is for the component name.
    // Group 1 could only be word or dot (.). It is for the namespace such as "artifacts" and "configuration".
    // Group 2 is the key. For namespace "configuration", it needs to support arbitrary JSON pointer.
    // so it can take any character but not be ':' or '}', because these breaks the interpolation placeholder format.
    private static final Pattern CROSS_COMPONENT_INTERPOLATION_REGEX =
            Pattern.compile("\\{([.\\w]+):([.\\w]+):([^:}]+)}");

    static final String PARAM_NAMESPACE = "params";
    static final String CONFIGURATION_NAMESPACE = "configuration";
    static final String PARAM_VALUE_SUFFIX = ".value";
    static final String PATH_KEY = "path";
    static final String DECOMPRESSED_PATH_KEY = "decompressedPath";

    private static final String NO_RECIPE_ERROR_FORMAT = "Failed to find component recipe for {}";

    // https://tools.ietf.org/html/rfc6901#section-5
    private static final String JSON_POINTER_WHOLE_DOC = "";

    // Map from Namespace -> Key -> Function which returns the replacement value
    private final Map<String, Map<String, CrashableFunction<ComponentIdentifier, String, IOException>>>
            systemParameters = new HashMap<>();

    private final ComponentStore componentStore;
    private final Kernel kernel;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);


    /**
     * Constructor.
     *
     * @param componentStore package store used to look up packages
     * @param kernel         kernel
     * @param nucleusPaths   nucleus paths
     */
    @Inject
    public KernelConfigResolver(ComponentStore componentStore, Kernel kernel, NucleusPaths nucleusPaths) {
        this.componentStore = componentStore;
        this.kernel = kernel;

        // More system parameters can be added over time by extending this map with new namespaces/keys
        HashMap<String, CrashableFunction<ComponentIdentifier, String, IOException>> artifactNamespace =
                new HashMap<>();
        artifactNamespace.put(PATH_KEY, (id) -> nucleusPaths.artifactPath(id).toAbsolutePath().toString());
        artifactNamespace
                .put(DECOMPRESSED_PATH_KEY, (id) -> nucleusPaths.unarchiveArtifactPath(id).toAbsolutePath().toString());
        systemParameters.put(ARTIFACTS_NAMESPACE, artifactNamespace);

        HashMap<String, CrashableFunction<ComponentIdentifier, String, IOException>> kernelNamespace = new HashMap<>();
        kernelNamespace.put(KERNEL_ROOT_PATH, (id) -> nucleusPaths.rootPath().toAbsolutePath().toString());
        systemParameters.put(KERNEL_NAMESPACE, kernelNamespace);
    }

    /**
     * Create a kernel config map from a list of package identifiers and deployment document. For each package, it first
     * retrieves its recipe, then merges the parameter values into the recipe, and last transform it to a kernel config
     * key-value pair.
     *
     * @param componentsToDeploy package identifiers for resolved packages of complete dependency graph across groups
     * @param document           deployment document
     * @param rootPackages       root level packages
     * @return a kernel config map
     * @throws PackageLoadingException if any service package was unable to be loaded
     * @throws IOException             for directory issues
     */
    public Map<String, Object> resolve(List<ComponentIdentifier> componentsToDeploy, DeploymentDocument document,
            List<String> rootPackages) throws PackageLoadingException, IOException {
        Map<ComponentIdentifier, Pair<Set<ComponentParameter>, Set<String>>> parameterAndDependencyCache =
                new ConcurrentHashMap<>();
        Map<String, Object> servicesConfig = new HashMap<>();

        // resolve configuration
        for (ComponentIdentifier componentToDeploy : componentsToDeploy) {
            servicesConfig.put(componentToDeploy.getName(),
                               getServiceConfig(componentToDeploy, document, componentsToDeploy,
                                                parameterAndDependencyCache));
        }

        // Interpolate configurations
        for (ComponentIdentifier resolvedComponentsToDeploy : componentsToDeploy) {
            ComponentRecipe componentRecipe = componentStore.getPackageRecipe(resolvedComponentsToDeploy);

            Object existingLifecycle = ((Map) servicesConfig.get(resolvedComponentsToDeploy.getName()))
                    .get(SERVICE_LIFECYCLE_NAMESPACE_TOPIC);

            Object interpolatedLifecycle = interpolate(existingLifecycle, resolvedComponentsToDeploy,
                                                       componentRecipe.getDependencies().keySet(), servicesConfig);

            ((Map) servicesConfig.get(resolvedComponentsToDeploy.getName()))
                    .put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, interpolatedLifecycle);
        }

        String nucleusComponentName = getNucleusComponentName(servicesConfig);
        servicesConfig.putIfAbsent(nucleusComponentName, getNucleusComponentConfig(nucleusComponentName));
        servicesConfig.put(kernel.getMain().getName(), getMainConfig(rootPackages, nucleusComponentName));

        // Services need to be under the services namespace in kernel config
        return Collections.singletonMap(SERVICES_NAMESPACE_TOPIC, servicesConfig);
    }

    /**
     * Build the kernel config for a service/component by processing deployment document.
     *
     * @param componentIdentifier         target component id
     * @param document                    deployment doc for the current deployment
     * @param componentsToDeploy          the entire list of components that would be deployed to the device cross
     *                                    groups
     * @param parameterAndDependencyCache cache for processing parameter and dependency
     * @return a built map representing the kernel config under "services" key for a particular component
     * @throws PackageLoadingException if any service package was unable to be loaded
     * @throws IOException             for directory issues
     */
    private Map<String, Object> getServiceConfig(ComponentIdentifier componentIdentifier, DeploymentDocument document,
            List<ComponentIdentifier> componentsToDeploy,
            Map<ComponentIdentifier, Pair<Set<ComponentParameter>, Set<String>>> parameterAndDependencyCache)
            throws PackageLoadingException, IOException {

        ComponentRecipe componentRecipe = componentStore.getPackageRecipe(componentIdentifier);

        Set<ComponentParameter> resolvedParams = resolveParameterValuesToUse(document, componentRecipe);
        parameterAndDependencyCache
                .put(componentIdentifier, new Pair<>(resolvedParams, componentRecipe.getDependencies().keySet()));

        Map<String, Object> resolvedServiceConfig = new HashMap<>();

        // Interpolate parameters
        resolvedServiceConfig.put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC,
                                  interpolate(componentRecipe.getLifecycle(), componentIdentifier, componentsToDeploy,
                                              document, parameterAndDependencyCache));

        resolvedServiceConfig.put(SERVICE_TYPE_TOPIC_KEY, componentRecipe.getComponentType() == null ? null
                : componentRecipe.getComponentType().name());

        // Generate dependencies
        List<String> dependencyConfig = new ArrayList<>();
        componentRecipe.getDependencies().forEach((name, prop) -> dependencyConfig
                .add(prop.getDependencyType() == null ? name : name + ":" + prop.getDependencyType()));
        resolvedServiceConfig.put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, dependencyConfig);

        // State information for deployments
        handleComponentVersionConfigs(componentIdentifier, componentRecipe.getVersion().getValue(),
                                      resolvedServiceConfig);
        Map<String, String> resolvedParamMap = new HashMap<>();
        for (ComponentParameter resolvedParam : resolvedParams) {
            resolvedParamMap.put(resolvedParam.getName(), resolvedParam.getValue());
        }
        resolvedServiceConfig.put(PARAMETERS_CONFIG_KEY, resolvedParamMap);

        Optional<DeploymentPackageConfiguration> optionalDeploymentPackageConfig =
                document.getDeploymentPackageConfigurationList().stream()
                        .filter(e -> e.getPackageName().equals(componentRecipe.getComponentName()))

                        // only allow update config for root
                        // no need to check version because root's version will be pinned
                        .filter(DeploymentPackageConfiguration::isRootComponent).findAny();

        Optional<ConfigurationUpdateOperation> optionalConfigUpdate = Optional.empty();
        if (optionalDeploymentPackageConfig.isPresent()) {
            DeploymentPackageConfiguration packageConfiguration = optionalDeploymentPackageConfig.get();
            optionalConfigUpdate = Optional.ofNullable(packageConfiguration.getConfigurationUpdateOperation());

            if (!optionalConfigUpdate.isPresent() && packageConfiguration.getConfiguration() != null) {
                ConfigurationUpdateOperation op = new ConfigurationUpdateOperation();
                op.setValueToMerge(packageConfiguration.getConfiguration());
                optionalConfigUpdate = Optional.of(op);
            }

            updateRunWith(packageConfiguration.getRunWith(), resolvedServiceConfig, componentIdentifier.getName());
        } else {
            // make sure existing run with is merged
            updateRunWith(null, resolvedServiceConfig, componentIdentifier.getName());
        }

        Map<String, Object> resolvedConfiguration = resolveConfigurationToApply(optionalConfigUpdate.orElse(null),
                componentRecipe, document);

        // merge resolved param and resolved configuration for backward compatibility
        resolvedServiceConfig
                .put(CONFIGURATION_CONFIG_KEY, resolvedConfiguration);

        return resolvedServiceConfig;
    }

    private void updateRunWith(RunWith runWith, Map<String, Object> resolvedServiceConfig, String componentName) {
        Topics serviceTopics = kernel.findServiceTopic(componentName);
        Map<String, Object> runWithConfig = new HashMap<>();
        boolean hasExisting = false;
        if (serviceTopics != null) {
            Topics runWithTopics = serviceTopics.findTopics(RUN_WITH_NAMESPACE_TOPIC);
            if (runWithTopics != null) {
                runWithConfig = runWithTopics.toPOJO();
                hasExisting = true;
            }
        }
        if (runWith != null && runWith.hasPosixUserValue()) {
            if (Utils.isEmpty(runWith.getPosixUser())) {
                runWithConfig.remove(POSIX_USER_KEY);
            } else {
                runWithConfig.put(POSIX_USER_KEY, runWith.getPosixUser());
            }
        }
        if (!runWithConfig.isEmpty() || hasExisting) {
            resolvedServiceConfig.put(RUN_WITH_NAMESPACE_TOPIC, runWithConfig);
        }
    }


    /**
     * Resolve configurations to apply for a component. It resolves based on current running config, default config, and
     * config update operation.
     *
     * @param configurationUpdateOperation nullable component configuration update operation.
     * @param componentRecipe              component recipe containing default configuration.
     * @param document                     deployment document
     * @return resolved configuration for this component. non null.
     */
    private Map<String, Object> resolveConfigurationToApply(
            @Nullable ConfigurationUpdateOperation configurationUpdateOperation, ComponentRecipe componentRecipe,
            DeploymentDocument document) {

        // try read the running service config
        try (Context context = new Context()) {
            Configuration currentRunningConfig = new Configuration(context);

            // Copy from running config (if any)
            Topics serviceTopics = kernel.findServiceTopic(componentRecipe.getComponentName());
            if (serviceTopics != null) {
                Topics configuration = serviceTopics.findTopics(CONFIGURATION_CONFIG_KEY);
                if (configuration != null) {
                    currentRunningConfig.copyFrom(configuration);
                }
            }

            // Remove keys which want to be reset to their default value
            if (configurationUpdateOperation != null) {
                removeKeysFromConfigWhichAreReset(currentRunningConfig, configurationUpdateOperation.getPathsToReset());
            }

            // Merge in the defaults with timestamp 1 so that they don't overwrite any pre-existing values
            JsonNode defaultConfig = Optional.ofNullable(componentRecipe.getComponentConfiguration())
                    .map(ComponentConfiguration::getDefaultConfiguration)
                    .orElse(MAPPER.createObjectNode()); // init null to be empty default config
            // Merge in the defaults from the recipe using timestamp 1 to denote a default
            currentRunningConfig.mergeMap(1, MAPPER.convertValue(defaultConfig, Map.class));
            currentRunningConfig.context.waitForPublishQueueToClear();

            // Merge in the requested config updates
            if (configurationUpdateOperation != null && configurationUpdateOperation.getValueToMerge() != null) {
                currentRunningConfig.mergeMap(document.getTimestamp(), configurationUpdateOperation.getValueToMerge());
            }

            return currentRunningConfig.toPOJO();
        } catch (IOException ignored) {
        }
        return new HashMap<>();
    }

    private void removeKeysFromConfigWhichAreReset(Configuration original, List<String> pathsToReset) {
        if (pathsToReset == null || pathsToReset.isEmpty()) {
            return;
        }

        for (String pointer : pathsToReset) {
            // special case handling for reset whole document
            if (pointer.equals(JSON_POINTER_WHOLE_DOC)) {
                original.getRoot().replaceAndWait(new HashMap<>());
                return;
            }

            // regular pointer handling
            JsonPointer jsonPointer = JsonPointer.compile(pointer);
            String[] path = pointerToPath(jsonPointer).toArray(new String[]{});

            if (pointsToArrayElement(jsonPointer)) {
                // no support for resetting an element of array
                LOGGER.atError().kv("jsonPointer", jsonPointer)
                        .log("Failed to reset because provided pointer for reset points to an element of array.");
                continue;
            }

            Topic topic = original.find(path);
            Topics topics = original.findTopics(path);
            if (topics != null) {
                topics.remove();
            }
            if (topic != null) {
                topic.remove();
            }
        }
    }

    private boolean pointsToArrayElement(JsonPointer pointer) {
        if (pointer.tail() != null) {
            return pointer.mayMatchElement() || pointsToArrayElement(pointer.tail());
        }
        return pointer.mayMatchElement();
    }

    private List<String> pointerToPath(JsonPointer pointer) {
        if (pointer.tail() != null) {
            List<String> path = pointerToPath(pointer.tail());
            path.add(0, pointer.getMatchingProperty());
            return path;
        }
        if (Utils.isNotEmpty(pointer.getMatchingProperty())) {
            return new ArrayList<>(Collections.singletonList(pointer.getMatchingProperty()));
        }
        return new ArrayList<>();
    }

    /**
     * Interpolate the lifecycle commands with resolved component configuration values and system configuration values.
     *
     * @param configValue                 original value; could be Map or String
     * @param componentIdentifier         target component id
     * @param dependencies                name set of component's dependencies
     * @param resolvedKernelServiceConfig resolved kernel configuration under "Service" key
     * @return the interpolated lifecycle object
     * @throws IOException for directory issues
     */
    private Object interpolate(Object configValue, ComponentIdentifier componentIdentifier, Set<String> dependencies,
            Map<String, Object> resolvedKernelServiceConfig) throws IOException {
        Object result = configValue;

        if (configValue instanceof String) {
            result = replace((String) configValue, componentIdentifier, dependencies, resolvedKernelServiceConfig);
        }
        if (configValue instanceof Map) {
            Map<String, Object> childConfigMap = (Map<String, Object>) configValue;
            Map<String, Object> resolvedChildConfig = new HashMap<>();
            for (Entry<String, Object> childLifecycle : childConfigMap.entrySet()) {
                resolvedChildConfig.put(childLifecycle.getKey(),
                                        interpolate(childLifecycle.getValue(), componentIdentifier, dependencies,
                                                    resolvedKernelServiceConfig));
            }
            result = resolvedChildConfig;
        }

        // No list handling because lists are outlawed under "Lifecycle" key
        return result;
    }

    private String replace(String stringValue, ComponentIdentifier componentIdentifier, Set<String> dependencies,
            Map<String, Object> resolvedKernelServiceConfig) throws IOException {

        Matcher matcher;

        // Handle same-component interpolation. ex. {configuration:/singleLevelKey}
        matcher = SAME_COMPONENT_INTERPOLATION_REGEX.matcher(stringValue);

        while (matcher.find()) {
            String namespace = matcher.group(1);
            String key = matcher.group(2);

            if (CONFIGURATION_NAMESPACE.equals(namespace)) {
                Optional<String> configReplacement =
                        lookupConfigurationValueForComponent(componentIdentifier.getName(), key,
                                                             resolvedKernelServiceConfig);
                if (configReplacement.isPresent()) {
                    stringValue = stringValue.replace(matcher.group(), configReplacement.get());
                }

            } else if (systemParameters.containsKey(namespace)) {
                String configReplacement = lookupSystemConfig(componentIdentifier, namespace, key);
                if (configReplacement != null) {
                    stringValue = stringValue.replace(matcher.group(), configReplacement);
                }

            } else {
                // unrecognized namespace
                LOGGER.atError().kv("interpolation placeholder", matcher.group()).kv("namespace", namespace)
                        .log("Failed to interpolate because of unrecognized namespace for interpolation.");
            }
        }

        // Handle cross-component interpolation. ex. {aws.iot.gg.component1:configuration:/singleLevelKey}
        matcher = CROSS_COMPONENT_INTERPOLATION_REGEX.matcher(stringValue);

        while (matcher.find()) {
            String targetComponent = matcher.group(1);
            String namespace = matcher.group(2);
            String key = matcher.group(3);

            // only interpolate if target component is a direct dependency
            if (!dependencies.contains(targetComponent)) {
                LOGGER.atError().kv("interpolation text", matcher.group()).kv("target component", targetComponent)
                        .kv("main component", componentIdentifier.getName())
                        .log("Failed to interpolate because the target component it's not a direct dependency.");
                continue;
            }

            if (!resolvedKernelServiceConfig.containsKey(targetComponent)) {
                LOGGER.atError().kv("interpolation text", matcher.group()).kv("target component", targetComponent)
                        .kv("main component", componentIdentifier.getName())
                        .log("Failed to interpolate because the target component is not in resolved kernel services."
                                     + " This indicates the dependency resolution is broken.");
                continue;
            }

            if (CONFIGURATION_NAMESPACE.equals(namespace)) {
                Optional<String> configReplacement =
                        lookupConfigurationValueForComponent(targetComponent, key, resolvedKernelServiceConfig);
                if (configReplacement.isPresent()) {
                    stringValue = stringValue.replace(matcher.group(), configReplacement.get());
                }

            } else if (systemParameters.containsKey(namespace)) {
                String version =
                        (String) ((Map) resolvedKernelServiceConfig.get(targetComponent)).get(VERSION_CONFIG_KEY);

                String configReplacement =
                        lookupSystemConfig(new ComponentIdentifier(targetComponent, new Semver(version)), namespace,
                                           key);

                if (configReplacement != null) {
                    stringValue = stringValue.replace(matcher.group(), configReplacement);
                }
            } else {
                // unrecognized namespace
                LOGGER.atError().kv("interpolation placeholder", matcher.group()).kv("namespace", namespace)
                        .log("Failed to interpolate because of unrecognized namespace for interpolation.");
            }

        }

        return stringValue;
    }

    /**
     * Find the configuration value for a component.
     *
     * @param componentName               component name
     * @param path                        path to the value
     * @param resolvedKernelServiceConfig resolved kernel service config to search from
     * @return configuration value for the path; empty if not found.
     */
    private Optional<String> lookupConfigurationValueForComponent(String componentName, String path,
            Map<String, Object> resolvedKernelServiceConfig) {

        Map componentResolvedConfig;

        if (resolvedKernelServiceConfig.containsKey(componentName) && ((Map) resolvedKernelServiceConfig
                .get(componentName)).containsKey(CONFIGURATION_CONFIG_KEY)) {
            componentResolvedConfig =
                    (Map) ((Map) resolvedKernelServiceConfig.get(componentName)).get(CONFIGURATION_CONFIG_KEY);
        } else {
            return Optional.empty();
        }

        JsonNode targetNode = MAPPER.convertValue(componentResolvedConfig, JsonNode.class).at(path);

        if (targetNode.isValueNode()) {
            return Optional.of(targetNode.asText());
        }

        if (targetNode.isMissingNode()) {
            LOGGER.atError().addKeyValue("Path", path)
                    .log("Failed to interpolate configuration due to missing value node at given path");
            return Optional.empty();
        }

        if (targetNode.isContainerNode()) {
            // return a serialized string for container node
            return Optional.of(targetNode.toString());
        }
        return Optional.empty();
    }

    @Nullable
    private String lookupSystemConfig(ComponentIdentifier component, String namespace, String key) throws IOException {
        // Handle system-wide configuration
        Map<String, CrashableFunction<ComponentIdentifier, String, IOException>> systemParams =
                systemParameters.getOrDefault(namespace, Collections.emptyMap());
        if (systemParams.containsKey(key)) {
            return systemParams.get(key).apply(component);
        }
        return null;
    }

    /**** end of new configuration code path. Most of below are all deprecated and will be remove soon ****/

    /*
     * For each lifecycle key-value pair of a package, substitute parameter values.
     */
    @Deprecated
    @SuppressWarnings("checkstyle:OverloadMethodsDeclarationOrder")
    private Object interpolate(Object configValue, ComponentIdentifier componentIdentifier,
            List<ComponentIdentifier> packagesToDeploy, DeploymentDocument document,
            Map<ComponentIdentifier, Pair<Set<ComponentParameter>, Set<String>>> parameterAndDependencyCache)
            throws IOException {

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
                                        interpolate(childLifecycle.getValue(), componentIdentifier, packagesToDeploy,
                                                    document, parameterAndDependencyCache));
            }
            result = resolvedChildConfig;
        }

        return result;
    }

    @Deprecated
    private String replace(String stringValue, ComponentIdentifier componentIdentifier,
            List<ComponentIdentifier> packagesToDeploy, DeploymentDocument document,
            Map<ComponentIdentifier, Pair<Set<ComponentParameter>, Set<String>>> parameterAndDependencyCache)
            throws IOException {

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
                                                                                      crossComponentIdentifier.get(),
                                                                                      parameterAndDependencyCache)) {
                String replacement = lookupParameterValueForComponent(parameterAndDependencyCache, document,
                                                                      crossComponentIdentifier.get(), matcher.group(2),
                                                                      matcher.group(3));
                if (replacement != null) {
                    stringValue = stringValue.replace(matcher.group(), replacement);
                }
            }
        }
        return stringValue;
    }

    @Deprecated
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

    @Nullable
    @Deprecated
    private String lookupParameterValueForComponent(
            Map<ComponentIdentifier, Pair<Set<ComponentParameter>, Set<String>>> parameterAndDependencyCache,
            DeploymentDocument document, ComponentIdentifier component, String namespace, String key)
            throws IOException {
        // Handle cross-component system parameters
        Map<String, CrashableFunction<ComponentIdentifier, String, IOException>> systemParams =
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
    private Map<String, Object> getMainConfig(List<String> rootPackages, String nucleusComponentName) {
        Map<String, Object> mainServiceConfig = new HashMap<>();
        ArrayList<String> mainDependencies = new ArrayList<>(rootPackages);
        kernel.getMain().getDependencies().forEach((greengrassService, dependencyType) -> {
            // Add all autostart dependencies
            if (greengrassService.isBuiltin()) {
                mainDependencies.add(greengrassService.getName() + ":" + dependencyType);
            }
        });

        // Make Nucleus component sticky
        mainDependencies.add(nucleusComponentName);

        mainServiceConfig.put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, mainDependencies);
        return mainServiceConfig;
    }

    /*
     * If the deployment's service config has a component of type Nucleus use that, if it doesn't,
     * fall back to the first party Nucleus component
     */
    private String getNucleusComponentName(Map<String, Object> newServiceConfig) {
        Optional<String> nucleusComponentName = newServiceConfig.keySet().stream()
                .filter(s -> ComponentType.NUCLEUS.name().equals(getComponentType(newServiceConfig.get(s)))).findAny();
        return nucleusComponentName.orElse(DEFAULT_NUCLEUS_COMPONENT_NAME);
    }

    private Object getNucleusComponentConfig(String nucleusComponentName) {
        Topics nucleusServiceTopics = kernel.findServiceTopic(nucleusComponentName);
        return Objects.isNull(nucleusServiceTopics) ? null : nucleusServiceTopics.toPOJO();
    }

    private String getComponentType(Object serviceConfig) {
        String componentType = null;
        if (serviceConfig instanceof Map) {
            componentType = Coerce.toString(((Map<String, Object>) serviceConfig).get(SERVICE_TYPE_TOPIC_KEY));
        }
        return componentType;
    }

    /*
     * Record current deployment version in service config. Rotate versions.
     */
    private void handleComponentVersionConfigs(ComponentIdentifier compId, String deploymentVersion,
            Map<String, Object> newConfig) {
        newConfig.put(VERSION_CONFIG_KEY, deploymentVersion);
        Topic existingVersionTopic =
                kernel.getConfig().find(SERVICES_NAMESPACE_TOPIC, compId.getName(), VERSION_CONFIG_KEY);
        if (existingVersionTopic == null) {
            return;
        }

        String existingVersion = (String) existingVersionTopic.getOnce();
        if (existingVersion.equals(deploymentVersion)) {
            // preserve the prevVersion if it exists
            Topic existingPrevVersionTopic =
                    kernel.getConfig().find(SERVICES_NAMESPACE_TOPIC, compId.getName(), PREV_VERSION_CONFIG_KEY);
            if (existingPrevVersionTopic != null) {
                String existingPrevVersion = (String) existingVersionTopic.getOnce();
                newConfig.put(PREV_VERSION_CONFIG_KEY, existingPrevVersion);
            }
        } else {
            // rotate versions if deploying a different version than the existing one
            newConfig.put(PREV_VERSION_CONFIG_KEY, existingVersion);
        }
    }

    /*
     * Get configuration for a package-version combination from deployment document.
     */
    @Deprecated
    private Optional<DeploymentPackageConfiguration> getMatchingPackageConfigFromDeployment(DeploymentDocument document,
            String packageName, String packageVersion) {
        return document.getDeploymentPackageConfigurationList().stream()
                .filter(packageConfig -> packageName.equals(packageConfig.getPackageName())
                        //TODO: [P41216380]: packageConfig.getResolvedVersion() should be strongly typed when created
                        && Requirement.buildNPM(packageConfig.getResolvedVersion())
                        .isSatisfiedBy(new Semver(packageVersion, Semver.SemverType.NPM))).findAny();
    }

    @Deprecated
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
    @Deprecated
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
    @Deprecated
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
    @Deprecated
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
    @Deprecated
    private Optional<String> getParameterValueFromServiceConfig(String service, String parameterName) {
        Topics serviceTopics = kernel.findServiceTopic(service);
        if (serviceTopics == null) {
            return Optional.empty();
        }
        Topic parameterConfig = serviceTopics.find(PARAMETERS_CONFIG_KEY, parameterName);
        return parameterConfig == null ? Optional.empty() : Optional.ofNullable(Coerce.toString(parameterConfig));
    }
}
