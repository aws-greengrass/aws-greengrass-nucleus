/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.util;

import com.aws.iot.evergreen.deployment.DeviceConfiguration;
import com.aws.iot.evergreen.tes.LazyCredentialProvider;
import lombok.Getter;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;

import javax.inject.Inject;

@Getter
public class CloudWatchClientFactory {
    private final CloudWatchLogsClient cloudWatchLogsClient;
    //TODO: Handle fips
    //private static String CLOUD_WATCH_FIPS_HOST = "logs-fips.%s.amazonaws.com";

    /**
     * Constructor.
     *
     * @param deviceConfiguration device configuration
     * @param credentialsProvider credential provider from TES
     */
    @Inject
    public CloudWatchClientFactory(DeviceConfiguration deviceConfiguration,
                                   LazyCredentialProvider credentialsProvider) {
        Region region;
        try {
            region = new DefaultAwsRegionProviderChain().getRegion();
        } catch (RuntimeException ignored) {
            region = Region.of(Coerce.toString(deviceConfiguration.getAWSRegion()));
        }

        this.cloudWatchLogsClient = CloudWatchLogsClient.builder().credentialsProvider(credentialsProvider)
                .region(region).build();
    }
}
