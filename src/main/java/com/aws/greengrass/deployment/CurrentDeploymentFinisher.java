/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCodeUtils;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.KernelAlternatives;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Pair;
import com.aws.greengrass.util.Utils;
import lombok.Setter;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;

import static com.aws.greengrass.deployment.DefaultDeploymentTask.DEVICE_DEPLOYMENT_GROUP_NAME_PREFIX;
import static com.aws.greengrass.deployment.DeploymentService.COMPONENTS_TO_GROUPS_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_DETAILED_STATUS_KEY;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_ERROR_STACK_KEY;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_ERROR_TYPES_KEY;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_FAILURE_CAUSE_KEY;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_ID_LOG_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentService.GG_DEPLOYMENT_ID_LOG_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_MEMBERSHIP_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_LAST_DEPLOYMENT_CONFIG_ARN_KEY;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_LAST_DEPLOYMENT_TIMESTAMP_KEY;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_LAST_DEPLOYMENT_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_NAME;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY;
import static com.aws.greengrass.deployment.converter.DeploymentDocumentConverter.LOCAL_DEPLOYMENT_GROUP_NAME;
import static com.aws.greengrass.deployment.model.DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_NOT_REQUESTED;


public class CurrentDeploymentFinisher {

    private final Logger logger;
    @Inject
    @Setter
    private Deployment deployment;
    private final DeploymentStatusKeeper deploymentStatusKeeper;
    private final DeploymentDirectoryManager deploymentDirectoryManager;
    private final Topics config;

    @Inject
    private final Kernel kernel;

    /**
     * Constructor for DeploymentFinisher.
     *
     * @param logger                     Logger instance
     * @param deployment                 Deployment instance
     * @param deploymentStatusKeeper     {@link DeploymentStatusKeeper}
     * @param deploymentDirectoryManager {@link DeploymentDirectoryManager}
     * @param kernel                     {@link Kernel}
     * @param config                     root Configuration topic for Deployment service
     */
    public CurrentDeploymentFinisher(Logger logger, Deployment deployment,
                                     DeploymentStatusKeeper deploymentStatusKeeper,
                                     DeploymentDirectoryManager deploymentDirectoryManager, Topics config,
                                     Kernel kernel) {
        this.logger = logger;
        this.deployment = deployment;
        this.deploymentStatusKeeper = deploymentStatusKeeper;
        this.deploymentDirectoryManager = deploymentDirectoryManager;
        this.config = config;
        this.kernel = kernel;
    }

    void finishCurrentDeployment(DeploymentResult result, boolean isKernelUpdateTask) throws InterruptedException {
        String deploymentId = deployment.getId();
        String ggDeploymentId = deployment.getGreengrassDeploymentId();
        String configurationArn = deployment.getConfigurationArn();
        Deployment.DeploymentType type = deployment.getDeploymentType();
        List<String> rootPackages = deployment.getDeploymentDocumentObj().getRootPackages();

        logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY_NAME, deploymentId).kv(GG_DEPLOYMENT_ID_LOG_KEY_NAME, ggDeploymentId)
                .log("Current deployment finished");
        try {
            // No timeout is set here. Detection of error is delegated to downstream components like
            // dependency resolver, package downloader, kernel which will have more visibility
            // if something is going wrong
            // DeploymentResult result = currentDeploymentTaskMetadata.getDeploymentResultFuture().get();
            if (result != null) {
                DeploymentResult.DeploymentStatus deploymentStatus = result.getDeploymentStatus();
                Map<String, Object> statusDetails = new HashMap<>();
                statusDetails.put(DEPLOYMENT_DETAILED_STATUS_KEY, deploymentStatus.name());
                if (DeploymentResult.DeploymentStatus.SUCCESSFUL.equals(deploymentStatus)) {
                    //Add the root packages of successful deployment to the configuration
                    persistGroupToRootComponents(deployment.getDeploymentDocumentObj());

                    deploymentStatusKeeper.persistAndPublishDeploymentStatus(deploymentId, ggDeploymentId,
                            configurationArn, type, JobStatus.SUCCEEDED.toString(), statusDetails, rootPackages);

                    if (isKernelUpdateTask) {
                        try {
                            kernel.getContext().get(KernelAlternatives.class).activationSucceeds();
                        } catch (IOException e) {
                            logger.atError().log("Failed to reset Nucleus activate directory", e);
                        }
                    }
                    deploymentDirectoryManager.persistLastSuccessfulDeployment();
                } else if (DeploymentResult.DeploymentStatus.REJECTED.equals(deploymentStatus)) {
                    if (result.getFailureCause() != null) {
                        logger.atWarn().log("Deployment task rejected with following errors");
                        updateStatusDetailsFromException(statusDetails, result.getFailureCause(),
                                deployment.getDeploymentType());
                        logger.atWarn().setCause(result.getFailureCause()).kv(DEPLOYMENT_ID_LOG_KEY_NAME, deploymentId)
                                .kv(GG_DEPLOYMENT_ID_LOG_KEY_NAME, ggDeploymentId)
                                .kv(DEPLOYMENT_DETAILED_STATUS_KEY, result.getDeploymentStatus())
                                .kv(DEPLOYMENT_ERROR_STACK_KEY, statusDetails.get(DEPLOYMENT_ERROR_STACK_KEY))
                                .kv(DEPLOYMENT_ERROR_TYPES_KEY, statusDetails.get(DEPLOYMENT_ERROR_TYPES_KEY))
                                .log("Deployment task rejected with following errors");
                    }
                    deploymentStatusKeeper.persistAndPublishDeploymentStatus(deploymentId, ggDeploymentId,
                            configurationArn, type, JobStatus.REJECTED.toString(), statusDetails, rootPackages);
                } else {
                    if (result.getFailureCause() != null) {
                        updateStatusDetailsFromException(statusDetails, result.getFailureCause(),
                                deployment.getDeploymentType());
                        logger.atError().setCause(result.getFailureCause()).kv(DEPLOYMENT_ID_LOG_KEY_NAME, deploymentId)
                                .kv(GG_DEPLOYMENT_ID_LOG_KEY_NAME, ggDeploymentId)
                                .kv(DEPLOYMENT_DETAILED_STATUS_KEY, result.getDeploymentStatus())
                                .kv(DEPLOYMENT_ERROR_STACK_KEY, statusDetails.get(DEPLOYMENT_ERROR_STACK_KEY))
                                .kv(DEPLOYMENT_ERROR_TYPES_KEY, statusDetails.get(DEPLOYMENT_ERROR_TYPES_KEY))
                                .log("Deployment task failed with following errors");
                    }

                    if (FAILED_ROLLBACK_NOT_REQUESTED.equals(result.getDeploymentStatus())) {
                        // Update the groupToRootComponents mapping in config for the case where there is no rollback
                        // and now the components deployed for the current group are not the same as before deployment
                        persistGroupToRootComponents(deployment.getDeploymentDocumentObj());
                    }

                    deploymentStatusKeeper.persistAndPublishDeploymentStatus(deploymentId, ggDeploymentId,
                            configurationArn, type, JobStatus.FAILED.toString(), statusDetails, rootPackages);

                    if (isKernelUpdateTask) {
                        try {
                            kernel.getContext().get(KernelAlternatives.class).rollbackCompletes();
                        } catch (IOException e) {
                            logger.atError().log("Failed to reset Nucleus rollback directory", e);
                        }
                    }
                    deploymentDirectoryManager.persistLastFailedDeployment();
                }
            }
        } catch (CancellationException e) {
            logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY_NAME, deploymentId)
                    .kv(GG_DEPLOYMENT_ID_LOG_KEY_NAME, ggDeploymentId).log("Deployment task is cancelled");
        }
        // Setting this to null to indicate there is no current deployment being processed
        // Did not use optionals over null due to performance
        //        currentDeploymentTaskMetadata = null;
    }

    private void persistGroupToRootComponents(DeploymentDocument deploymentDocument) {
        Topics deploymentGroupTopics = config.lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS);
        Topics groupLastDeploymentTopics = config.lookupTopics(GROUP_TO_LAST_DEPLOYMENT_TOPICS);

        // clean up group
        cleanupGroupData(deploymentGroupTopics, groupLastDeploymentTopics);

        // persist group to root components
        Map<String, Object> deploymentGroupToRootPackages = new HashMap<>();
        deploymentDocument.getDeploymentPackageConfigurationList().stream().forEach(pkgConfig -> {
            if (pkgConfig.isRootComponent()) {
                Map<String, Object> pkgDetails = new HashMap<>();
                pkgDetails.put(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, pkgConfig.getResolvedVersion());
                pkgDetails.put(GROUP_TO_ROOT_COMPONENTS_GROUP_NAME, deploymentDocument.getGroupName());
                String configurationArn =
                        Utils.isEmpty(deploymentDocument.getConfigurationArn()) ? deploymentDocument.getDeploymentId()
                                : deploymentDocument.getConfigurationArn();
                pkgDetails.put(GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN, configurationArn);
                deploymentGroupToRootPackages.put(pkgConfig.getPackageName(), pkgDetails);
            }
        });

        // persist last deployment details
        Map<String, Object> lastDeploymentDetails = new HashMap<>();
        lastDeploymentDetails.put(GROUP_TO_LAST_DEPLOYMENT_TIMESTAMP_KEY, deploymentDocument.getTimestamp());
        lastDeploymentDetails.put(GROUP_TO_LAST_DEPLOYMENT_CONFIG_ARN_KEY, deploymentDocument.getConfigurationArn());
        groupLastDeploymentTopics.lookupTopics(deploymentDocument.getGroupName()).replaceAndWait(lastDeploymentDetails);

        // persist group to root packages mapping
        deploymentGroupTopics.lookupTopics(deploymentDocument.getGroupName())
                .replaceAndWait(deploymentGroupToRootPackages);
        setComponentsToGroupsMapping(deploymentGroupTopics);
    }

    /**
     * Group memberships for a device can change. If the device is no longer part of a group, then perform cleanup.
     */
    private void cleanupGroupData(Topics deploymentGroupTopics, Topics groupLastDeploymentTopics) {
        Topics groupMembershipTopics = config.lookupTopics(GROUP_MEMBERSHIP_TOPICS);
        deploymentGroupTopics.forEach(node -> {
            if (node instanceof Topics) {
                Topics groupTopics = (Topics) node;
                if (groupMembershipTopics.find(groupTopics.getName()) == null && !groupTopics.getName()
                        .startsWith(DEVICE_DEPLOYMENT_GROUP_NAME_PREFIX) && !groupTopics.getName()
                        .equals(LOCAL_DEPLOYMENT_GROUP_NAME)) {
                    logger.debug("Removing mapping for thing group " + groupTopics.getName());
                    groupTopics.remove();
                }
            }
        });

        groupLastDeploymentTopics.forEach(node -> {
            if (node instanceof Topics) {
                Topics groupTopics = (Topics) node;
                if (groupMembershipTopics.find(groupTopics.getName()) == null && !groupTopics.getName()
                        .startsWith(DEVICE_DEPLOYMENT_GROUP_NAME_PREFIX) && !groupTopics.getName()
                        .equals(LOCAL_DEPLOYMENT_GROUP_NAME)) {
                    logger.debug("Removing last deployment information for thing group " + groupTopics.getName());
                    groupTopics.remove();
                }
            }
        });
        groupMembershipTopics.remove();
    }

    void setComponentsToGroupsMapping(Topics groupsToRootComponents) {
        Set<String> pendingComponents = new HashSet<>();
        Map<String, Object> componentsToGroupsMappingCache = new ConcurrentHashMap<>();
        Topics componentsToGroupsTopics = config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS);
        /*
         * Structure of COMPONENTS_TO_GROUPS_TOPICS is:
         * COMPONENTS_TO_GROUPS_TOPICS :
         * |_ <componentName> :
         *     |_ <deploymentID> : <GroupName>
         * This stores all the components with the list of deployment IDs associated to it along with the thing group
         * (if available) to be associated to the deployment.
         */
        // Get all the groups associated to the root components.
        groupsToRootComponents.forEach(groupNode -> ((Topics) groupNode).forEach(componentNode -> {
            Topics componentTopics = (Topics) componentNode;

            Topic groupConfigTopic = componentTopics.lookup(GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN);
            String groupConfig = Coerce.toString(groupConfigTopic);

            Topic groupNameTopic = componentTopics.lookup(GROUP_TO_ROOT_COMPONENTS_GROUP_NAME);
            String groupName = Coerce.toString(groupNameTopic);

            Map<String, Object> groupDeploymentIdSet =
                    (Map<String, Object>) componentsToGroupsMappingCache.getOrDefault(componentTopics.getName(),
                            new HashMap<>());
            groupDeploymentIdSet.putIfAbsent(groupConfig, groupName);
            componentsToGroupsMappingCache.put(componentTopics.getName(), groupDeploymentIdSet);
            pendingComponents.add(componentTopics.getName());
        }));

        // Associate the groups to the dependant services based on the services it is depending on.
        while (!pendingComponents.isEmpty()) {
            String componentName = pendingComponents.iterator().next();
            try {
                GreengrassService greengrassService = kernel.locate(componentName);
                Map<String, Object> groupNamesForComponent =
                        (Map<String, Object>) componentsToGroupsMappingCache.getOrDefault(greengrassService.getName(),
                                new HashMap<>());

                greengrassService.getDependencies().forEach((greengrassService1, dependencyType) -> {
                    pendingComponents.add(greengrassService1.getName());
                    Map<String, Object> groupNamesForDependentComponent =
                            (Map<String, Object>) componentsToGroupsMappingCache.getOrDefault(
                                    greengrassService1.getName(), new HashMap<>());
                    groupNamesForDependentComponent.putAll(groupNamesForComponent);
                    componentsToGroupsMappingCache.put(greengrassService1.getName(), groupNamesForDependentComponent);
                });
            } catch (ServiceLoadException ex) {
                logger.atError().cause(ex).log("Unable to get status for {}.", componentName);
            }
            pendingComponents.remove(componentName);
        }

        if (componentsToGroupsTopics != null) {
            componentsToGroupsTopics.replaceAndWait(componentsToGroupsMappingCache);
        }
    }

    Map<String, Object> updateStatusDetailsFromException(Map<String, Object> statusDetails, Throwable failureCause,
                                                         Deployment.DeploymentType deploymentType) {
        Pair<List<String>, List<String>> errorReport =
                DeploymentErrorCodeUtils.generateErrorReportFromExceptionStack(failureCause, deploymentType);
        statusDetails.put(DEPLOYMENT_ERROR_STACK_KEY, errorReport.getLeft());
        statusDetails.put(DEPLOYMENT_ERROR_TYPES_KEY, errorReport.getRight());
        statusDetails.put(DEPLOYMENT_FAILURE_CAUSE_KEY, Utils.generateFailureMessage(failureCause));

        return statusDetails;
    }
}
