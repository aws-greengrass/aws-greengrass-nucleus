/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.componentmanager.DependencyResolver;
import com.aws.greengrass.componentmanager.KernelConfigResolver;
import com.aws.greengrass.componentmanager.exceptions.NoAvailableComponentVersionException;
import com.aws.greengrass.componentmanager.exceptions.PackagingException;
import com.aws.greengrass.componentmanager.exceptions.UnexpectedPackagingException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.exceptions.NonRetryableDeploymentTaskFailureException;
import com.aws.greengrass.deployment.exceptions.RetryableDeploymentTaskFailureException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.deployment.model.DeploymentTask;
import com.aws.greengrass.logging.api.Logger;
import lombok.Getter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static com.aws.greengrass.deployment.DeploymentConfigMerger.DEPLOYMENT_ID_LOG_KEY;

/**
 * A task of deploying a configuration specified by a deployment document to a Greengrass device.
 */
public class DefaultDeploymentTask implements DeploymentTask {
    private final DependencyResolver dependencyResolver;
    private final ComponentManager componentManager;
    private final KernelConfigResolver kernelConfigResolver;
    private final DeploymentConfigMerger deploymentConfigMerger;
    private final Logger logger;
    @Getter
    private final Deployment deployment;
    private final Topics deploymentServiceConfig;

    private static final String DEPLOYMENT_TASK_EVENT_TYPE = "deployment-task-execution";

    /**
     * Constructor for DefaultDeploymentTask.
     *
     * @param dependencyResolver DependencyResolver instance
     * @param componentManager PackageManager instance
     * @param kernelConfigResolver KernelConfigResolver instance
     * @param deploymentConfigMerger DeploymentConfigMerger instance
     * @param logger Logger instance
     * @param deployment Deployment instance
     * @param deploymentServiceConfig Deployment service configuration Topics
     */
    public DefaultDeploymentTask(DependencyResolver dependencyResolver, ComponentManager componentManager,
                                 KernelConfigResolver kernelConfigResolver,
                                 DeploymentConfigMerger deploymentConfigMerger, Logger logger, Deployment deployment,
                                 Topics deploymentServiceConfig) {
        this.dependencyResolver = dependencyResolver;
        this.componentManager = componentManager;
        this.kernelConfigResolver = kernelConfigResolver;
        this.deploymentConfigMerger = deploymentConfigMerger;
        this.logger = logger.dfltKv(DEPLOYMENT_ID_LOG_KEY, deployment.getDeploymentDocumentObj().getDeploymentId());
        this.deployment = deployment;
        this.deploymentServiceConfig = deploymentServiceConfig;
    }

    @Override
    @SuppressWarnings({"PMD.PreserveStackTrace", "PMD.PrematureDeclaration"})
    public DeploymentResult call()
            throws NonRetryableDeploymentTaskFailureException, RetryableDeploymentTaskFailureException {
        Future<Void> preparePackagesFuture = null;
        Future<DeploymentResult> deploymentMergeFuture = null;
        DeploymentDocument deploymentDocument = deployment.getDeploymentDocumentObj();
        try {
            logger.atInfo().setEventType(DEPLOYMENT_TASK_EVENT_TYPE)
                    .kv("Deployment service config", deploymentServiceConfig.toPOJO().toString())
                    .log("Starting deployment task");

            Set<String> rootPackages = new HashSet<>(deploymentDocument.getRootPackages());

            Topics groupsToRootPackages =
                    deploymentServiceConfig.lookupTopics(DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS);
            groupsToRootPackages.iterator().forEachRemaining(node -> {
                Topics groupTopics = (Topics) node;
                if (!groupTopics.getName().equals(deploymentDocument.getGroupName())) {
                    groupTopics.forEach(pkgTopic -> rootPackages.add(pkgTopic.getName()));
                }
            });

            List<ComponentIdentifier> desiredPackages =
                    dependencyResolver.resolveDependencies(deploymentDocument, groupsToRootPackages);

            // Block this without timeout because a device can be offline and it can take quite a long time
            // to download a package.
            preparePackagesFuture = componentManager.preparePackages(desiredPackages);
            preparePackagesFuture.get();

            Map<String, Object> newConfig =
                    kernelConfigResolver.resolve(desiredPackages, deploymentDocument, new ArrayList<>(rootPackages));
            if (Thread.currentThread().isInterrupted()) {
                logger.atInfo().log("Received interrupt before attempting deployment merge, skipping merge");
                return null;
            }
            deploymentMergeFuture = deploymentConfigMerger.mergeInNewConfig(deployment, newConfig);

            // Block this without timeout because it can take a long time for the device to update the config
            // (if it's not in a safe window).
            DeploymentResult result = deploymentMergeFuture.get();

            logger.atInfo(DEPLOYMENT_TASK_EVENT_TYPE).setEventType(DEPLOYMENT_TASK_EVENT_TYPE)
                    .log("Finished deployment task");

            componentManager.cleanupStaleVersions();
            return result;
        } catch (IOException | NoAvailableComponentVersionException | UnexpectedPackagingException e) {
            throw new NonRetryableDeploymentTaskFailureException(e);
        } catch (ExecutionException e) {
            Throwable t = e.getCause();
            if (t instanceof PackagingException || t instanceof InterruptedException || t instanceof IOException) {
                throw new RetryableDeploymentTaskFailureException(t);
            }
            throw new NonRetryableDeploymentTaskFailureException(t);
        } catch (InterruptedException e) {
            // DeploymentTask got interrupted while waiting or blocked on either prepare packages
            // or deployment merge step and landed here
            handleCancellation(preparePackagesFuture, deploymentMergeFuture);
            return null;
        } catch (PackagingException e) {
            throw new RetryableDeploymentTaskFailureException(e);
        }
    }

    /*
     * Handle deployment cancellation
     */
    private void handleCancellation(Future<Void> preparePackagesFuture,
                                    Future<DeploymentResult> deploymentMergeFuture) {
        // Stop downloading packages since the task was cancelled
        if (preparePackagesFuture != null && !preparePackagesFuture.isDone()) {
            preparePackagesFuture.cancel(true);
            logger.atInfo(DEPLOYMENT_TASK_EVENT_TYPE)
                    .log("Cancelled package download due to received interrupt");
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
