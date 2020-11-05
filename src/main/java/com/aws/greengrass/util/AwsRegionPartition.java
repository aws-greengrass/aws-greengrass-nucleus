/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.utils.ImmutableMap;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class AwsRegionPartition {
    private static final Set<String> chinaRegions = new HashSet<>(Arrays.asList("cn-north-1", "cn-northwest-1"));
    private static final String DEFAULT_IOT_CONTROL_PLANE_ENDPOINT_FORMAT = "https://%s.%s.iot.amazonaws.com";
    private static final String CHINA_IOT_CONTROL_PLANE_ENDPOINT_FORMAT = "https://%s.%s.iot.amazonaws.com.cn";
    private static Map<String, String> REGION_TO_IOT_CONTROL_PLANE_ENDPOINT = new HashMap<>();
    private static Map<String, Region> GLOBAL_REGION_CONVERTER = new HashMap<>();

    private static final Map<IotSdkClientFactory.EnvironmentStage, String>
            DEFAULT_GREENGRASS_SERVICE_STAGE_TO_ENDPOINT_FORMAT = ImmutableMap.of(
            IotSdkClientFactory.EnvironmentStage.PROD, "greengrass-ats.iot.%s.amazonaws.com:8443/greengrass",
            IotSdkClientFactory.EnvironmentStage.GAMMA, "greengrass-ats.gamma.%s.iot.amazonaws.com:8443/greengrass",
            IotSdkClientFactory.EnvironmentStage.BETA, "greengrass-ats.beta.%s.iot.amazonaws.com:8443/greengrass"
    );
    private static final Map<IotSdkClientFactory.EnvironmentStage, String>
            CHINA_GREENGRASS_SERVICE_STAGE_TO_ENDPOINT_FORMAT = ImmutableMap.of(
            IotSdkClientFactory.EnvironmentStage.PROD, "greengrass-ats.iot.%s.amazonaws.com.cn:8443/greengrass",
            IotSdkClientFactory.EnvironmentStage.GAMMA, "greengrass-ats.gamma.%s.iot.amazonaws.com.cn:8443/greengrass",
            IotSdkClientFactory.EnvironmentStage.BETA, "greengrass-ats.beta.%s.iot.amazonaws.com.cn:8443/greengrass"
    );

    static {
        REGION_TO_IOT_CONTROL_PLANE_ENDPOINT.put("cn-north-1", CHINA_IOT_CONTROL_PLANE_ENDPOINT_FORMAT);
        REGION_TO_IOT_CONTROL_PLANE_ENDPOINT.put("cn-northwest-1", CHINA_IOT_CONTROL_PLANE_ENDPOINT_FORMAT);

        GLOBAL_REGION_CONVERTER.put("cn-northwest-1", Region.AWS_CN_GLOBAL);
        GLOBAL_REGION_CONVERTER.put("cn-north-1", Region.AWS_CN_GLOBAL);
        GLOBAL_REGION_CONVERTER.put("us-gov-east-1", Region.AWS_US_GOV_GLOBAL);
        GLOBAL_REGION_CONVERTER.put("us-gov-west-1", Region.AWS_US_GOV_GLOBAL);
        GLOBAL_REGION_CONVERTER.put("us-iso-east-1", Region.AWS_ISO_GLOBAL);
        GLOBAL_REGION_CONVERTER.put("us-isob-east-1", Region.AWS_ISO_B_GLOBAL);
    }

    private AwsRegionPartition() {
    }

    /**
     * Get Greengrass ServiceEndpoint by region and stage.
     * @param awsRegion aws region
     * @param stage envrioment stage
     * @return Greengrass ServiceEndpoint
     */
    public static String getGreengrassServiceEndpointByRegionAndStage(String awsRegion,
                                                                      IotSdkClientFactory.EnvironmentStage stage) {
        if (chinaRegions.contains(awsRegion)) {
            return String.format(CHINA_GREENGRASS_SERVICE_STAGE_TO_ENDPOINT_FORMAT.get(stage), awsRegion);
        }
        return String.format(DEFAULT_GREENGRASS_SERVICE_STAGE_TO_ENDPOINT_FORMAT.get(stage), awsRegion);
    }

    /**
     * Get Iot Control Plane Endpoint by regition and stage.
     * @param awsRegion aws region
     * @param stage envrioment stage
     * @return Iot Control Plane Endpoint
     */
    public static String getIotControlPlaneEndpointByRegionAndStage(Region awsRegion,
                                                                    IotSdkClientFactory.EnvironmentStage stage) {

        String iotControlPlaneEndpointTemplate = REGION_TO_IOT_CONTROL_PLANE_ENDPOINT
                .getOrDefault(awsRegion, DEFAULT_IOT_CONTROL_PLANE_ENDPOINT_FORMAT);
        return  String.format(iotControlPlaneEndpointTemplate, stage.value, awsRegion);
    }

    public static Region getGlobalRegion(String awsRegion) {
        return GLOBAL_REGION_CONVERTER.getOrDefault(awsRegion, Region.AWS_GLOBAL);
    }
}
