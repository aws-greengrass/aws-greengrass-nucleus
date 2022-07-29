/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.status.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeploymentInformation {
    public static final String STATUS_KEY = "status";
    public static final String STATUS_DETAILS_KEY = "statusDetails";
    public static final String ARN_FOR_STATUS_KEY = "fleetConfigurationArnForStatus";
    private String status;
    private StatusDetails statusDetails;
    private String fleetConfigurationArnForStatus;
    private String deploymentId;
}
