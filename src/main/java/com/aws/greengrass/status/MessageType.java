/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.status;

import com.aws.greengrass.deployment.model.Deployment.DeploymentType;

public enum MessageType {
    LOCAL_DEPLOYMENT,
    THING_DEPLOYMENT,
    THING_GROUP_DEPLOYMENT,
    BROKEN_COMPONENT,
    RECONNECT,
    CADENCE;

    /**
     * Get MessageTypeEnum from DeploymentType.
     *
     * @param deploymentType deploymentType
     * @return deployment message type
     * @throws IllegalArgumentException invalid deployment type
     */
    public static MessageType fromDeploymentType(DeploymentType deploymentType) {
        switch (deploymentType) {
            case LOCAL:
                return LOCAL_DEPLOYMENT;
            case SHADOW:
                return THING_DEPLOYMENT;
            case IOT_JOBS:
                return THING_GROUP_DEPLOYMENT;
            default:
                throw new IllegalArgumentException("Invalid deployment type: " + deploymentType);
        }
    }
}
