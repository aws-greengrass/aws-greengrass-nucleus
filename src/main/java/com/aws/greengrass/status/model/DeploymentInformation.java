/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.status.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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
    // tracking root components in a deployment FSS update without component status detail due to state unchanged
    // since last update
    private List<String> unchangedRootComponents;
}
