/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.amazon.aws.iot.greengrass.component.common.ComponentConfiguration;
import com.amazon.aws.iot.greengrass.component.common.ComponentType;
import com.amazon.aws.iot.greengrass.component.common.DependencyProperties;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.ComponentRecipe;
import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.DeviceConfiguration;
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
import com.aws.greengrass.util.Utils;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.inject.Inject;

import static com.aws.greengrass.lifecyclemanager.GreengrassService.POSIX_USER_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.RUN_WITH_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SYSTEM_RESOURCE_LIMITS_TOPICS;
import static com.aws.greengrass.lifecyclemanager.Kernel.SERVICE_TYPE_TOPIC_KEY;

public class KernelConfigResolver {
    public static final String VERSION_CONFIG_KEY = "version";
    public static final String PREV_VERSION_CONFIG_KEY = "previousVersion";
    public static final String CONFIGURATION_CONFIG_KEY = "configuration";
    static final String IOT_NAMESPACE = "iot";
    static final String THING_NAME_PATH = "thingName";
    static final String ARTIFACTS_NAMESPACE = "artifacts";
    static final String WORK_NAMESPACE = "work";
    static final String KERNEL_NAMESPACE = "kernel";
    static final String KERNEL_ROOT_PATH = "rootPath";
    static final String CONFIGURATION_NAMESPACE = "configuration";
    static final String PATH_KEY = "path";
    static final String DECOMPRESSED_PATH_KEY = "decompressedPath";
    private static final Logger LOGGER = LogManager.getLogger(KernelConfigResolver.class);
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
    // https://tools.ietf.org/html/rfc6901#section-5
    private static final String JSON_POINTER_WHOLE_DOC = "";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    // Map from Namespace -> Key -> Function which returns the replacement value
    private final Map<String, Map<String, CrashableFunction<ComponentIdentifier, String, IOException>>>
            systemParameters = new HashMap<>();
    private final ComponentStore componentStore;
    public final Kernel kernel;
    private final DeviceConfiguration deviceConfiguration;


    /**
     * Constructor.
     *
     * @param componentStore package store used to look up packages
     * @param kernel         kernel
     * @param nucleusPaths   nucleus paths
     * @param deviceConfiguration device configuration
     */
    @Inject
    public KernelConfigResolver(ComponentStore componentStore, Kernel kernel, NucleusPaths nucleusPaths,
                                DeviceConfiguration deviceConfiguration) {
        this.componentStore = componentStore;
        this.kernel = kernel;
        this.deviceConfiguration = deviceConfiguration;

        // More system parameters can be added over time by extending this map with new namespaces/keys
        Map<String, CrashableFunction<ComponentIdentifier, String, IOException>> artifactNamespace = new HashMap<>();
        artifactNamespace.put(PATH_KEY, (id) -> nucleusPaths.artifactPath(id).toAbsolutePath().toString());
        artifactNamespace
                .put(DECOMPRESSED_PATH_KEY, (id) -> nucleusPaths.unarchiveArtifactPath(id).toAbsolutePath().toString());
        systemParameters.put(ARTIFACTS_NAMESPACE, artifactNamespace);

        Map<String, CrashableFunction<ComponentIdentifier, String, IOException>> workNamespace = new HashMap<>();
        workNamespace.put(PATH_KEY, (id) -> nucleusPaths.workPath(id.getName()).toAbsolutePath().toString());
        systemParameters.put(WORK_NAMESPACE, workNamespace);

        Map<String, CrashableFunction<ComponentIdentifier, String, IOException>> kernelNamespace = new HashMap<>();
        kernelNamespace.put(KERNEL_ROOT_PATH, (id) -> nucleusPaths.rootPath().toAbsolutePath().toString());
        systemParameters.put(KERNEL_NAMESPACE, kernelNamespace);

        Map<String, CrashableFunction<ComponentIdentifier, String, IOException>> iotNamespace = new HashMap<>();
        iotNamespace.put(THING_NAME_PATH, (id) -> Coerce.toString(deviceConfiguration.getThingName()));
        systemParameters.put(IOT_NAMESPACE, iotNamespace);
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
        Map<String, Object> servicesConfig = new HashMap<>();
        // resolve configuration
        for (ComponentIdentifier componentToDeploy : componentsToDeploy) {
            servicesConfig.put(componentToDeploy.getName(), getServiceConfig(componentToDeploy, document));
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

        String nucleusComponentName =
                getNucleusComponentName(servicesConfig);
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
     * @return a built map representing the kernel config under "services" key for a particular component
     * @throws PackageLoadingException if any service package was unable to be loaded
     */
    private Map<String, Object> getServiceConfig(ComponentIdentifier componentIdentifier, DeploymentDocument document)
            throws PackageLoadingException {

        ComponentRecipe componentRecipe = componentStore.getPackageRecipe(componentIdentifier);


        Map<String, Object> resolvedServiceConfig = new HashMap<>();

        resolvedServiceConfig.put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, componentRecipe.getLifecycle());


        resolvedServiceConfig.put(SERVICE_TYPE_TOPIC_KEY, componentRecipe.getComponentType() == null ? null
                : componentRecipe.getComponentType().name());

        // Generate dependencies
        resolvedServiceConfig.put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC,
                generateServiceDependencies(componentRecipe.getDependencies()));

        // State information for deployments
        handleComponentVersionConfigs(componentIdentifier, componentRecipe.getVersion().getValue(),
                                      resolvedServiceConfig);

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

    /**
     * Generate service dependency list from the given dependency definition from recipe.
     * @param dependencyPropertiesMap map of service dependency name to dependency properties
     * @return service dependency list
     */
    public List<String> generateServiceDependencies(Map<String, DependencyProperties> dependencyPropertiesMap) {
        // Generate dependencies
        List<String> dependencyConfig = new ArrayList<>();
        dependencyPropertiesMap.forEach((name, prop) -> dependencyConfig
                .add(prop.getDependencyType() == null ? name : name + ":" + prop.getDependencyType()));
        return dependencyConfig;
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

        if (runWith != null) {
            if (runWith.getSystemResourceLimits() == null) {
                runWithConfig.remove(SYSTEM_RESOURCE_LIMITS_TOPICS);
            } else {
                runWithConfig.put(SYSTEM_RESOURCE_LIMITS_TOPICS,
                        MAPPER.convertValue(runWith.getSystemResourceLimits(), Map.class));
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
    @SuppressWarnings("PMD.ConfusingTernary")
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
            } else if (ComponentType.NUCLEUS.equals(componentRecipe.getComponentType())) {
                // Copy from existing Nucleus config (if any)
                Topics nucleusTopics = kernel.findServiceTopic(deviceConfiguration.getNucleusComponentName());
                if (nucleusTopics != null) {
                    Topics nucleusConfig = nucleusTopics.findTopics(CONFIGURATION_CONFIG_KEY);
                    if (nucleusConfig != null) {
                        currentRunningConfig.copyFrom(nucleusConfig);
                    }
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
     * @param resolvedKernelServiceConfig resolved kernel configuration under "Services" key
     * @return the interpolated lifecycle object
     * @throws IOException for directory issues
     */
    public Object interpolate(Object configValue, ComponentIdentifier componentIdentifier, Set<String> dependencies,
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
                        .log("Failed to interpolate because the target component is not in resolved Nucleus services."
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

    /*
     * Compute the config for main service
     */
    private Map<String, Object> getMainConfig(List<String> rootPackages, String nucleusComponentName) {
        Map<String, Object> mainServiceConfig = new HashMap<>();
        Set<String> mainDependencies = new HashSet<>(rootPackages);
        kernel.getMain().getDependencies().forEach((greengrassService, dependencyType) -> {
            // Add all autostart dependencies
            if (greengrassService.isBuiltin()) {
                mainDependencies.add(greengrassService.getName() + ":" + dependencyType);
            }
        });

        // Make Nucleus component sticky
        mainDependencies.add(nucleusComponentName);

        mainServiceConfig.put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, new ArrayList<>(mainDependencies));
        return mainServiceConfig;
    }

    /*
     * If the deployment's service config has a component of type Nucleus use that, if it doesn't,
     * fall back to the first party Nucleus component
     */
    private String getNucleusComponentName(Map<String, Object> newServiceConfig) {
        Optional<String> nucleusComponentName = newServiceConfig.keySet().stream()
                .filter(s -> ComponentType.NUCLEUS.name().equals(getComponentType(newServiceConfig.get(s)))).findAny();
        return nucleusComponentName.orElse(deviceConfiguration.getNucleusComponentName());
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
}
