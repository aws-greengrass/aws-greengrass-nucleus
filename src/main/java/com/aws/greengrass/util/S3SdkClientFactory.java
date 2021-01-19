/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.tes.LazyCredentialProvider;
import lombok.Getter;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
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
    private S3Client s3Client;
    private final LazyCredentialProvider credentialsProvider;

    /**
     * Constructor.
     *
     * @param deviceConfiguration device configuration
     * @param credentialsProvider credential provider from TES
     */
    @Inject
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public S3SdkClientFactory(DeviceConfiguration deviceConfiguration, LazyCredentialProvider credentialsProvider) {
        this.credentialsProvider = credentialsProvider;
        deviceConfiguration.getAWSRegion().subscribe((what, node) -> {
            Region region = null;
            try {
                region = new DefaultAwsRegionProviderChain().getRegion();
            } catch (RuntimeException ignored) {
            }
            if (region == null) {
                region = Region.of(Coerce.toString(deviceConfiguration.getAWSRegion()));
            }
            this.s3Client =
                    S3Client.builder().httpClient(ProxyUtils.getSdkHttpClient())
                            .serviceConfiguration(S3Configuration.builder().useArnRegionEnabled(true).build())
                            .overrideConfiguration(ClientOverrideConfiguration.builder()
                                    .retryPolicy(RetryMode.STANDARD).build())
                            .credentialsProvider(credentialsProvider).region(region).build();
        });
    }

    /**
     * Get a client for a specific region.
     *
     * @param r region
     * @return s3client
     */
    public S3Client getClientForRegion(Region r) {
        return clientCache.computeIfAbsent(r, (region) -> S3Client.builder()
                .httpClient(ProxyUtils.getSdkHttpClient())
                .serviceConfiguration(S3Configuration.builder().useArnRegionEnabled(true).build())
                .credentialsProvider(credentialsProvider).region(r).build());
    }
}
