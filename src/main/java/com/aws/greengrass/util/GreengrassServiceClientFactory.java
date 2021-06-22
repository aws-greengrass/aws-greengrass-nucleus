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
import java.nio.file.Files;
import java.nio.file.Paths;
import javax.inject.Inject;

import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_AWS_REGION;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_CERTIFICATE_FILE_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_GG_DATA_PLANE_PORT;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_PRIVATE_KEY_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_ROOT_CA_PATH;

@Getter
@SuppressWarnings("PMD.ConfusingTernary")
public class GreengrassServiceClientFactory {

    public static final String CONFIGURING_GGV2_INFO_MESSAGE = "Configuring GGV2 client";
    private static final Logger logger = LogManager.getLogger(GreengrassServiceClientFactory.class);
    private GreengrassV2DataClient greengrassV2DataClient;
    private String configValidationError;

    /**
     * Constructor with custom endpoint/region configuration.
     *
     * @param deviceConfiguration       Device configuration
     */
    @Inject
    public GreengrassServiceClientFactory(DeviceConfiguration deviceConfiguration) {
        deviceConfiguration.onAnyChange((what, node) -> {
            if (WhatHappened.interiorAdded.equals(what) || WhatHappened.timestampUpdated.equals(what)) {
                return;
            }
            if (validString(node, DEVICE_PARAM_AWS_REGION) || validPath(node, DEVICE_PARAM_ROOT_CA_PATH) || validPath(
                    node, DEVICE_PARAM_CERTIFICATE_FILE_PATH) || validPath(node, DEVICE_PARAM_PRIVATE_KEY_PATH)
                    || validString(node, DEVICE_PARAM_GG_DATA_PLANE_PORT)) {
                try {
                   validateAndConfigure(deviceConfiguration);
                } catch (DeviceConfigurationException ex) {
                    configValidationError = ex.getMessage();
                    return;
                }
            }
        });

        try {
            validateAndConfigure(deviceConfiguration);
        } catch (DeviceConfigurationException e) {
            configValidationError = e.getMessage();
            return;
        }
    }

    @SuppressWarnings("PMD.NullAssignment")
    private void validateAndConfigure(DeviceConfiguration deviceConfiguration) throws DeviceConfigurationException {
        deviceConfiguration.validate(true);
        configureClient(deviceConfiguration);
        configValidationError = null;
    }

    private boolean validString(Node node, String key) {
        return node != null && node.childOf(key) && Utils.isNotEmpty(Coerce.toString(node));
    }

    private boolean validPath(Node node, String key) {
        return validString(node, key) && Files.exists(Paths.get(Coerce.toString(node)));
    }

    public synchronized GreengrassV2DataClient getGreengrassV2DataClient() {
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
                logger.atInfo("initialize-greengrass-client").addKeyValue("service-endpoint", greengrassServiceEndpoint)
                        .addKeyValue("service-region", region).log();
                clientBuilder.endpointOverride(URI.create(greengrassServiceEndpoint));
                clientBuilder.region(Region.of(region));
            } else {
                // This section is to override default region if needed
                logger.atInfo("initialize-greengrass-client").addKeyValue("service-region", region).log();
                clientBuilder.region(Region.of(region));
            }
        }
        synchronized (this) {
            if (this.greengrassV2DataClient != null) {
                this.greengrassV2DataClient.close();
            }
            this.greengrassV2DataClient = clientBuilder.build();
        }
    }
}
