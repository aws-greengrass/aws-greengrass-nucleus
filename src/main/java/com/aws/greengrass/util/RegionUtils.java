/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.utils.ImmutableMap;

import java.util.Map;

public final class RegionUtils {
    private static final String IOT_CORE_CONTROL_PLANE_ENDPOINT_FORMAT = "https://%s.%s.iot.%s";
    private static final Map<IotSdkClientFactory.EnvironmentStage, String> GREENGRASS_DATA_PLANE_STAGE_TO_ENDPOINT_FORMAT =
            ImmutableMap.of(IotSdkClientFactory.EnvironmentStage.PROD, "https://greengrass-ats.iot.%s.%s:%s",
                    IotSdkClientFactory.EnvironmentStage.GAMMA, "https://greengrass-ats.gamma.%s.iot.%s:%s",
                    IotSdkClientFactory.EnvironmentStage.BETA, "https://greengrass-ats.beta.%s.iot.%s:%s");
    private static final Map<IotSdkClientFactory.EnvironmentStage, String> GREENGRASS_DATA_PLANE_STAGE_TO_ENDPOINT_FORMAT_CN_NORTH_1 =
            ImmutableMap.of(IotSdkClientFactory.EnvironmentStage.PROD, "https://greengrass.ats.iot.%s.%s:%s",
                    IotSdkClientFactory.EnvironmentStage.GAMMA, "https://greengrass.ats.gamma.%s.iot.%s:%s",
                    IotSdkClientFactory.EnvironmentStage.BETA, "https://greengrass.ats.beta.%s.iot.%s:%s");
    private static final Map<IotSdkClientFactory.EnvironmentStage, String> GREENGRASS_CONTROL_PLANE_STAGE_TO_ENDPOINT_FORMAT =
            ImmutableMap.of(IotSdkClientFactory.EnvironmentStage.PROD, "https://greengrass.%s.%s",
                    IotSdkClientFactory.EnvironmentStage.GAMMA, "https://greengrass-gamma.%s.%s",
                    IotSdkClientFactory.EnvironmentStage.BETA, "https://greengrass-beta2.%s.%s");

    private RegionUtils() {
    }

    /**
     * Get Greengrass Control Plane Endpoint by region and stage.
     * 
     * @param awsRegion aws region
     * @param stage environment stage
     * @return Greengrass control plane endpoint
     */
    public static String getGreengrassControlPlaneEndpoint(String awsRegion,
            IotSdkClientFactory.EnvironmentStage stage) {
        String dnsSuffix = Region.of(awsRegion).metadata().partition().dnsSuffix();
        return String.format(GREENGRASS_CONTROL_PLANE_STAGE_TO_ENDPOINT_FORMAT.get(stage), awsRegion, dnsSuffix);
    }

    /**
     * Get Greengrass Data Plane Endpoint by region and stage.
     * 
     * @param awsRegion aws region
     * @param stage environment stage
     * @param port endpoint port
     * @return Greengrass ServiceEndpoint
     */
    public static String getGreengrassDataPlaneEndpoint(String awsRegion, IotSdkClientFactory.EnvironmentStage stage,
            int port) {
        String dnsSuffix = Region.of(awsRegion).metadata().partition().dnsSuffix();
        if (Region.CN_NORTH_1.equals(Region.of(awsRegion))) {
            // CN_NORTH_1 has a special endpoint format
            return String.format(GREENGRASS_DATA_PLANE_STAGE_TO_ENDPOINT_FORMAT_CN_NORTH_1.get(stage), awsRegion,
                    dnsSuffix, port);
        }
        return String.format(GREENGRASS_DATA_PLANE_STAGE_TO_ENDPOINT_FORMAT.get(stage), awsRegion, dnsSuffix, port);
    }

    /**
     * Get Iot Core Control Plane Endpoint by region and stage.
     * 
     * @param awsRegion aws region
     * @param stage environment stage
     * @return Iot Control Plane Endpoint
     */
    public static String getIotCoreControlPlaneEndpoint(Region awsRegion, IotSdkClientFactory.EnvironmentStage stage) {
        String dnsSuffix = awsRegion.metadata().partition().dnsSuffix();
        return String.format(IOT_CORE_CONTROL_PLANE_ENDPOINT_FORMAT, stage.value, awsRegion, dnsSuffix);
    }

    /**
     * Get global region based on the region partition ID.
     * 
     * @param awsRegion aws region
     * @return Region
     */
    public static Region getGlobalRegion(String awsRegion) {
        String partitionId = Region.of(awsRegion).metadata().partition().id();
        return Region.of(partitionId + "-global");
    }
}
