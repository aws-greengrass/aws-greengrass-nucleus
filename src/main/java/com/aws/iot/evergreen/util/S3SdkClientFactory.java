/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.util;

import com.aws.iot.evergreen.deployment.DeviceConfiguration;
import lombok.Getter;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import javax.inject.Inject;

/**
 * S3 accessor that uses Token Exchange Service to get credentials for customer's S3.
 */
@Getter
public class S3SdkClientFactory {
    private final S3Client s3Client;

    @Inject
    public S3SdkClientFactory(DeviceConfiguration deviceConfiguration) {
        this.s3Client =
                S3Client.builder().region(Region.of(Coerce.toString(deviceConfiguration.getAWSRegion()))).build();
    }
}
