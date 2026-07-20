/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.amazon.aws.iot.greengrass.configuration.common.DeploymentCapability;
import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.componentmanager.DependencyResolver;
import com.aws.greengrass.componentmanager.KernelConfigResolver;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.ComponentRequirementIdentifier;
import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.exceptions.DeploymentTaskFailureException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentPackageConfiguration;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.deployment.model.DeploymentTask;
import com.aws.greengrass.deployment.model.LocalOverrideRequest;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.SerializerFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.vdurmont.semver4j.Requirement;
import lombok.Getter;
import software.amazon.awssdk.http.HttpStatusCode;
import software.amazon.awssdk.services.greengrassv2data.model.GreengrassV2DataException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeploymentConfigMerger.DEPLOYMENT_ID_LOG_KEY;
import static com.aws.greengrass.deployment.DeploymentService.COMPONENTS_TO_GROUPS_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEPLOYMENT_CONFIGURATION_TIME_SOURCE_DEPLOYMENT_PROCESSING_TIME;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_DEPLOYMENT_CONFIGURATION_TIME_SOURCE;
import static com.aws.greengrass.deployment.converter.DeploymentDocumentConverter.LOCAL_DEPLOYMENT_GROUP_NAME;

/**
 * A task of deploying a configuration specified by a deployment document to a Greengrass device.
 */
public class DefaultDeploymentTask implements DeploymentTask {
    private static final int INFINITE_RETRY_COUNT = Integer.MAX_VALUE;

    private static final String DEPLOYMENT_TASK_EVENT_TYPE = "deployment-task-execution";
    public static final String DEVICE_DEPLOYMENT_GROUP_NAME_PREFIX = "thing/";
    /**
     * Pseudo-group key under which running root components with no positive removal evidence are preserved in
     * the resolution root set. Never persisted to config; only used in-memory for dependency resolution and in
     * version-constraint diagnostics.
     */
    public static final String RUNNING_ROOTS_PRESERVATION_KEY = "currently-running-components";

    private final DependencyResolver dependencyResolver;
    private final ComponentManager componentManager;
    private final KernelConfigResolver kernelConfigResolver;
    private final DeploymentConfigMerger deploymentConfigMerger;
    private final ExecutorService executorService;
    private final Logger logger;
    private final DeviceConfiguration deviceConfiguration;
    private final Kernel kernel;
    @Getter
    private final Deployment deployment;
    private final Topics deploymentServiceConfig;

    private final DeploymentDocumentDownloader deploymentDocumentDownloader;
    private final ThingGroupHelper thingGroupHelper;

    /**
     * Constructor for DefaultDeploymentTask.
     *
     * @param dependencyResolver           DependencyResolver instance
     * @param componentManager             PackageManager instance
     * @param kernelConfigResolver         KernelConfigResolver instance
     * @param deploymentConfigMerger       DeploymentConfigMerger instance
     * @param logger                       Logger instance
     * @param deployment                   Deployment instance
     * @param deploymentServiceConfig      Deployment service configuration Topics
     * @param executorService              Executor service
     * @param deploymentDocumentDownloader download large deployment document.
     * @param thingGroupHelper             Thing Group Helper / Retriever
     * @param deviceConfiguration          Device Configuration Information
     * @param kernel                       Kernel instance, used to read the currently running root components
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public DefaultDeploymentTask(DependencyResolver dependencyResolver, ComponentManager componentManager,
                                 KernelConfigResolver kernelConfigResolver,
                                 DeploymentConfigMerger deploymentConfigMerger, Logger logger, Deployment deployment,
                                 Topics deploymentServiceConfig, ExecutorService executorService,
                                 DeploymentDocumentDownloader deploymentDocumentDownloader,
                                 ThingGroupHelper thingGroupHelper,
                                 DeviceConfiguration deviceConfiguration,
                                 Kernel kernel) {
        this.dependencyResolver = dependencyResolver;
        this.componentManager = componentManager;
        this.kernelConfigResolver = kernelConfigResolver;
        this.deploymentConfigMerger = deploymentConfigMerger;
        this.logger = logger.dfltKv(DEPLOYMENT_ID_LOG_KEY, deployment.getGreengrassDeploymentId());
        this.deployment = deployment;
        this.deploymentServiceConfig = deploymentServiceConfig;
        this.executorService = executorService;
        this.deploymentDocumentDownloader = deploymentDocumentDownloader;
        this.thingGroupHelper = thingGroupHelper;
        this.deviceConfiguration = deviceConfiguration;
        this.kernel = kernel;
    }

    @Override
    @SuppressWarnings({"PMD.PreserveStackTrace", "PMD.PrematureDeclaration"})
    public DeploymentResult call() throws InterruptedException {
        Future<List<ComponentIdentifier>> resolveDependenciesFuture = null;
        Future<Void> preparePackagesFuture = null;
        Future<DeploymentResult> deploymentMergeFuture = null;
        DeploymentResult deploymentResult = null;
        List<ComponentIdentifier> desiredPackages = null;
        DeploymentDocument deploymentDocument = deployment.getDeploymentDocumentObj();
        try {
            logger.atInfo().setEventType(DEPLOYMENT_TASK_EVENT_TYPE)
                    .kv("Deployment service config", deploymentServiceConfig.toPOJO().toString())
                    .log("Starting deployment task");

            Map<String, Set<ComponentRequirementIdentifier>> nonTargetGroupsToRootPackagesMap =
                    getNonTargetGroupToRootPackagesMap(deploymentDocument);

            // Root packages for the target group is taken from deployment document.
            Set<String> rootPackages = new HashSet<>(deploymentDocument.getRootPackages());
            // Add root components from non-target groups.
            nonTargetGroupsToRootPackagesMap.values().forEach(packages -> {
                packages.forEach(p -> rootPackages.add(p.getName()));
            });

            resolveDependenciesFuture = executorService.submit(() ->
                    dependencyResolver.resolveDependencies(deploymentDocument, nonTargetGroupsToRootPackagesMap));

            desiredPackages = resolveDependenciesFuture.get();

            // download configuration if large
            List<String> requiredCapabilities = deploymentDocument.getRequiredCapabilities();
            if (requiredCapabilities != null && requiredCapabilities
                    .contains(DeploymentCapability.LARGE_CONFIGURATION.toString())) {
                DeploymentDocument downloadedDeploymentDocument =
                        deploymentDocumentDownloader.download(deploymentDocument.getDeploymentId());

                deployment.getDeploymentDocumentObj().setDeploymentPackageConfigurationList(
                        downloadedDeploymentDocument.getDeploymentPackageConfigurationList());

            }

            // Check that all prerequisites for preparing components are met
            componentManager.checkPreparePackagesPrerequisites(desiredPackages);

            // Block this without timeout because a device can be offline and it can take quite a long time
            // to download a package.
            preparePackagesFuture = componentManager.preparePackages(desiredPackages);
            preparePackagesFuture.get();

            // Default to the deployment creation timestamp
            long timestamp = deploymentDocument.getTimestamp();

            // If the incoming deployment contains a requested deploymentConfigurationTimeSource,
            // use the incoming setting for processing the deployment itself.

            //   - First, get the name of the nucleus component, by searching through the component configs that were
            //   on the device before the deployment started, and finding one of type nucleus.
            Optional<DeploymentPackageConfiguration> incomingNucleusComponentConfiguration =
                    deploymentDocument.getDeploymentPackageConfigurationList() == null ? Optional.empty() :
                            deploymentDocument.getDeploymentPackageConfigurationList().stream()
                                    .filter(c -> c.getPackageName()
                                            .equals(deviceConfiguration.getNucleusComponentName()))
                                    .findAny();

            if (incomingNucleusComponentConfiguration.isPresent()
                    && incomingNucleusComponentConfiguration.get().getConfigurationUpdateOperation() != null
                    && incomingNucleusComponentConfiguration.get().getConfigurationUpdateOperation()
                        .getValueToMerge() != null
                    && incomingNucleusComponentConfiguration.get().getConfigurationUpdateOperation()
                        .getValueToMerge()
                        .containsKey(DEVICE_PARAM_DEPLOYMENT_CONFIGURATION_TIME_SOURCE)) {
                logger.atDebug(DEPLOYMENT_TASK_EVENT_TYPE).log(
                        "Incoming nucleus component configuration contains deployment configuration time source");
                String incomingDeploymentConfigurationTimeSource = Coerce.toString(
                        incomingNucleusComponentConfiguration
                                .get()
                                .getConfigurationUpdateOperation()
                                .getValueToMerge()
                                .get(DEVICE_PARAM_DEPLOYMENT_CONFIGURATION_TIME_SOURCE)
                );
                if (DEPLOYMENT_CONFIGURATION_TIME_SOURCE_DEPLOYMENT_PROCESSING_TIME
                        .equals(incomingDeploymentConfigurationTimeSource)) {
                    logger.atDebug(DEPLOYMENT_TASK_EVENT_TYPE).log(
                            "Incoming nucleus component configuration contains deployment configuration time "
                                    + "source set to deployment processing time");
                    timestamp = System.currentTimeMillis();
                }
            } else { // The incoming deployment does not specify deploymentConfigurationTimeSource
                logger.atDebug(DEPLOYMENT_TASK_EVENT_TYPE).log(
                        "Incoming nucleus component configuration does not contain deployment configuration time "
                                + "source");
                // Use it from the existing device configuration, if present
                if (DEPLOYMENT_CONFIGURATION_TIME_SOURCE_DEPLOYMENT_PROCESSING_TIME.equals(
                        Coerce.toString(deviceConfiguration.getDeploymentConfigurationTimeSource()))) {
                    logger.atDebug(DEPLOYMENT_TASK_EVENT_TYPE).log(
                            "Existing nucleus component configuration specifies deployment configuration time "
                                    + "source as deployment processing time");
                    timestamp = System.currentTimeMillis();
                }
            }
            logger.atDebug(DEPLOYMENT_TASK_EVENT_TYPE).log(
                    "Timestamp to be used for deployment configuration: " + timestamp);

            Map<String, Object> newConfig =
                    kernelConfigResolver.resolve(desiredPackages, deploymentDocument,
                            new ArrayList<>(rootPackages), timestamp);
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Deployment task is interrupted");
            }
            deploymentMergeFuture = deploymentConfigMerger.mergeInNewConfig(deployment, newConfig, timestamp);

            // Block this without timeout because it can take a long time for the device to update the config
            // (if it's not in a safe window).
            deploymentResult = deploymentMergeFuture.get();

            logger.atInfo(DEPLOYMENT_TASK_EVENT_TYPE).setEventType(DEPLOYMENT_TASK_EVENT_TYPE)
                    .log("Finished deployment task");

            return deploymentResult;
        } catch (PackageLoadingException | DeploymentTaskFailureException | IOException e) {
            logger.atError().setCause(e).log("Error occurred while processing deployment");
            deploymentResult = new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, e);
            return deploymentResult;
        } catch (ExecutionException e) {
            logger.atError().setCause(e).log("Error occurred while processing deployment");
            Throwable t = e.getCause();
            if (t instanceof InterruptedException) {
                throw (InterruptedException) t;
            } else {
                deploymentResult = new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, t);
                return deploymentResult;
            }
        } catch (InterruptedException e) {
            // DeploymentTask got interrupted while performing a blocking step.
            cancelDeploymentTask(resolveDependenciesFuture, preparePackagesFuture, deploymentMergeFuture);
            // Populate the exception up to the stack
            throw e;
        } finally {
            // Clean up stale artifacts from previous deployments after downloading deployment document
            // if we have desiredPackages then the artifact download has started
            Map<String, String> currentDeploymentComponentVersions = null;
            if (desiredPackages != null) {
                currentDeploymentComponentVersions = desiredPackages.stream()
                        .collect(Collectors.toMap(
                                ComponentIdentifier::getName,
                                ci -> ci.getVersion().getValue()));
                logger.atDebug(DEPLOYMENT_TASK_EVENT_TYPE)
                        .kv("Current deployment map", currentDeploymentComponentVersions.toString())
                        .log("Found current deployment artifacts, will preserve.");
            }
            boolean isDeploymentAborted = deploymentResult == null
                    || deploymentResult.getDeploymentStatus() != DeploymentResult.DeploymentStatus.SUCCESSFUL;
            componentManager.cleanupStaleVersions(isDeploymentAborted, currentDeploymentComponentVersions);
        }
    }

    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.AvoidRethrowingException"})
    private Map<String, Set<ComponentRequirementIdentifier>> getNonTargetGroupToRootPackagesMap(
            DeploymentDocument deploymentDocument)
            throws DeploymentTaskFailureException, InterruptedException {

        // Don't block local deployments due to device being offline by using finite retries for getting the
        // hierarchy and fall back to hierarchy stored previously in worst case. For cloud deployment, use infinite
        // retries by default similar/to all other cloud interactions.
        boolean isLocalDeployment = Deployment.DeploymentType.LOCAL.equals(deployment.getDeploymentType());
        // SDK already retries with RetryMode.STANDARD. For local deployment we don't retry on top of that
        int maxAttemptCount = isLocalDeployment ? 1 : INFINITE_RETRY_COUNT;

        // Whether the membership list below was freshly fetched from the cloud in this deployment run.
        // Only a fresh, successful fetch is authoritative evidence of group membership; every fallback
        // below produces a best-effort list that must not be used to justify removing anything.
        boolean membershipFetchedFromCloud = false;
        Optional<Set<String>> groupsForDeviceOpt;
        try {
            groupsForDeviceOpt = thingGroupHelper.listThingGroupsForDevice(maxAttemptCount);
            if (groupsForDeviceOpt.isPresent()) {
                membershipFetchedFromCloud = true;
            } else {
                // Membership is unknown (e.g. the device is not configured to talk to the cloud). Unknown
                // must not be treated as an authoritative "member of no thing groups" - that would drop all
                // thing group components from the merge and allow their group records to be cleaned up.
                // Fall back to the memberships implied by the persisted group records, as the failure
                // paths below do.
                logger.atDebug().log("Thing group membership is unknown, falling back to persisted membership");
                groupsForDeviceOpt = getPersistedMembershipInfo();
            }
        } catch (GreengrassV2DataException e) {
            if (e.statusCode() == HttpStatusCode.FORBIDDEN) {
                // Getting group hierarchy requires permission to call the ListThingGroupsForCoreDevice API which
                // may not be configured on existing IoT Thing policy in use for current device, log a warning in
                // that case and move on.
                logger.atWarn().setCause(e).log("Failed to get thing group hierarchy. Deployment will proceed. "
                        + "To automatically clean up unused components, please add "
                        + "greengrass:ListThingGroupsForCoreDevice permission to your IoT Thing policy.");
                groupsForDeviceOpt = getPersistedMembershipInfo();
            } else {
                throw new DeploymentTaskFailureException("Error fetching thing group information", e);
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            if (isLocalDeployment && ThingGroupHelper.RETRYABLE_EXCEPTIONS.contains(e.getClass())) {
                logger.atWarn().setCause(e).log("Failed to get thing group hierarchy, local deployment will proceed");
                groupsForDeviceOpt = getPersistedMembershipInfo();
            } else {
                throw new DeploymentTaskFailureException("Error fetching thing group information", e);
            }
        }
        Set<String> groupsForDevice =
                groupsForDeviceOpt.isPresent() ? groupsForDeviceOpt.get() : Collections.emptySet();

        Map<String, Set<ComponentRequirementIdentifier>> nonTargetGroupsToRootPackagesMap = new HashMap<>();
        Topics groupsToRootPackages =
                deploymentServiceConfig.lookupTopics(DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS);

        groupsToRootPackages.iterator().forEachRemaining(node -> {
            Topics groupTopics = (Topics) node;
            // skip group the deployment is targeting as the root packages for it are taken from the deployment document
            // skip root packages if device does not belong to that group anymore
            if (!groupTopics.getName().equals(deploymentDocument.getGroupName())
                    && (groupTopics.getName().startsWith(DEVICE_DEPLOYMENT_GROUP_NAME_PREFIX)
                    || groupTopics.getName().equals(LOCAL_DEPLOYMENT_GROUP_NAME)
                    || groupsForDevice.contains(groupTopics.getName()))) {
                groupTopics.forEach(pkgNode -> {
                    Topics pkgTopics = (Topics) pkgNode;
                    Requirement versionReq = Requirement.buildNPM(Coerce.toString(pkgTopics
                            .lookup(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY)));
                    nonTargetGroupsToRootPackagesMap.putIfAbsent(groupTopics.getName(), new HashSet<>());
                    nonTargetGroupsToRootPackagesMap.get(groupTopics.getName())
                            .add(new ComponentRequirementIdentifier(pkgTopics.getName(), versionReq));
                });
            }
        });

        // Local deployments only: cloud deployment documents are authoritative full-state contracts for
        // their target group, and their removal semantics are unchanged. A local deployment is an explicit
        // add/remove delta and has no authority to implicitly remove running components.
        if (isLocalDeployment) {
            preserveUnaccountedRunningRootComponents(deploymentDocument, nonTargetGroupsToRootPackagesMap,
                    groupsForDevice, membershipFetchedFromCloud);
        }

        deploymentServiceConfig.lookupTopics(DeploymentService.GROUP_MEMBERSHIP_TOPICS).remove();
        Topics groupMembership =
                deploymentServiceConfig.lookupTopics(DeploymentService.GROUP_MEMBERSHIP_TOPICS);
        groupsForDevice.forEach(groupMembership::createLeafChild);

        return nonTargetGroupsToRootPackagesMap;
    }

    /**
     * Preserve currently-running root components of a LOCAL deployment that are not accounted for by the
     * deployment document or the persisted group records, unless there is positive evidence that they should
     * be removed.
     *
     * <p>A root component that is currently running was put there by a previous deployment. The local bookkeeping
     * that normally preserves it across local deployments ({@code GroupToRootComponents}) can be lost or incomplete
     * independently of the component itself, and its absence is not evidence that the component should go away.
     * Without this preservation, a local deployment processed while a group's record is missing silently removes
     * that group's components in the merge, even when the cloud confirms the device still belongs to the group.
     *
     * <p>Positive evidence that a running root component should be removed by a local deployment is exactly one of:
     * <ul>
     * <li>the request explicitly lists it in {@code rootComponentsToRemove};</li>
     * <li>the thing group membership freshly fetched from the cloud in this run shows the device no longer belongs
     * to any thing group the component is attributed to.</li>
     * </ul>
     * Components preserved here are pinned to their currently-running version and participate in dependency
     * resolution only; no group bookkeeping is written for them. Cloud deployment semantics are unchanged: a
     * component is removed when the cloud deployment for its group no longer contains it.
     */
    private void preserveUnaccountedRunningRootComponents(DeploymentDocument deploymentDocument,
            Map<String, Set<ComponentRequirementIdentifier>> nonTargetGroupsToRootPackagesMap,
            Set<String> groupsForDevice, boolean membershipFetchedFromCloud) {
        Set<String> accountedFor = new HashSet<>(deploymentDocument.getRootPackages());
        nonTargetGroupsToRootPackagesMap.values()
                .forEach(packages -> packages.forEach(p -> accountedFor.add(p.getName())));
        Set<String> explicitlyRemoved = getComponentsExplicitlyRemovedByLocalRequest();

        String nucleusComponentName = deviceConfiguration.getNucleusComponentName();
        for (GreengrassService service : kernel.getMain().getDependencies().keySet()) {
            String componentName = service.getServiceName();
            // The nucleus component is a permanent dependency of main with its own lifecycle machinery;
            // it is never group-managed and must not be pulled into resolution by preservation.
            if (service.isBuiltin() || componentName.equals(nucleusComponentName)
                    || accountedFor.contains(componentName) || explicitlyRemoved.contains(componentName)) {
                continue;
            }
            Set<String> attributedGroups = getAttributedGroups(componentName);
            if (hasPositiveRemovalEvidence(attributedGroups, membershipFetchedFromCloud, groupsForDevice)) {
                continue;
            }
            String runningVersion = Coerce.toString(service.getServiceConfig().find(VERSION_CONFIG_KEY));
            if (runningVersion == null) {
                // Fail closed: without a version there is nothing safe to pin the component to, and an open
                // version requirement could resolve a different (e.g. cloud) component of the same name.
                logger.atWarn().kv("component", componentName)
                        .log("Not preserving running root component with no version in its configuration");
                continue;
            }
            nonTargetGroupsToRootPackagesMap
                    .computeIfAbsent(RUNNING_ROOTS_PRESERVATION_KEY, k -> new HashSet<>())
                    .add(new ComponentRequirementIdentifier(componentName, Requirement.buildNPM(runningVersion)));
            logger.atWarn().kv("component", componentName).kv("version", runningVersion)
                    .kv("attributedGroups", attributedGroups)
                    .log("Preserving running root component that no local group record accounts for; local"
                            + " deployment bookkeeping may have been lost. The component is kept at its running"
                            + " version. To remove it, deploy the change through its thing group or remove it"
                            + " explicitly with a local deployment");
        }
    }

    /**
     * Decide whether there is positive evidence that a running root component should be removed by this local
     * deployment. See {@link #preserveUnaccountedRunningRootComponents}.
     *
     * @param attributedGroups           groups the component is attributed to in local ComponentToGroups records
     * @param membershipFetchedFromCloud whether the membership list is a fresh, authoritative cloud response
     * @param groupsForDevice            the membership list for this run
     */
    private boolean hasPositiveRemovalEvidence(Set<String> attributedGroups, boolean membershipFetchedFromCloud,
            Set<String> groupsForDevice) {
        if (attributedGroups.isEmpty()) {
            // Unattributed: local bookkeeping knows nothing about this component. Absence of bookkeeping is
            // not evidence of anything.
            return false;
        }
        for (String group : attributedGroups) {
            boolean membershipGoverned = !group.startsWith(DEVICE_DEPLOYMENT_GROUP_NAME_PREFIX)
                    && !group.equals(LOCAL_DEPLOYMENT_GROUP_NAME);
            if (!membershipGoverned || !membershipFetchedFromCloud || groupsForDevice.contains(group)) {
                // This attribution cannot be dismissed: it is device/local scoped, or we have no authoritative
                // membership, or the device is still a member of the group.
                return false;
            }
        }
        return true;
    }

    /**
     * Groups a component is attributed to in the local ComponentToGroups bookkeeping.
     */
    private Set<String> getAttributedGroups(String componentName) {
        Set<String> groups = new HashSet<>();
        Node mappingNode = deploymentServiceConfig.findNode(COMPONENTS_TO_GROUPS_TOPICS, componentName);
        if (mappingNode instanceof Topics) {
            ((Topics) mappingNode).forEach(node -> {
                if (node instanceof Topic) {
                    String groupName = Coerce.toString((Topic) node);
                    if (groupName != null) {
                        groups.add(groupName);
                    }
                }
            });
        }
        return groups;
    }

    /**
     * Components a local deployment request explicitly asks to remove. Explicit removal is always honored,
     * even when the local bookkeeping that would normally reflect it is missing.
     */
    private Set<String> getComponentsExplicitlyRemovedByLocalRequest() {
        if (!Deployment.DeploymentType.LOCAL.equals(deployment.getDeploymentType())
                || deployment.getDeploymentDocument() == null) {
            return Collections.emptySet();
        }
        try {
            LocalOverrideRequest request = SerializerFactory.getFailSafeJsonObjectMapper()
                    .readValue(deployment.getDeploymentDocument(), LocalOverrideRequest.class);
            if (request.getComponentsToRemove() == null) {
                return Collections.emptySet();
            }
            return new HashSet<>(request.getComponentsToRemove());
        } catch (JsonProcessingException e) {
            // The document was parsed successfully upstream; a failure here should not block the deployment.
            logger.atWarn().setCause(e).log("Unable to re-read local override request for explicit removals");
            return Collections.emptySet();
        }
    }

    /*
     * Get device's thing group membership info stored in config as last obtained from cloud
     */
    private Optional<Set<String>> getPersistedMembershipInfo() {
        Topics groupsToRootPackages =
                deploymentServiceConfig.lookupTopics(DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS);
        return Optional.of(groupsToRootPackages.children.values().stream().map(Node::getName)
                .filter(g -> !LOCAL_DEPLOYMENT_GROUP_NAME.equals(g))
                .filter(g -> !g.startsWith(DEVICE_DEPLOYMENT_GROUP_NAME_PREFIX))
                .collect(Collectors.toSet()));
    }

    private void cancelDeploymentTask(Future<List<ComponentIdentifier>> resolveDependenciesFuture,
                                      Future<Void> preparePackagesFuture,
                                      Future<DeploymentResult> deploymentMergeFuture) {
        if (resolveDependenciesFuture != null && !resolveDependenciesFuture.isDone()) {
            resolveDependenciesFuture.cancel(true);
            logger.atInfo(DEPLOYMENT_TASK_EVENT_TYPE).log("Cancelled dependency resolution due to received interrupt");
            return;
        }
        // Stop downloading packages since the task was cancelled
        if (preparePackagesFuture != null && !preparePackagesFuture.isDone()) {
            preparePackagesFuture.cancel(true);
            logger.atInfo(DEPLOYMENT_TASK_EVENT_TYPE).log("Cancelled package download due to received interrupt");
            return;
        }
        // Cancel deployment config merge future
        if (deploymentMergeFuture != null && !deploymentMergeFuture.isDone()) {
            deploymentMergeFuture.cancel(false);
            logger.atInfo(DEPLOYMENT_TASK_EVENT_TYPE)
                    .log("Cancelled deployment merge future due to interrupt, update may not get cancelled if"
                            + " it is already being applied");
        }
    }
}
