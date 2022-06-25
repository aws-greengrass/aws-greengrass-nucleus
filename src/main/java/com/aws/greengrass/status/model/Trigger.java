/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.status.model;

import com.aws.greengrass.deployment.model.Deployment.DeploymentType;

public enum Trigger {
    LOCAL_DEPLOYMENT,
    THING_DEPLOYMENT,
    THING_GROUP_DEPLOYMENT,
    BROKEN_COMPONENT,
    // when mqtt connection resumes
    RECONNECT,
    // when nucleus initially connects IoT Core, a complete FSS update is sent
    NUCLEUS_LAUNCH,
    // when nucleus device configs change, connection to IoT Core is reset and a complete FSS update is sent
    NETWORK_RECONFIGURE,
    // periodic FSS complete update
    CADENCE;

    /**
     * Get Trigger from DeploymentType.
     *
     * @param deploymentType deploymentType
     * @return deployment trigger
     * @throws IllegalArgumentException invalid deployment type
     */
    public static Trigger fromDeploymentType(DeploymentType deploymentType) {
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
