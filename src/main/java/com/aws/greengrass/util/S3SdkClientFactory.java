/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.deployment.model.S3EndpointType;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.tes.LazyCredentialProvider;
import software.amazon.awssdk.core.SdkSystemSetting;
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
    private static final Logger logger = LogManager.getLogger(S3SdkClientFactory.class);
    private static final String S3_ENDPOINT_PROP_NAME = SdkSystemSetting.AWS_S3_US_EAST_1_REGIONAL_ENDPOINT.property();
    private static final String S3_REGIONAL_ENDPOINT_VALUE = "regional";
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
        this.deviceConfiguration.onAnyChange((what, node) -> handleRegionUpdate());
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
        setS3EndpointType(Coerce.toString(deviceConfiguration.gets3EndpointType()));
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

    /**
     * Set s3 endpoint type.
     *
     * @param type s3EndpointType
     */
    private void setS3EndpointType(String type) {
        //Check if system property and device config are consistent
        //If not consistent, set system property according to device config value
        String s3EndpointSystemProp = System.getProperty(S3_ENDPOINT_PROP_NAME);
        boolean isGlobal = S3EndpointType.GLOBAL.name().equals(type);

        if (isGlobal && S3_REGIONAL_ENDPOINT_VALUE.equals(s3EndpointSystemProp)) {
            System.clearProperty(S3_ENDPOINT_PROP_NAME);
            refreshClientCache();
            logger.atDebug().log("s3 endpoint set to global");
        } else if (!isGlobal && !S3_REGIONAL_ENDPOINT_VALUE.equals(s3EndpointSystemProp)) {
            System.setProperty(S3_ENDPOINT_PROP_NAME, S3_REGIONAL_ENDPOINT_VALUE);
            refreshClientCache();
            logger.atDebug().log("s3 endpoint set to regional");
        }
    }

    private void refreshClientCache() {
        clientCache.remove(region);
    }
}
