/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * <p>DeploymentQueue is a thread-safe deployment queue that automatically de-duplicates by deployment id, and
 * also de-duplicates shadow deployments, i.e. there can be at-most-one shadow deployment enqueued.</p>
 *
 * <p>DeploymentQueue is implemented internally as a deployment id queue, along with a map of id -> deployment.</p>
 *
 * <p>When an offered deployment has a unique deployment id, it is enqueued normally.</p>
 *
 * <p>When an offered deployment has the same deployment id as an already-enqueued element:
 *     - if the offered deployment meets replacement criteria, then the enqueued element is replaced by the offered
 *       element, preserving queue order.
 *     - otherwise, the offered deployment is ignored.</p>
 *
 * <p>Replacement criteria are as follows:
 *     - offered.getDeploymentStage().getPriority() > enqueued.getDeploymentStage().getPriority(), OR
 *     - offered.isCancelled() == true, OR
 *     - offered.getDeploymentType().equals(SHADOW)</p>
 *
 * <p>When an offered deployment is a shadow deployment:
 *     - if there is already a shadow deployment in the queue, then replace it and preserve queue order.
 *     - otherwise, enqueue normally.</p>
 */
public class DeploymentQueue {

    /**
     * For the internal queue, shadow deployments use a special queue id. Effectively, this means there can be at most
     * one shadow deployment in the queue.
     */
    private static final String SHADOW_DEPLOYMENT_QUEUE_ID = Deployment.DeploymentType.SHADOW.name();

    private static final String DEPLOYMENT_ID_LOG_KEY = "DeploymentId";
    private static final String DISCARDED_DEPLOYMENT_ID_LOG_KEY = "DiscardedDeploymentId";
    private static final Logger logger = LogManager.getLogger(DeploymentQueue.class);

    /**
     * Internal queue of deployment id's. For shadow deployments, the internal queue id is not the same as the
     * actual deployment id.
     */
    private final Queue<String> deploymentIdQueue = new LinkedList<>();

    /**
     * Map of internal queue id -> deployment instance.
     */
    private final Map<String, Deployment> deploymentMap = new HashMap<>();

    /**
     * <p>If the offered deployment id is unique, then insert the offered deployment at the tail of the queue.</p>
     *
     * <p>When an offered deployment has the same deployment id as an already-enqueued element:
     *     - if the offered deployment meets replacement criteria, then the enqueued element is replaced by the offered
     *       element, preserving queue order.
     *     - otherwise, the offered deployment is ignored.</p>
     *
     * <p>Replacement criteria are as follows:
     *     - offered.getDeploymentStage().getPriority() > enqueued.getDeploymentStage().getPriority(), OR
     *     - offered.isCancelled() == true, OR
     *     - offered.getDeploymentType().equals(SHADOW)</p>
     *
     * <p>When an offered deployment is a shadow deployment:
     *     - if there is already a shadow deployment in the queue, then replace it and preserve queue order.
     *     - otherwise, enqueue normally.</p>
     *
     * @param offeredDeployment the offered deployment instance.
     * @return true if the queue was modified, otherwise false.
     */
    public synchronized boolean offer(Deployment offeredDeployment) {
        if (offeredDeployment == null) {
            return false;
        }
        final String offeredDeploymentInternalId; // the id to employ in the internal queue
        if (Deployment.DeploymentType.SHADOW.equals(offeredDeployment.getDeploymentType())) {
            offeredDeploymentInternalId = SHADOW_DEPLOYMENT_QUEUE_ID;
        } else {
            offeredDeploymentInternalId = offeredDeployment.getId();
        }

        // is the internal queue id already in use?
        final String enqueuedDeploymentId = deploymentIdQueue.stream()
                .filter(enqueuedId -> enqueuedId.equals(offeredDeploymentInternalId))
                .findAny()
                .orElse(null);
        if (enqueuedDeploymentId != null) {
            // internal queue id is already in use
            final Deployment enqueuedDeployment = deploymentMap.get(enqueuedDeploymentId);
            if (enqueuedDeployment == null) {
                logger.atError().kv(DEPLOYMENT_ID_LOG_KEY, offeredDeployment.getId())
                        .kv("InternalQueueId", offeredDeploymentInternalId)
                        .log("Logic error: internal queue contains id with no corresponding deployment in map");
                return false;
            }
            // check the replacement criteria
            if (Deployment.DeploymentType.SHADOW.equals(offeredDeployment.getDeploymentType())
                    || offeredDeployment.isCancelled()
                    || offeredDeployment.getDeploymentStage().getPriority()
                    > enqueuedDeployment.getDeploymentStage().getPriority()) {
                logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY, offeredDeployment.getId())
                        .kv(DISCARDED_DEPLOYMENT_ID_LOG_KEY,
                                deploymentMap.get(enqueuedDeploymentId) == null ? null
                                : deploymentMap.get(enqueuedDeploymentId).getId())
                        .log("New deployment replacing enqueued deployment");
                deploymentMap.put(enqueuedDeploymentId, offeredDeployment);
                return true;
            }
            logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY, offeredDeployment.getId())
                    .log("New deployment ignored as duplicate");
            return false;
        }

        // internal queue id is not in use; enqueue deployment normally
        deploymentIdQueue.add(offeredDeploymentInternalId);
        deploymentMap.put(offeredDeploymentInternalId, offeredDeployment);
        return true;
    }

    /**
     * Retrieve and remove the deployment at the head of the queue, or return null if the queue is empty.
     *
     * @return the deployment retrieved from the head of the queue, or null if the queue is empty.
     */
    public synchronized Deployment poll() {
        final String id = deploymentIdQueue.poll();
        if (id == null) {
            return null; // queue is empty
        }
        return deploymentMap.remove(id);
    }

    /**
     * Return true if the queue contains no elements.
     *
     * @return true if the queue contains no elements, otherwise false.
     */
    public synchronized boolean isEmpty() {
        return deploymentIdQueue.isEmpty();
    }

    /**
     * Return the contents of the queue as a list of deployments.
     *
     * @return the contents of the queue as a list of deployments.
     */
    public synchronized List<Deployment> toArray() {
        final List<Deployment> result = new ArrayList<>();
        deploymentIdQueue.forEach(id -> {
            result.add(deploymentMap.get(id));
        });
        return result;
    }
}
