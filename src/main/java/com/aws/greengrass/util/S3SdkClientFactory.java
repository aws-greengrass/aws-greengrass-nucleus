/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.tes.LazyCredentialProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;

/**
 * S3 accessor that uses Token Exchange Service to get credentials for customer's S3.
 */
public class S3SdkClientFactory {
    static final Map<Region, S3Client> clientCache = new ConcurrentHashMap<>();
    private final LazyCredentialProvider credentialsProvider;
    private final DeviceConfiguration deviceConfiguration;

    private DeviceConfigurationException configValidationError;
    private Region region;

    /**
     * Constructor.
     *
     * @param deviceConfiguration device configuration
     * @param credentialsProvider credential provider from TES
     */
    @Inject
    public S3SdkClientFactory(DeviceConfiguration deviceConfiguration, LazyCredentialProvider credentialsProvider) {
        this.credentialsProvider = credentialsProvider;
        this.deviceConfiguration = deviceConfiguration;
        this.deviceConfiguration.getAWSRegion().subscribe((what, node) -> handleRegionUpdate());
    }

    protected void handleRegionUpdate() {
        try {
            deviceConfiguration.validate();
            configValidationError = null; // NOPMD
            region = Region.of(Coerce.toString(deviceConfiguration.getAWSRegion()));
        } catch (DeviceConfigurationException e) {
            configValidationError = e;
            region = null; // NOPMD
        }
    }

    /**
     * Get the config validation error message if it exists.
     *
     * @return a validation error message
     */
    public String getConfigValidationError() {
        if (configValidationError != null) {
            return configValidationError.getMessage();
        }
        return null;
    }

    /**
     * Get a client for the device region.
     *
     * @return an S3 client
     * @throws DeviceConfigurationException if the configuration is invalid
     */
    public S3Client getS3Client() throws DeviceConfigurationException {
        if (configValidationError != null) {
            throw configValidationError;
        }
        return getClientForRegion(region);
    }

    /**
     * Get a client for a specific region.
     *
     * @param r region
     * @return s3client
     */
    public S3Client getClientForRegion(Region r) {
        return clientCache.computeIfAbsent(r, (region) -> S3Client.builder()
                .httpClientBuilder(ProxyUtils.getSdkHttpClientBuilder())
                .serviceConfiguration(S3Configuration.builder().useArnRegionEnabled(true).build())
                .credentialsProvider(credentialsProvider).region(r).build());
    }
}
