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
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.exceptions.DeploymentTaskFailureException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.deployment.model.DeploymentTask;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.util.Coerce;
import com.vdurmont.semver4j.Semver;
import lombok.Getter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static com.aws.greengrass.deployment.DeploymentConfigMerger.DEPLOYMENT_ID_LOG_KEY;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY;
import static com.aws.greengrass.deployment.converter.DeploymentDocumentConverter.LOCAL_DEPLOYMENT_GROUP_NAME;

/**
 * A task of deploying a configuration specified by a deployment document to a Greengrass device.
 */
public class DefaultDeploymentTask implements DeploymentTask {
    private static final int FINITE_RETRY_COUNT = 10;
    private static final int INFINITE_RETRY_COUNT = Integer.MAX_VALUE;

    private static final String DEPLOYMENT_TASK_EVENT_TYPE = "deployment-task-execution";
    public static final String DEVICE_DEPLOYMENT_GROUP_NAME_PREFIX = "thing/";

    private final DependencyResolver dependencyResolver;
    private final ComponentManager componentManager;
    private final KernelConfigResolver kernelConfigResolver;
    private final DeploymentConfigMerger deploymentConfigMerger;
    private final ExecutorService executorService;
    private final Logger logger;
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
     * @param thingGroupHelper             Executor service
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public DefaultDeploymentTask(DependencyResolver dependencyResolver, ComponentManager componentManager,
                                 KernelConfigResolver kernelConfigResolver,
                                 DeploymentConfigMerger deploymentConfigMerger, Logger logger, Deployment deployment,
                                 Topics deploymentServiceConfig, ExecutorService executorService,
                                 DeploymentDocumentDownloader deploymentDocumentDownloader,
                                 ThingGroupHelper thingGroupHelper) {
        this.dependencyResolver = dependencyResolver;
        this.componentManager = componentManager;
        this.kernelConfigResolver = kernelConfigResolver;
        this.deploymentConfigMerger = deploymentConfigMerger;
        this.logger = logger.dfltKv(DEPLOYMENT_ID_LOG_KEY, deployment.getDeploymentDocumentObj().getDeploymentId());
        this.deployment = deployment;
        this.deploymentServiceConfig = deploymentServiceConfig;
        this.executorService = executorService;
        this.deploymentDocumentDownloader = deploymentDocumentDownloader;
        this.thingGroupHelper = thingGroupHelper;
    }

    @Override
    @SuppressWarnings({"PMD.PreserveStackTrace", "PMD.PrematureDeclaration"})
    public DeploymentResult call() throws InterruptedException {
        Future<List<ComponentIdentifier>> resolveDependenciesFuture = null;
        Future<Void> preparePackagesFuture = null;
        Future<DeploymentResult> deploymentMergeFuture = null;
        DeploymentDocument deploymentDocument = deployment.getDeploymentDocumentObj();
        try {
            logger.atInfo().setEventType(DEPLOYMENT_TASK_EVENT_TYPE)
                    .kv("Deployment service config", deploymentServiceConfig.toPOJO().toString())
                    .log("Starting deployment task");


            Map<String, Set<ComponentIdentifier>> nonTargetGroupsToRootPackagesMap =
                    getNonTargetGroupToRootPackagesMap(deploymentDocument);

            // Root packages for the target group is taken from deployment document.
            Set<String> rootPackages = new HashSet<>(deploymentDocument.getRootPackages());
            // Add root components from non-target groups.
            nonTargetGroupsToRootPackagesMap.values().forEach(packages -> {
                packages.forEach(p -> rootPackages.add(p.getName()));
            });

            resolveDependenciesFuture = executorService.submit(() ->
                    dependencyResolver.resolveDependencies(deploymentDocument, nonTargetGroupsToRootPackagesMap));

            List<ComponentIdentifier> desiredPackages = resolveDependenciesFuture.get();

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

            Map<String, Object> newConfig =
                    kernelConfigResolver.resolve(desiredPackages, deploymentDocument, new ArrayList<>(rootPackages));
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Deployment task is interrupted");
            }
            deploymentMergeFuture = deploymentConfigMerger.mergeInNewConfig(deployment, newConfig);

            // Block this without timeout because it can take a long time for the device to update the config
            // (if it's not in a safe window).
            DeploymentResult result = deploymentMergeFuture.get();

            logger.atInfo(DEPLOYMENT_TASK_EVENT_TYPE).setEventType(DEPLOYMENT_TASK_EVENT_TYPE)
                    .log("Finished deployment task");

            componentManager.cleanupStaleVersions();
            return result;
        } catch (PackageLoadingException | DeploymentTaskFailureException | IOException e) {
            logger.atError().setCause(e).log("Error occurred while processing deployment");
            return new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, e);
        } catch (ExecutionException e) {
            logger.atError().setCause(e).log("Error occurred while processing deployment");
            Throwable t = e.getCause();
            if (t instanceof InterruptedException) {
                throw (InterruptedException) t;
            } else {
                return new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, t);
            }
        } catch (InterruptedException e) {
            // DeploymentTask got interrupted while performing a blocking step.
            cancelDeploymentTask(resolveDependenciesFuture, preparePackagesFuture, deploymentMergeFuture);
            // Populate the exception up to the stack
            throw e;
        }
    }


    private Map<String, Set<ComponentIdentifier>> getNonTargetGroupToRootPackagesMap(
            DeploymentDocument deploymentDocument)
            throws DeploymentTaskFailureException, InterruptedException {
        Map<String, Set<ComponentIdentifier>> nonTargetGroupsToRootPackagesMap = new HashMap<>();

        Topics groupsToRootPackages =
                deploymentServiceConfig.lookupTopics(DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS);

        // Avoid blocking local deployments due to device being offline by using finite retries for getting the
        // hierarchy. For cloud deployment, use infinite retries by default similar to all other cloud
        // interactions.
        int retryCount =
                deploymentDocument.getGroupName().equalsIgnoreCase(LOCAL_DEPLOYMENT_GROUP_NAME) ? FINITE_RETRY_COUNT
                        : INFINITE_RETRY_COUNT;

        Optional<Set<String>> groupsDeviceBelongsToOptional = thingGroupHelper.listThingGroupsForDevice(retryCount);
        groupsToRootPackages.iterator().forEachRemaining(node -> {
            Topics groupTopics = (Topics) node;
            // skip group the deployment is targeting as the root packages for it are taken from the deployment document
            // skip root packages if device does not belong to that group anymore
            if (!groupTopics.getName().equals(deploymentDocument.getGroupName())
                    && (groupTopics.getName().startsWith(DEVICE_DEPLOYMENT_GROUP_NAME_PREFIX)
                    || groupTopics.getName().equals(LOCAL_DEPLOYMENT_GROUP_NAME)
                    || groupsDeviceBelongsToOptional.isPresent() && groupsDeviceBelongsToOptional.get()
                    .contains(groupTopics.getName()))) {
                groupTopics.forEach(pkgNode -> {
                    Topics pkgTopics = (Topics) pkgNode;
                    Semver version = new Semver(Coerce.toString(pkgTopics
                            .lookup(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY)));
                    nonTargetGroupsToRootPackagesMap.putIfAbsent(groupTopics.getName(), new HashSet<>());
                    nonTargetGroupsToRootPackagesMap.get(groupTopics.getName())
                            .add(new ComponentIdentifier(pkgTopics.getName(), version));
                });
            }
        });

        deploymentServiceConfig.lookupTopics(DeploymentService.GROUP_MEMBERSHIP_TOPICS).remove();
        Topics groupMembership =
                deploymentServiceConfig.lookupTopics(DeploymentService.GROUP_MEMBERSHIP_TOPICS);

        if (groupsDeviceBelongsToOptional.isPresent()) {
            groupsDeviceBelongsToOptional.get().forEach(groupName -> groupMembership.createLeafChild(groupName));
        }

        return nonTargetGroupsToRootPackagesMap;
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
