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
import com.aws.greengrass.componentmanager.models.ComponentRequirementIdentifier;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentPackageConfiguration;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.DependencyOrder;
import com.aws.greengrass.util.Utils;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.COMPONENT_VERSION_NOT_VALID;

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
     * @param document deployment document
     * @param otherGroupsToRootComponents root components associated with other groups
     * @return a list of components to be run on the device
     * @throws NoAvailableComponentVersionException no version of the component can fulfill the deployment
     * @throws PackagingException for other component operation errors
     * @throws InterruptedException InterruptedException
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public List<ComponentIdentifier> resolveDependencies(DeploymentDocument document,
            Map<String, Set<ComponentRequirementIdentifier>> otherGroupsToRootComponents)
            throws NoAvailableComponentVersionException, PackagingException, InterruptedException {

        // A map of component version constraints {componentName => {dependentComponentName => versionConstraint}} to be
        // maintained and updated. This information needs to be tracked because: 1. One component can have multiple
        // dependent components posing different version constraints. 2. When the version of a dependent component
        // changes, the version constraints will also change accordingly. 3. The information also shows the complete
        // dependency tree.
        Map<String, Map<String, Requirement>> componentNameToVersionConstraints = new HashMap<>();

        // Get the target components with version requirements in the deployment document
        List<String> targetComponentsToResolve = new ArrayList<>();
        // Add the constraints of the target group to componentNameToVersionConstraints before
        // trying to resolve the other group so that the resolved version will be compatible with
        // both versions (if possible)
        for (DeploymentPackageConfiguration e : document.getDeploymentPackageConfigurationList()) {
            if (e.isRootComponent()) {
                logger.atDebug()
                        .kv(COMPONENT_NAME_KEY, e.getPackageName())
                        .kv(VERSION_KEY, e.getResolvedVersion())
                        .log("Found component configuration");
                componentNameToVersionConstraints.putIfAbsent(e.getPackageName(), new HashMap<>());
                try {
                    componentNameToVersionConstraints.get(e.getPackageName())
                            .put(document.getGroupName(), Requirement.buildNPM(e.getResolvedVersion()));
                } catch (Exception exception) {
                    throw new PackagingException(
                            String.format(
                                    "Unsupported component version '%s' for component '%s'. For pre-release "
                                            + "versions, please start the version tag with a non-numeric character",
                                    e.getResolvedVersion(), e.getPackageName()),
                            exception, COMPONENT_VERSION_NOT_VALID);
                }
                targetComponentsToResolve.add(e.getPackageName());
            }
        }

        // A map of component name to count of components that depend on it
        Map<String, Integer> componentIncomingReferenceCount = new HashMap<>();

        // A map of component name to its resolved version.
        Map<String, ComponentMetadata> resolvedComponents = new HashMap<>();

        Set<String> combinedTargetComponents = new LinkedHashSet<>(targetComponentsToResolve);
        combinedTargetComponents
                .addAll(getOtherGroupsTargetComponents(otherGroupsToRootComponents, componentNameToVersionConstraints));

        logger.atInfo()
                .setEventType("resolve-all-group-dependencies-start")
                .kv("allGroupTargets", combinedTargetComponents)
                .kv(COMPONENT_VERSION_REQUIREMENT_KEY, componentNameToVersionConstraints)
                .log("Start to resolve all groups dependencies");
        // populate all groups target components dependencies
        // resolve updated version from the cloud, update version requirement map
        for (String targetComponent : combinedTargetComponents) {
            resolveComponentDependencies(targetComponent, componentNameToVersionConstraints, resolvedComponents,
                    componentIncomingReferenceCount,
                    (name, requirements) -> componentManager.resolveComponentVersion(name, requirements));
        }

        // detect circular dependencies for target components from the current deployment
        for (String component : targetComponentsToResolve) {
            detectCircularDependency(component, resolvedComponents);
        }

        List<ComponentIdentifier> resolvedComponentIdentifiers = resolvedComponents.values()
                .stream()
                .map(ComponentMetadata::getComponentIdentifier)
                .collect(Collectors.toList());

        checkNonExplicitNucleusUpdate(targetComponentsToResolve, resolvedComponentIdentifiers);

        logger.atInfo()
                .setEventType("resolve-all-group-dependencies-finish")
                .kv("resolvedComponents", resolvedComponents)
                .kv(COMPONENT_VERSION_REQUIREMENT_KEY, componentNameToVersionConstraints)
                .log("Finish resolving all groups dependencies");
        return new ArrayList<>(resolvedComponentIdentifiers);
    }

    void checkNonExplicitNucleusUpdate(List<String> targetComponents, List<ComponentIdentifier> resolvedComponents)
            throws PackagingException {
        List<ComponentIdentifier> resolvedNucleusComponents = new ArrayList<>();
        for (ComponentIdentifier componentIdentifier : resolvedComponents) {
            if (ComponentType.NUCLEUS.equals(componentStore.getPackageRecipe(componentIdentifier).getComponentType())) {
                resolvedNucleusComponents.add(componentIdentifier);
            }
        }
        if (resolvedNucleusComponents.size() > 1) {
            throw new PackagingException(
                    String.format("Deployment cannot have more than 1 component of type Nucleus " + "%s",
                            resolvedNucleusComponents),
                    DeploymentErrorCode.MULTIPLE_NUCLEUS_RESOLVED_ERROR);
        }
        if (resolvedNucleusComponents.isEmpty()) {
            return;
        }
        Optional<GreengrassService> activeNucleusOption = kernel.orderedDependencies()
                .stream()
                .filter(s -> ComponentType.NUCLEUS.name().equals(s.getServiceType()))
                .findFirst();
        if (!activeNucleusOption.isPresent()) {
            return;
        }
        GreengrassService activeNucleus = activeNucleusOption.get();
        String activeNucleusVersionConfig = Coerce.toString(activeNucleus.getServiceConfig().find(VERSION_KEY));
        if (Utils.isEmpty(activeNucleusVersionConfig)) {
            throw new PackagingException(NO_ACTIVE_NUCLEUS_VERSION_ERROR_MSG,
                    DeploymentErrorCode.NUCLEUS_VERSION_NOT_FOUND);
        }
        Semver activeNucleusVersion = new Semver(activeNucleusVersionConfig);
        ComponentIdentifier activeNucleusId =
                new ComponentIdentifier(activeNucleus.getServiceName(), activeNucleusVersion);
        ComponentIdentifier resolvedNucleusId = resolvedNucleusComponents.get(0);

        if (!resolvedNucleusId.equals(activeNucleusId) && !targetComponents.contains(resolvedNucleusId.getName())) {
            Semver.VersionDiff diff = activeNucleusVersion.diff(resolvedNucleusId.getVersion());
            if (Semver.VersionDiff.MINOR.equals(diff) || Semver.VersionDiff.MAJOR.equals(diff)) {
                throw new PackagingException(
                        String.format(NON_EXPLICIT_NUCLEUS_UPDATE_ERROR_MESSAGE_FMT, activeNucleusId.getName(),
                                activeNucleusId.getVersion().toString(), resolvedNucleusId.getName(),
                                resolvedNucleusId.getVersion().toString()),
                        DeploymentErrorCode.UNAUTHORIZED_NUCLEUS_MINOR_VERSION_UPDATE);
            }
        }
    }

    private Set<String> getOtherGroupsTargetComponents(
            Map<String, Set<ComponentRequirementIdentifier>> otherGroupsRootComponents,
            Map<String, Map<String, Requirement>> componentNameToVersionConstraints) {
        Set<String> targetComponents = new HashSet<>();
        otherGroupsRootComponents.forEach((groupName, rootPackages) -> {
            rootPackages.forEach(component -> {
                targetComponents.add(component.getName());
                componentNameToVersionConstraints.putIfAbsent(component.getName(), new HashMap<>());
                componentNameToVersionConstraints.get(component.getName())
                        .put(groupName, component.getVersionRequirement());
            });
        });
        return targetComponents;
    }

    // Breadth first traverse of dependency tree, use component resolve to resolve every component
    private void resolveComponentDependencies(String targetComponentName,
            Map<String, Map<String, Requirement>> componentNameToVersionConstraints,
            Map<String, ComponentMetadata> resolvedComponents, Map<String, Integer> componentIncomingReferenceCount,
            ComponentResolver componentResolver) throws PackagingException, InterruptedException {
        logger.atDebug()
                .setEventType("traverse-dependencies-start")
                .kv("targetComponent", targetComponentName)
                .kv(COMPONENT_VERSION_REQUIREMENT_KEY, componentNameToVersionConstraints)
                .log("Start traversing dependencies");
        Queue<String> componentsToResolve = new LinkedList<>();
        componentsToResolve.add(targetComponentName);

        while (!componentsToResolve.isEmpty()) {
            String componentToResolve = componentsToResolve.poll();
            Map<String, Requirement> versionConstraints =
                    new HashMap<>(componentNameToVersionConstraints.get(componentToResolve));
            ComponentMetadata resolvedVersion = componentResolver.resolve(componentToResolve, versionConstraints);
            // Incrementing the incoming reference count
            componentIncomingReferenceCount.compute(resolvedVersion.getComponentIdentifier().getName(),
                    (key, value) -> value == null ? 1 : value + 1);
            logger.atDebug().kv("resolvedVersion", resolvedVersion).log("Resolved component");

            ComponentMetadata previousVersion = resolvedComponents.put(componentToResolve, resolvedVersion);

            if (previousVersion != null && !previousVersion.equals(resolvedVersion)) {
                logger.atDebug()
                        .kv("previousVersion", previousVersion)
                        .kv("newVersion", resolvedVersion)
                        .log("The resolved version of the component changed, updating the dependency tree");
                removeDependencies(previousVersion, resolvedComponents, componentIncomingReferenceCount,
                        componentNameToVersionConstraints);
            }
            // Skipping dependency resolution for the component as there is no change in the version.
            if (resolvedVersion.equals(previousVersion)) {
                continue;
            }
            for (Map.Entry<String, String> dependency : resolvedVersion.getDependencies().entrySet()) {
                componentNameToVersionConstraints.putIfAbsent(dependency.getKey(), new HashMap<>());
                componentNameToVersionConstraints.get(dependency.getKey())
                        .put(componentToResolve, Requirement.buildNPM(dependency.getValue()));
                componentsToResolve.add(dependency.getKey());
            }
        }

        logger.atDebug()
                .setEventType("traverse-dependencies-finish")
                .kv("resolvedComponents", resolvedComponents)
                .log("Finish traversing dependencies");
    }

    /*
     * A component version is removed from the dependency tree, remove all dependencies of this version which has an
     * incoming reference count of 1 (i.e no other component has depends on them)
     */
    private void removeDependencies(ComponentMetadata removedComponentVersion,
            Map<String, ComponentMetadata> resolvedComponents, Map<String, Integer> componentIncomingReferenceCount,
            Map<String, Map<String, Requirement>> componentNameToVersionConstraints) {

        Queue<ComponentMetadata> componentsToRemove = new LinkedList<>();
        componentsToRemove.add(removedComponentVersion);
        while (!componentsToRemove.isEmpty()) {
            ComponentMetadata removedComponent = componentsToRemove.poll();
            for (Map.Entry<String, String> dependency : removedComponent.getDependencies().entrySet()) {
                // removing version constraints from removed component
                componentNameToVersionConstraints.get(dependency.getKey())
                        .remove(removedComponent.getComponentIdentifier().getName());
                componentIncomingReferenceCount.compute(dependency.getKey(), (key, value) -> {
                    if (value == null) {
                        return null;
                    } else if (value == 1) {
                        // only removedComponent depend on this component. This component can be removed.
                        ComponentMetadata component = resolvedComponents.remove(key);
                        logger.atDebug().kv("version", component).log("Removing component");
                        // adding the component to componentsToRemove, to clean up its dependencies
                        componentsToRemove.add(component);
                        return null;
                    } else {
                        // count down the incoming reference count for the dependency
                        return value - 1;
                    }
                });
            }
        }
    }

    private void detectCircularDependency(String targetComponent, Map<String, ComponentMetadata> resolvedComponents)
            throws ComponentVersionNegotiationException {
        Map<String, Set<String>> componentDependencyMap = new HashMap();
        Queue<String> componentsToVisit = new LinkedList<>();
        componentsToVisit.add(targetComponent);
        while (!componentsToVisit.isEmpty()) {
            String componentName = componentsToVisit.poll();
            Set<String> dependencies = resolvedComponents.get(componentName).getDependencies().keySet();
            componentDependencyMap.put(componentName, dependencies);
            dependencies.stream()
                    .filter(dependency -> !componentDependencyMap.containsKey(dependency))
                    .forEach(componentsToVisit::add);
        }
        int componentCount = componentDependencyMap.keySet().size();
        LinkedHashSet<String> result = new DependencyOrder<String>()
                .computeOrderedDependencies(componentDependencyMap.keySet(), componentDependencyMap::get);

        if (result.size() != componentCount) {
            throw new ComponentVersionNegotiationException("Circular dependency detected for component "
                    + resolvedComponents.get(targetComponent).getComponentIdentifier().toString());
        }
    }

    @FunctionalInterface
    public interface ComponentResolver {
        ComponentMetadata resolve(String name, Map<String, Requirement> requirements)
                throws PackagingException, InterruptedException;
    }
}
