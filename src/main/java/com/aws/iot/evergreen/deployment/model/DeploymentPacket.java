/* Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment.model;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DeploymentPacket {

    private String deploymentId;

    private long deploymentCreationTimestamp;

}
