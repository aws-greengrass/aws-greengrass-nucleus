/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>DeploymentQueue is a thread-safe deployment queue that automatically de-duplicates by deployment id, and
 * also de-duplicates shadow deployments, i.e. there can be at-most-one shadow deployment enqueued.</p>
 *
 * <p>DeploymentQueue is implemented internally as a LinkedHashMap of id -> deployment.</p>
 *
 * <p>When an offered deployment has a unique deployment id, it is enqueued normally.</p>
 *
 * <p>When an offered deployment has the same deployment id as an already-enqueued element:
 *     - if the offered deployment meets replacement criteria, then the enqueued element is replaced by the offered
 *       element, preserving queue order.
 *     - otherwise, the offered deployment is ignored.</p>
 *
 * <p>Replacement criteria are as follows:
 *     - if enqueued.getDeploymentStage() != DEFAULT, then do not replace
 *     - else replace if:
 *          - offered.getDeploymentStage() != DEFAULT, OR
 *          - offered.isCancelled() == true, OR
 *          - offered.getDeploymentType().equals(SHADOW)</p>
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
     * Map of internal queue id -> deployment instance.
     */
    private final Map<String, Deployment> deploymentMap = new LinkedHashMap<>();

    /**
     * <p>If the offered deployment id is unique, then insert the offered deployment at the tail of the queue.</p>
     *
     * <p>When an offered deployment has the same deployment id as an already-enqueued element:
     *     - if the offered deployment meets replacement criteria, then the enqueued element is replaced by the offered
     *       element, preserving queue order.
     *     - otherwise, the offered deployment is ignored.</p>
     *
     * <p>Replacement criteria are as follows:
     *     - if enqueued.getDeploymentStage() != DEFAULT, then do not replace
     *     - else replace if:
     *          - offered.getDeploymentStage() != DEFAULT, OR
     *          - offered.isCancelled() == true, OR
     *          - offered.getDeploymentType().equals(SHADOW)</p>
     *
     * <p>When an offered deployment is a shadow deployment:
     *     - if there is already a shadow deployment in the queue, then replace it and preserve queue order.
     *     - otherwise, enqueue normally.</p>
     *
     * @param offeredDeployment the offered deployment instance.
     * @return true if the queue was modified, otherwise false.
     * @throws NullPointerException if the offered deployment is null.
     */
    @SuppressWarnings("PMD.AvoidThrowingNullPointerException")
    public synchronized boolean offer(Deployment offeredDeployment) {
        if (offeredDeployment == null) {
            throw new NullPointerException("Offered deployment must not be null");
        }
        final String offeredDeploymentInternalId; // the id to employ in the internal queue
        if (Deployment.DeploymentType.SHADOW.equals(offeredDeployment.getDeploymentType())) {
            offeredDeploymentInternalId = SHADOW_DEPLOYMENT_QUEUE_ID;
        } else {
            offeredDeploymentInternalId = offeredDeployment.getId();
        }

        if (deploymentMap.containsKey(offeredDeploymentInternalId)) {
            // internal queue id is already in use; check the replacement criteria
            final Deployment enqueuedDeployment = deploymentMap.get(offeredDeploymentInternalId);
            if (checkReplacementCriteria(enqueuedDeployment, offeredDeployment)) {
                logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY, offeredDeployment.getId())
                        .kv(DISCARDED_DEPLOYMENT_ID_LOG_KEY,
                                deploymentMap.get(offeredDeploymentInternalId) == null ? null
                                : deploymentMap.get(offeredDeploymentInternalId).getId())
                        .log("New deployment replacing enqueued deployment");
                deploymentMap.put(offeredDeploymentInternalId, offeredDeployment);
                return true;
            }
            logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY, offeredDeployment.getId())
                    .log("New deployment ignored as duplicate");
            return false;
        }

        // internal queue id is not in use; enqueue deployment normally
        deploymentMap.put(offeredDeploymentInternalId, offeredDeployment);
        return true;
    }

    private boolean checkReplacementCriteria(Deployment enqueued, Deployment offered) {
        // if the enqueued deployment is in non-DEFAULT stage, then do not replace
        if (!Deployment.DeploymentStage.DEFAULT.equals(enqueued.getDeploymentStage())) {
            return false;
        }
        // if the offered deployment is a new Shadow deployment, then it supersedes existing shadow deployment
        // if the offered deployment is a cancelled deployment, then it should replace the existing deployment
        if (Deployment.DeploymentType.SHADOW.equals(offered.getDeploymentType()) || offered.isCancelled()) {
            return true;
        }
        // if the offered deployment is in non-DEFAULT stage, then replace it
        return !Deployment.DeploymentStage.DEFAULT.equals(offered.getDeploymentStage());
    }

    /**
     * Retrieve and remove the deployment at the head of the queue, or return null if the queue is empty.
     *
     * @return the deployment retrieved from the head of the queue, or null if the queue is empty.
     */
    public synchronized Deployment poll() {
        final Iterator<String> deploymentMapIterator = deploymentMap.keySet().iterator();
        if (deploymentMapIterator.hasNext()) {
            return deploymentMap.remove(deploymentMapIterator.next());
        }
        return null; // queue is empty
    }

    /**
     * Return true if the queue contains no elements.
     *
     * @return true if the queue contains no elements, otherwise false.
     */
    public synchronized boolean isEmpty() {
        return deploymentMap.isEmpty();
    }

    /**
     * Return the contents of the queue as a list of deployments.
     *
     * @return the contents of the queue as a list of deployments.
     */
    public synchronized List<Deployment> toArray() {
        final List<Deployment> result = new ArrayList<>();
        deploymentMap.forEach((key, value) -> {
            result.add(value);
        });
        return result;
    }
}
