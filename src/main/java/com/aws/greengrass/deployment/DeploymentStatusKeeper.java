/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.aws.greengrass.deployment.model.Deployment.DeploymentType;

public class DeploymentStatusKeeper {

    public static final String PROCESSED_DEPLOYMENTS_TOPICS = "ProcessedDeployments";
    public static final String DEPLOYMENT_ID_KEY_NAME = "DeploymentId";
    public static final String CONFIGURATION_ARN_KEY_NAME = "ConfigurationArn";
    public static final String DEPLOYMENT_TYPE_KEY_NAME = "DeploymentType";
    public static final String DEPLOYMENT_STATUS_KEY_NAME = "DeploymentStatus";
    public static final String DEPLOYMENT_STATUS_DETAILS_KEY_NAME = "DeploymentStatusDetails";
    private static final Logger logger = LogManager.getLogger(DeploymentStatusKeeper.class);
    private final Map<DeploymentType, Map<String, Function<Map<String, Object>, Boolean>>> deploymentStatusConsumerMap
            = new ConcurrentHashMap<>();
    @Setter
    private DeploymentService deploymentService;
    private Topics processedDeployments;

    /**
     * Register call backs for receiving deployment status updates for a particular deployment type .
     *
     * @param type        determines which deployment type the call back consumes
     * @param consumer    deployment status details
     * @param serviceName subscribing service name
     * @return true if call back is registered.
     */
    public boolean registerDeploymentStatusConsumer(DeploymentType type,
                                                    Function<Map<String, Object>, Boolean> consumer,
                                                    String serviceName) {
        Map<String, Function<Map<String, Object>, Boolean>> map = deploymentStatusConsumerMap
                .getOrDefault(type, new ConcurrentHashMap<>());
        map.putIfAbsent(serviceName, consumer);
        return deploymentStatusConsumerMap.put(type, map) == null;
    }

    /**
     * Persist deployment status in kernel config.
     *
     * @param deploymentId     id for the deployment.
     * @param configurationArn arn for deployment target configuration.
     * @param deploymentType   type of deployment.
     * @param status           status of deployment.
     * @param statusDetails    other details of deployment status.
     * @throws IllegalArgumentException for invalid deployment type
     */
    public void persistAndPublishDeploymentStatus(String deploymentId, String configurationArn,
                                                  DeploymentType deploymentType, String status,
                                                  Map<String, Object> statusDetails) {

        //While this method is being run, another thread could be running the publishPersistedStatusUpdates
        // method which consumes the data in config from the same topics. These two thread needs to be synchronized
        synchronized (deploymentType) {
            logger.atDebug().kv(DEPLOYMENT_ID_KEY_NAME, deploymentId).kv(DEPLOYMENT_STATUS_KEY_NAME, status)
                    .log("Storing deployment status");
            Map<String, Object> deploymentDetails = new HashMap<>();
            deploymentDetails.put(DEPLOYMENT_ID_KEY_NAME, deploymentId);
            deploymentDetails.put(CONFIGURATION_ARN_KEY_NAME, configurationArn);
            deploymentDetails.put(DEPLOYMENT_TYPE_KEY_NAME, deploymentType.toString());
            deploymentDetails.put(DEPLOYMENT_STATUS_KEY_NAME, status);
            deploymentDetails.put(DEPLOYMENT_STATUS_DETAILS_KEY_NAME, statusDetails);
            //Each status update is uniquely stored
            Topics processedDeployments = getProcessedDeployments();
            Topics thisJob = processedDeployments.createInteriorChild(String.valueOf(System.currentTimeMillis()));
            thisJob.replaceAndWait(deploymentDetails);
            logger.atInfo().kv(DEPLOYMENT_ID_KEY_NAME, deploymentId).kv(DEPLOYMENT_STATUS_KEY_NAME, status)
                    .log("Stored deployment status");
        }
        publishPersistedStatusUpdates(deploymentType);
    }

    /**
     * Invokes the call-backs with persisted deployment status updates for deployments with specified type.
     * This is called by IotJobsHelper/MqttJobsHelper when connection is re-established to update cloud of all
     * all deployments the device performed when offline
     *
     * @param type deployment type
     */
    public void publishPersistedStatusUpdates(DeploymentType type) {
        synchronized (type) {
            Topics processedDeployments = getProcessedDeployments();
            ArrayList<Topics> deployments = new ArrayList<>();
            processedDeployments.forEach(node -> {
                Topics deploymentDetails = (Topics) node;
                DeploymentType deploymentType = Coerce.toEnum(DeploymentType.class, deploymentDetails
                        .find(DEPLOYMENT_TYPE_KEY_NAME));
                if (Objects.equals(deploymentType, type)) {
                    deployments.add(deploymentDetails);
                }
            });
            // Topics are stored as ConcurrentHashMaps which do not guarantee ordering of elements
            // We want the statuses to be updated in the cloud in the order in which they were processed on the device.
            // This will be accurate representation of what happened on the device, especially when deployment service
            // processes multiple deployments in the order in which they come. Additionally, a customer workflow can
            // depend on this order. If Group2 gets successfully updated before Group1 then customer workflow may
            // error out.
            List<Topics> sortedByTimestamp = deployments.stream().sorted((o1, o2) -> {
                if (o1.getModtime() > o2.getModtime()) {
                    return 1;
                }
                return -1;
            }).collect(Collectors.toList());

            List<Function<Map<String, Object>, Boolean>> consumers = getConsumersForDeploymentType(type);
            logger.atDebug().kv("deploymentType", type).kv("numberOfSubscribers", consumers.size())
                    .log("Updating status of persisted deployments to subscribers");
            for (Topics topics : sortedByTimestamp) {
                boolean allConsumersUpdated = consumers.stream()
                        .allMatch(consumer -> consumer.apply(topics.toPOJO()));
                if (!allConsumersUpdated) {
                    // If one deployment update fails, exit the loop to ensure the update order.
                    logger.atDebug().log("Unable to update status of persisted deployments. Retry later");
                    break;
                }
                processedDeployments.remove(topics);
            }
        }
    }

    /**
     * Gets the list of callback functions based on the Deployment Type.
     *
     * @param type the type of deployment. {@link DeploymentType}
     * @return list of callback functions.
     */
    protected List<Function<Map<String, Object>, Boolean>> getConsumersForDeploymentType(DeploymentType type) {
        Map<String, Function<Map<String, Object>, Boolean>> stringFunctionMap = deploymentStatusConsumerMap.get(type);
        if (stringFunctionMap != null) {
            return new ArrayList<>(stringFunctionMap.values());
        }
        return Collections.emptyList();
    }

    /**
     * Get a reference to persisted deployment states Topics. Not thread-safe.
     *
     * @return Topics of persisted deployment states
     */
    protected Topics getProcessedDeployments() {
        if (processedDeployments == null) {
            processedDeployments = deploymentService.getRuntimeConfig().lookupTopics(PROCESSED_DEPLOYMENTS_TOPICS);
        }
        return processedDeployments;
    }
}
