/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.componentmanager.ClientConfigurationUtils;
import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import lombok.Getter;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.greengrassv2data.GreengrassV2DataClient;
import software.amazon.awssdk.services.greengrassv2data.GreengrassV2DataClientBuilder;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;

import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_AWS_REGION;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_CERTIFICATE_FILE_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_GG_DATA_PLANE_PORT;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_IOT_CRED_ENDPOINT;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_IOT_DATA_ENDPOINT;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_PRIVATE_KEY_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_ROOT_CA_PATH;

@Getter
@SuppressWarnings("PMD.ConfusingTernary")
public class GreengrassServiceClientFactory {

    public static final String CONFIGURING_GGV2_INFO_MESSAGE = "Configuring GGV2 client";
    private static final Logger logger = LogManager.getLogger(GreengrassServiceClientFactory.class);
    private final DeviceConfiguration deviceConfiguration;
    private GreengrassV2DataClient greengrassV2DataClient;
    // stores the result of last validation; null <=> successful
    private volatile String configValidationError;
    private final AtomicBoolean validateConfigQueued = new AtomicBoolean(false);

    /**
     * Constructor with custom endpoint/region configuration.
     *
     * @param deviceConfiguration       Device configuration
     */
    @Inject
    public GreengrassServiceClientFactory(DeviceConfiguration deviceConfiguration) {
        this.deviceConfiguration = deviceConfiguration;
        deviceConfiguration.onAnyChange(new BatchedSubscriber((what, node) -> {
            if (WhatHappened.interiorAdded.equals(what) || WhatHappened.timestampUpdated.equals(what)) {
                return false;
            }
            if (validString(node, DEVICE_PARAM_AWS_REGION) || validString(node, DEVICE_PARAM_ROOT_CA_PATH)
                    || validString(node, DEVICE_PARAM_CERTIFICATE_FILE_PATH) || validString(node,
                    DEVICE_PARAM_PRIVATE_KEY_PATH) || validString(node, DEVICE_PARAM_GG_DATA_PLANE_PORT)
                    || validString(node, DEVICE_PARAM_IOT_CRED_ENDPOINT) || validString(node,
                    DEVICE_PARAM_IOT_DATA_ENDPOINT)) {
                logger.atDebug().kv("modifiedNode", node.getFullName()).kv("changeType", what)
                        .log("Queued re-validation of Greengrass v2 data client.");
                return true;
            }
            return false;
        }, (what) -> {
            validateConfigQueued.set(false);
            cleanClient();
        }));
    }

    @SuppressWarnings("PMD.NullAssignment")
    private void validateConfiguration() {
        logger.atDebug().log("Validating device configs for Greengrass v2 data client.");
        try {
            deviceConfiguration.validate(true);
            configValidationError = null;
        } catch (DeviceConfigurationException e) {
            configValidationError = e.getMessage();
        }
    }

    @SuppressWarnings("PMD.NullAssignment")
    private void cleanClient() {
        synchronized (this) {
            if (this.greengrassV2DataClient != null) {
                this.greengrassV2DataClient.close();
                this.greengrassV2DataClient = null;
            }
        }
    }

    private boolean validString(Node node, String key) {
        return node != null && node.childOf(key) && Utils.isNotEmpty(Coerce.toString(node));
    }

    /**
     * Retrieve configValidationError.
     * Validate again if the device config has changed.
     *
     */
    public String getConfigValidationError() {
        if (validateConfigQueued.compareAndSet(false, true)) {
            validateConfiguration();
        }
        return configValidationError;
    }

    /**
     * Initializes and returns GreengrassV2DataClient.
     *
     */
    public synchronized GreengrassV2DataClient getGreengrassV2DataClient() {
        if (getConfigValidationError() != null) {
            return null;
        }

        if (greengrassV2DataClient == null) {
            configureClient(deviceConfiguration);
        }
        return greengrassV2DataClient;
    }

    private void configureClient(DeviceConfiguration deviceConfiguration) {
        logger.atDebug().log(CONFIGURING_GGV2_INFO_MESSAGE);
        ApacheHttpClient.Builder httpClient = ClientConfigurationUtils.getConfiguredClientBuilder(deviceConfiguration);
        GreengrassV2DataClientBuilder clientBuilder = GreengrassV2DataClient.builder()
                // Use an empty credential provider because our requests don't need SigV4
                // signing, as they are going through IoT Core instead
                .credentialsProvider(AnonymousCredentialsProvider.create())
                .httpClient(httpClient.build())
                .overrideConfiguration(ClientOverrideConfiguration.builder().retryPolicy(RetryMode.STANDARD).build());

        String region = Coerce.toString(deviceConfiguration.getAWSRegion());

        if (!Utils.isEmpty(region)) {
            String greengrassServiceEndpoint = ClientConfigurationUtils
                    .getGreengrassServiceEndpoint(deviceConfiguration);

            if (!Utils.isEmpty(greengrassServiceEndpoint)) {
                // Region and endpoint are both required when updating endpoint config
                logger.atDebug("initialize-greengrass-client")
                        .kv("service-endpoint", greengrassServiceEndpoint)
                        .kv("service-region", region).log();
                clientBuilder.endpointOverride(URI.create(greengrassServiceEndpoint));
                clientBuilder.region(Region.of(region));
            } else {
                // This section is to override default region if needed
                logger.atDebug("initialize-greengrass-client")
                        .kv("service-region", region).log();
                clientBuilder.region(Region.of(region));
            }
        }
        this.greengrassV2DataClient = clientBuilder.build();
    }
}
