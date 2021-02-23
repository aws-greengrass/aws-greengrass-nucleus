/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.amazon.aws.iot.greengrass.component.common.ComponentType;
import com.aws.greengrass.componentmanager.exceptions.ComponentVersionNegotiationException;
import com.aws.greengrass.componentmanager.exceptions.NoAvailableComponentVersionException;
import com.aws.greengrass.componentmanager.exceptions.PackagingException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.ComponentMetadata;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentPackageConfiguration;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Utils;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;

import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY;

@NoArgsConstructor
public class DependencyResolver {
    static final String NON_EXPLICIT_NUCLEUS_UPDATE_ERROR_MESSAGE_FMT = "The deployment attempts to update the "
            + "nucleus from %s-%s to %s-%s but no component of type nucleus was included as target component, please "
            + "add the desired nucleus version as top level component if you wish to update the nucleus to a different "
            + "minor/major version";
    static final String NO_ACTIVE_NUCLEUS_VERSION_ERROR_MSG = "Nucleus version config is required but not found";
    private static final Logger logger = LogManager.getLogger(DependencyResolver.class);
    private static final String VERSION_KEY = "version";
    private static final String COMPONENT_NAME_KEY = "componentName";
    private static final String COMPONENT_VERSION_REQUIREMENT_KEY = "componentToVersionRequirements";
    @Inject
    private ComponentManager componentManager;

    @Inject
    private Kernel kernel;

    @Inject
    private ComponentStore componentStore;

    /**
     * Create the full list of components to be run on the device from a deployment document. It also resolves the
     * conflicts between the components specified in the deployment document and the existing running components on the
     * device.
     *
     * @param document                      deployment document
     * @param groupToTargetComponentDetails {@link Topics} providing component details for each group
     * @return a list of components to be run on the device
     * @throws NoAvailableComponentVersionException no version of the component can fulfill the deployment
     * @throws PackagingException                   for other component operation errors
     * @throws InterruptedException                 InterruptedException
     */
    public List<ComponentIdentifier> resolveDependencies(DeploymentDocument document,
                                                         Topics groupToTargetComponentDetails)
            throws NoAvailableComponentVersionException, PackagingException, InterruptedException {

        // A map of component version constraints {componentName => {dependentComponentName => versionConstraint}} to be
        // maintained and updated. This information needs to be tracked because: 1. One component can have multiple
        // dependent components posing different version constraints. 2. When the version of a dependent component
        // changes, the version constraints will also change accordingly. 3. The information also shows the complete
        // dependency tree.
        Map<String, Map<String, Requirement>> componentNameToVersionConstraints = new HashMap<>();

        // populate the other groups dependencies version requirement.
        Map<String, ComponentIdentifier> otherGroupsComponents =
                populateOtherGroupsComponentsDependencies(groupToTargetComponentDetails, document.getGroupName(),
                        componentNameToVersionConstraints);

        Map<String, ComponentIdentifier> resolvedComponents = new HashMap<>(otherGroupsComponents);

        // Get the target components with version requirements in the deployment document
        List<String> targetComponentsToResolve = new ArrayList<>();
        document.getDeploymentPackageConfigurationList().stream()
                .filter(DeploymentPackageConfiguration::isRootComponent).forEach(e -> {
            logger.atDebug().kv(COMPONENT_NAME_KEY, e.getPackageName()).kv(VERSION_KEY, e.getResolvedVersion())
                    .log("Found component configuration");
            componentNameToVersionConstraints.putIfAbsent(e.getPackageName(), new HashMap<>());
            componentNameToVersionConstraints.get(e.getPackageName())
                    .put(document.getGroupName(), Requirement.buildNPM(e.getResolvedVersion()));
            targetComponentsToResolve.add(e.getPackageName());
        });

        logger.atInfo().setEventType("resolve-group-dependencies-start")
                .kv("targetComponents", targetComponentsToResolve)
                .kv(COMPONENT_VERSION_REQUIREMENT_KEY, componentNameToVersionConstraints)
                .log("Start to resolve group dependencies");
        // resolve target components dependencies
        for (String component : targetComponentsToResolve) {
            resolvedComponents.putAll(resolveComponentDependencies(component, componentNameToVersionConstraints,
                    (name, requirements) -> componentManager.resolveComponentVersion(name, requirements,
                            document.getDeploymentId())));
        }

        checkNonExplicitNucleusUpdate(targetComponentsToResolve,
                resolvedComponents.entrySet().stream().map(Map.Entry::getValue).collect(Collectors.toList()));

        logger.atInfo().setEventType("resolve-group-dependencies-finish").kv("resolvedComponents", resolvedComponents)
                .kv(COMPONENT_VERSION_REQUIREMENT_KEY, componentNameToVersionConstraints)
                .log("Finish resolving group dependencies");
        return new ArrayList<>(resolvedComponents.values());
    }

    void checkNonExplicitNucleusUpdate(List<String> targetComponents,
                                     List<ComponentIdentifier> resolvedComponents) throws PackagingException {
        List<ComponentIdentifier> resolvedNucleusComponents = new ArrayList<>();
        for (ComponentIdentifier componentIdentifier : resolvedComponents) {
            if (ComponentType.NUCLEUS.equals(componentStore.getPackageRecipe(componentIdentifier).getComponentType())) {
                resolvedNucleusComponents.add(componentIdentifier);
            }
        }
        if (resolvedNucleusComponents.size() > 1) {
            throw new PackagingException(String.format("Deployment cannot have more than 1 component of type Nucleus "
                    + "%s", Arrays.toString(resolvedNucleusComponents.toArray())));
        }
        if (resolvedNucleusComponents.isEmpty()) {
            return;
        }
        Optional<GreengrassService> activeNucleusOption = kernel.orderedDependencies().stream()
                .filter(s -> ComponentType.NUCLEUS.name().equals(s.getServiceType())).findFirst();
        if (!activeNucleusOption.isPresent()) {
            return;
        }
        GreengrassService activeNucleus = activeNucleusOption.get();
        String activeNucleusVersionConfig = Coerce.toString(activeNucleus.getServiceConfig().find(VERSION_KEY));
        if (Utils.isEmpty(activeNucleusVersionConfig)) {
            throw new PackagingException(NO_ACTIVE_NUCLEUS_VERSION_ERROR_MSG);
        }
        Semver activeNucleusVersion = new Semver(activeNucleusVersionConfig);
        ComponentIdentifier activeNucleusId = new ComponentIdentifier(activeNucleus.getServiceName(),
                activeNucleusVersion);
        ComponentIdentifier resolvedNucleusId = resolvedNucleusComponents.get(0);

        if (!resolvedNucleusId.equals(activeNucleusId) && !targetComponents.contains(resolvedNucleusId.getName())) {
            Semver.VersionDiff diff = activeNucleusVersion.diff(resolvedNucleusId.getVersion());
            if (Semver.VersionDiff.MINOR.equals(diff) || Semver.VersionDiff.MAJOR.equals(diff)) {
                throw new PackagingException(
                        String.format(NON_EXPLICIT_NUCLEUS_UPDATE_ERROR_MESSAGE_FMT, activeNucleusId.getName(),
                                activeNucleusId.getVersion().toString(), resolvedNucleusId.getName(),
                                resolvedNucleusId.getVersion().toString()));
            }
        }
    }

    private Set<String> getOtherGroupsTargetComponents(Topics groupToTargetComponentDetails, String deploymentGroupName,
                                                       Map<String, Map<String, Requirement>>
                                                               componentNameToVersionConstraints) {
        Set<String> targetComponents = new HashSet<>();
        groupToTargetComponentDetails.forEach(node -> {
            Topics groupTopics = (Topics) node;
            String groupName = groupTopics.getName();
            if (!groupName.equals(deploymentGroupName)) {
                groupTopics.forEach(componentTopic -> {
                    targetComponents.add(componentTopic.getName());
                    componentNameToVersionConstraints.putIfAbsent(componentTopic.getName(), new HashMap<>());
                    Map<Object, Object> componentDetails = (Map) componentTopic.toPOJO();
                    componentNameToVersionConstraints.get(componentTopic.getName()).put(groupName, Requirement
                            .buildNPM(componentDetails.get(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY).toString()));
                });
            }
        });

        return targetComponents;
    }

    private Map<String, ComponentIdentifier> populateOtherGroupsComponentsDependencies(
            Topics groupToTargetComponentDetails, String deploymentGroupName,
            Map<String, Map<String, Requirement>> componentNameToVersionConstraints)
            throws PackagingException, InterruptedException {
        Set<String> otherGroupTargetComponents =
                getOtherGroupsTargetComponents(groupToTargetComponentDetails, deploymentGroupName,
                        componentNameToVersionConstraints);
        logger.atDebug().kv("otherGroupTargets", otherGroupTargetComponents)
                .log("Found the other group target components");
        // populate other groups target components dependencies
        // retrieve only dependency active version, update version requirement map
        Map<String, ComponentIdentifier> resolvedComponent = new HashMap<>();
        for (String targetComponent : otherGroupTargetComponents) {
            resolvedComponent.putAll(resolveComponentDependencies(targetComponent, componentNameToVersionConstraints,
                    (name, requirements) -> componentManager
                            .getActiveAndSatisfiedComponentMetadata(name, requirements)));
        }

        return resolvedComponent;
    }

    // Breadth first traverse of dependency tree, use component resolve to resolve every component
    private Map<String, ComponentIdentifier> resolveComponentDependencies(
            String targetComponentName, Map<String, Map<String, Requirement>> componentNameToVersionConstraints,
            ComponentResolver componentResolver) throws PackagingException, InterruptedException {
        logger.atDebug().setEventType("traverse-dependencies-start").kv("targetComponent", targetComponentName)
                .kv(COMPONENT_VERSION_REQUIREMENT_KEY, componentNameToVersionConstraints)
                .log("Start traversing dependencies");
        Queue<String> componentsToResolve = new LinkedList<>();
        componentsToResolve.add(targetComponentName);

        Map<String, ComponentIdentifier> resolvedComponents = new HashMap<>();
        while (!componentsToResolve.isEmpty()) {
            String componentToResolve = componentsToResolve.poll();
            Map<String, Requirement> versionConstraints =
                    new HashMap<>(componentNameToVersionConstraints.get(componentToResolve));
            ComponentMetadata resolvedVersion = componentResolver.resolve(componentToResolve, versionConstraints);
            logger.atDebug().kv("resolvedVersion", resolvedVersion).log("Resolved component");
            resolvedComponents.put(componentToResolve, resolvedVersion.getComponentIdentifier());

            for (Map.Entry<String, String> dependency : resolvedVersion.getDependencies().entrySet()) {
                // A circular dependency is present if the dependency is already resolved.
                if (resolvedComponents.containsKey(dependency.getKey())) {
                    throw new ComponentVersionNegotiationException("Circular dependency detected for component "
                            + dependency.getKey());
                }
                componentNameToVersionConstraints.putIfAbsent(dependency.getKey(), new HashMap<>());
                componentNameToVersionConstraints.get(dependency.getKey()).put(componentToResolve,
                        Requirement.buildNPM(dependency.getValue()));
                componentsToResolve.add(dependency.getKey());
            }
        }

        logger.atDebug().setEventType("traverse-dependencies-finish").kv("resolvedComponents", resolvedComponents)
                .log("Finish traversing dependencies");
        return resolvedComponents;
    }

    @FunctionalInterface
    public interface ComponentResolver {
        ComponentMetadata resolve(String name, Map<String, Requirement> requirements)
                throws PackagingException, InterruptedException;
    }
}
