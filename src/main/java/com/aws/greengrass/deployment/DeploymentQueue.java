/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.Deployment.DeploymentType;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;

import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;

public class DeploymentQueue {

    private static final Logger logger = LogManager.getLogger(DeploymentQueue.class);
    private final ConcurrentLinkedQueue<Deployment> deploymentsQueue = new ConcurrentLinkedQueue();

    /**
     * Add a deployment to the queue.
     *
     * @param deployment deployment
     */
    public synchronized boolean offer(Deployment deployment) {
        //For shadow deployment when desired state is reverted, it can result in scheduling a deployment which is
        // same as
        if (!DeploymentType.SHADOW.equals(deployment.getDeploymentType()) && deploymentsQueue.contains(deployment)) {
            return false;
        }
        return deploymentsQueue.offer(deployment);
    }

    /**
     * Get the next deployment to be deployed.
     *
     * @return deployment
     */
    public synchronized Deployment peek() {
        Deployment deployment = deploymentsQueue.peek();
        // Discarding is not done at schedule time because the DeploymentService does not remove the deployments
        // atomically DeploymentService first peeks and determine if the next deployment is actionable.
        while (deployment != null && canDeploymentBeDiscarded(deployment)) {
            logger.atInfo().kv("DEPLOYMENT_ID", deployment.getId())
                    .kv("DEPLOYMENT_TYPE", deployment.getDeploymentType())
                    .log("Discarding device deployment");
            deploymentsQueue.remove();
            deployment = deploymentsQueue.peek();
        }
        return deployment;
    }

    /**
     * Removed the deployment from the head of the queue.
     */
    public synchronized void remove() {
        deploymentsQueue.remove();
    }

    public boolean isEmpty() {
        return deploymentsQueue.isEmpty();
    }

    private boolean canDeploymentBeDiscarded(Deployment selectedDeployment) {
        // If the selected deployment is of type shadow and there is another deployment in the queue
        // the selected deployment can be discarded. ShadowDeploymentListener ensures that shadow deployments are
        // queued based on the order in which they are created in the cloud.
        if (DeploymentType.SHADOW.equals(selectedDeployment.getDeploymentType())) {
            Iterator<Deployment> iterator = deploymentsQueue.iterator();
            while (iterator.hasNext()) {
                Deployment nextDeployment = iterator.next();
                if (!selectedDeployment.equals(nextDeployment)
                        && nextDeployment.getDeploymentType().equals(DeploymentType.SHADOW)) {
                    return true;
                }
            }
        }
        return false;
    }
}
