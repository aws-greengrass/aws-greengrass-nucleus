/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.util;

import com.aws.iot.evergreen.deployment.DeviceConfiguration;
import com.aws.iot.evergreen.tes.LazyCredentialProvider;
import lombok.Getter;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;

import javax.inject.Inject;

@Getter
public class CloudWatchClientFactory {
    private CloudWatchLogsClient cloudWatchLogsClient;
    //TODO: Handle fips
    //private static String CLOUD_WATCH_FIPS_HOST = "logs-fips.%s.amazonaws.com";

    @Inject
    public CloudWatchClientFactory(DeviceConfiguration deviceConfiguration,
                                   LazyCredentialProvider credentialsProvider) {
        this.cloudWatchLogsClient = CloudWatchLogsClient.builder().credentialsProvider(credentialsProvider)
                .region(Region.of(Coerce.toString(deviceConfiguration.getAWSRegion()))).build();
    }
}
