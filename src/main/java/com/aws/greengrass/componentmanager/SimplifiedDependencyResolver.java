/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.aws.greengrass.componentmanager.exceptions.NoAvailableComponentVersionException;
import com.aws.greengrass.componentmanager.exceptions.PackagingException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.ComponentMetadata;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentPackageConfiguration;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.vdurmont.semver4j.Requirement;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import javax.inject.Inject;

import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY;

@NoArgsConstructor
public class SimplifiedDependencyResolver {
    private static final Logger logger = LogManager.getLogger(SimplifiedDependencyResolver.class);
    private static final String VERSION_KEY = "version";
    private static final String COMPONENT_NAME_KEY = "componentName";
    private static final String COMPONENT_VERSION_REQUIREMENT_KEY = "componentToVersionRequirements";

    @Inject
    private ComponentManager componentManager;

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
     */
    public List<ComponentIdentifier> resolveDependencies(DeploymentDocument document,
                                                         Topics groupToTargetComponentDetails)
            throws NoAvailableComponentVersionException, PackagingException {

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
                    (name, requirements) -> componentManager.resolveComponentVersion(name, requirements)));
        }

        logger.atInfo().setEventType("resolve-group-dependencies-finish").kv("resolvedComponents", resolvedComponents)
                .kv(COMPONENT_VERSION_REQUIREMENT_KEY, componentNameToVersionConstraints)
                .log("Finish resolving group dependencies");
        return new ArrayList<>(resolvedComponents.values());
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
            Map<String, Map<String, Requirement>> componentNameToVersionConstraints) throws PackagingException {
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
    private Map<String, ComponentIdentifier> resolveComponentDependencies(String targetComponentName,
                                                                          Map<String, Map<String, Requirement>>
                                                                                  componentNameToVersionConstraints,
                                                                          ComponentResolver componentResolver)
            throws PackagingException {
        logger.atDebug().setEventType("traverse-dependencies-start").kv("targetComponent", targetComponentName)
                .kv(COMPONENT_VERSION_REQUIREMENT_KEY, componentNameToVersionConstraints)
                .log("Start traversing dependencies");
        Queue<String> componentsToResolve = new LinkedList<>();
        componentsToResolve.add(targetComponentName);

        Map<String, ComponentIdentifier> resolvedComponents = new HashMap<>();
        while (componentsToResolve.size() != 0) {
            String componentToResolve = componentsToResolve.poll();
            Map<String, Requirement> versionConstraints =
                    new HashMap<>(componentNameToVersionConstraints.get(componentToResolve));
            ComponentMetadata resolvedVersion = componentResolver.resolve(componentToResolve, versionConstraints);
            logger.atDebug().kv("resolvedVersion", resolvedVersion).log("Resolved component");
            resolvedComponents.put(componentToResolve, resolvedVersion.getComponentIdentifier());
            resolvedVersion.getDependencies().forEach((k, v) -> {
                componentNameToVersionConstraints.putIfAbsent(k, new HashMap<>());
                componentNameToVersionConstraints.get(k).put(componentToResolve, Requirement.buildNPM(v));
                componentsToResolve.add(k);
            });
        }

        logger.atDebug().setEventType("traverse-dependencies-finish").kv("resolvedComponents", resolvedComponents)
                .log("Finish traversing dependencies");
        return resolvedComponents;
    }

    @FunctionalInterface
    public interface ComponentResolver {
        ComponentMetadata resolve(String name, Map<String, Requirement> requirements) throws PackagingException;
    }
}
