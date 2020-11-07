/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.utils.ImmutableMap;

import java.util.Map;

public final class RegionUtils {
    private static final String DEFAULT_IOT_CONTROL_PLANE_ENDPOINT_FORMAT = "https://%s.%s.iot.%s";
    private static final Map<IotSdkClientFactory.EnvironmentStage, String>
            DEFAULT_GREENGRASS_SERVICE_STAGE_TO_ENDPOINT_FORMAT = ImmutableMap.of(
            IotSdkClientFactory.EnvironmentStage.PROD, "greengrass-ats.iot.%s.%s:8443/greengrass",
            IotSdkClientFactory.EnvironmentStage.GAMMA, "greengrass-ats.gamma.%s.iot.%s:8443/greengrass",
            IotSdkClientFactory.EnvironmentStage.BETA, "greengrass-ats.beta.%s.iot.%s:8443/greengrass"
    );
    private static final String CN_PARTITION_ID = "aws-cn";
    private static final String US_GOV_PARTITION_ID = "aws-us-gov";

    private RegionUtils() {
    }

    /**
     * Get Greengrass ServiceEndpoint by region and stage.
     * @param awsRegion aws region
     * @param stage environment stage
     * @return Greengrass ServiceEndpoint
     */
    public static String getGreengrassServiceEndpoint(String awsRegion,
                                                      IotSdkClientFactory.EnvironmentStage stage) {
        String dnsSuffix = Region.of(awsRegion).metadata().partition().dnsSuffix();
        return String.format(DEFAULT_GREENGRASS_SERVICE_STAGE_TO_ENDPOINT_FORMAT.get(stage), awsRegion, dnsSuffix);
    }

    /**
     * Get Iot Control Plane Endpoint by region and stage.
     * @param awsRegion aws region
     * @param stage environment stage
     * @return Iot Control Plane Endpoint
     */
    public static String getIotControlPlaneEndpoint(Region awsRegion,
                                                    IotSdkClientFactory.EnvironmentStage stage) {
        String dnsSuffix = awsRegion.metadata().partition().dnsSuffix();
        return String.format(DEFAULT_IOT_CONTROL_PLANE_ENDPOINT_FORMAT, stage.value, awsRegion, dnsSuffix);
    }

    /**
     * Get global region based on the region partition ID.
     * @param awsRegion aws region
     * @return Region
     */
    public static Region getPartitionFromRegion(String awsRegion) {
        String partitionId = Region.of(awsRegion).metadata().id();
        if (partitionId.equals(CN_PARTITION_ID)) {
            return Region.AWS_CN_GLOBAL;
        }
        if (partitionId.equals(US_GOV_PARTITION_ID)) {
            return Region.AWS_US_GOV_GLOBAL;
        }
        return Region.AWS_GLOBAL;
    }
}
