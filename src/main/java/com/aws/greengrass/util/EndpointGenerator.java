/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.utils.ImmutableMap;

import java.util.HashMap;
import java.util.Map;

public final class EndpointGenerator {
    private static final String DEFAULT_IOT_CONTROL_PLANE_ENDPOINT_FORMAT = "https://%s.%s.iot.amazonaws.com";
    private static final String CN_IOT_CONTROL_PLANE_ENDPOINT_FORMAT = "https://%s.%s.iot.amazonaws.com.cn";
    private static Map<String, String> REGION_TO_IOT_CONTROL_PLANE_ENDPOINT = new HashMap<>();
    private static Map<String, Region> GLOBAL_REGION_CONVERTER = new HashMap<>();
    private static Map<String, Map<IotSdkClientFactory.EnvironmentStage, String>>
            REGION_TO_GREENGRASS_SERVICE_STAGE_TO_ENDPOINT = new HashMap<>();

    private static final Map<IotSdkClientFactory.EnvironmentStage, String>
            DEFAULT_GREENGRASS_SERVICE_STAGE_TO_ENDPOINT_FORMAT = ImmutableMap.of(
            IotSdkClientFactory.EnvironmentStage.PROD, "greengrass-ats.iot.%s.amazonaws.com:8443/greengrass",
            IotSdkClientFactory.EnvironmentStage.GAMMA, "greengrass-ats.gamma.%s.iot.amazonaws.com:8443/greengrass",
            IotSdkClientFactory.EnvironmentStage.BETA, "greengrass-ats.beta.%s.iot.amazonaws.com:8443/greengrass"
    );
    private static final Map<IotSdkClientFactory.EnvironmentStage, String>
            CN_GREENGRASS_SERVICE_STAGE_TO_ENDPOINT_FORMAT = ImmutableMap.of(
            IotSdkClientFactory.EnvironmentStage.PROD, "greengrass-ats.iot.%s.amazonaws.com.cn:8443/greengrass",
            IotSdkClientFactory.EnvironmentStage.GAMMA, "greengrass-ats.gamma.%s.iot.amazonaws.com.cn:8443/greengrass",
            IotSdkClientFactory.EnvironmentStage.BETA, "greengrass-ats.beta.%s.iot.amazonaws.com.cn:8443/greengrass"
    );

    static {
        REGION_TO_IOT_CONTROL_PLANE_ENDPOINT.put("cn-north-1", CN_IOT_CONTROL_PLANE_ENDPOINT_FORMAT);

        REGION_TO_GREENGRASS_SERVICE_STAGE_TO_ENDPOINT.put("cn-north-1",
                CN_GREENGRASS_SERVICE_STAGE_TO_ENDPOINT_FORMAT);

        GLOBAL_REGION_CONVERTER.put("cn-north-1", Region.AWS_CN_GLOBAL);
        GLOBAL_REGION_CONVERTER.put("us-gov-east-1", Region.AWS_US_GOV_GLOBAL);
        GLOBAL_REGION_CONVERTER.put("us-gov-west-1", Region.AWS_US_GOV_GLOBAL);
    }

    private EndpointGenerator() {
    }

    /**
     * Get Greengrass ServiceEndpoint by region and stage.
     * @param awsRegion aws region
     * @param stage environment stage
     * @return Greengrass ServiceEndpoint
     */
    public static String getGreengrassServiceEndpointByRegionAndStage(String awsRegion,
                                                                      IotSdkClientFactory.EnvironmentStage stage) {

        Map<IotSdkClientFactory.EnvironmentStage, String> greengrassServiceEndpointTemplate =
                REGION_TO_GREENGRASS_SERVICE_STAGE_TO_ENDPOINT
                .getOrDefault(awsRegion, DEFAULT_GREENGRASS_SERVICE_STAGE_TO_ENDPOINT_FORMAT);
        return String.format(greengrassServiceEndpointTemplate.get(stage), awsRegion);
    }

    /**
     * Get Iot Control Plane Endpoint by region and stage.
     * @param awsRegion aws region
     * @param stage environment stage
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
