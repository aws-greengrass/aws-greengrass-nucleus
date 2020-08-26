/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.util;

import com.aws.iot.evergreen.tes.LazyCredentialProvider;
import lombok.Getter;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;

/**
 * S3 accessor that uses Token Exchange Service to get credentials for customer's S3.
 */
@Getter
public class S3SdkClientFactory {
    private static final Map<Region, S3Client> clientCache = new ConcurrentHashMap<>();
    private final S3Client s3Client;
    private final LazyCredentialProvider credentialsProvider;

    /**
     * Constructor.
     *
     * @param credentialsProvider credential provider from TES
     */
    @Inject
    public S3SdkClientFactory(LazyCredentialProvider credentialsProvider) {
        this.credentialsProvider = credentialsProvider;
        this.s3Client =
                S3Client.builder().serviceConfiguration(S3Configuration.builder().useArnRegionEnabled(true).build())
                        .credentialsProvider(credentialsProvider).build();
    }

    /**
     * Get a client for a specific region.
     *
     * @param r region
     * @return s3client
     */
    public S3Client getClientForRegion(Region r) {
        return clientCache.computeIfAbsent(r, (region) -> S3Client.builder()
                .serviceConfiguration(S3Configuration.builder().useArnRegionEnabled(true).build())
                .credentialsProvider(credentialsProvider).region(r).build());
    }
}
