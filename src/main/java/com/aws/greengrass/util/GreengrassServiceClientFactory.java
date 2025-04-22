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
import com.aws.greengrass.util.exceptions.TLSAuthException;
import lombok.AccessLevel;
import lombok.Getter;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.endpoints.Endpoint;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.greengrassv2data.GreengrassV2DataClient;
import software.amazon.awssdk.services.greengrassv2data.GreengrassV2DataClientBuilder;
import software.amazon.awssdk.services.greengrassv2data.endpoints.GreengrassV2DataEndpointParams;
import software.amazon.awssdk.services.greengrassv2data.endpoints.GreengrassV2DataEndpointProvider;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
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
    @Getter(AccessLevel.NONE)
    private SdkHttpClient cachedHttpClient;
    private GreengrassV2DataClient greengrassV2DataClient;
    // stores the result of last validation; null <=> successful
    private volatile String configValidationError;
    private final AtomicBoolean deviceConfigChanged = new AtomicBoolean(true);
    private final Lock lock = LockFactory.newReentrantLock(this);
    private GreengrassV2DataClientBuilder clientBuilder;

    /**
     * Constructor with custom endpoint/region configuration.
     *
     * @param deviceConfiguration       Device configuration
     */
    @Inject
    public GreengrassServiceClientFactory(DeviceConfiguration deviceConfiguration) {
        this.deviceConfiguration = deviceConfiguration;
        deviceConfiguration.onAnyChange((what, node) -> {
            if (WhatHappened.interiorAdded.equals(what) || WhatHappened.timestampUpdated.equals(what)) {
                return;
            }
            if (validString(node, DEVICE_PARAM_ROOT_CA_PATH) || validString(node, DEVICE_PARAM_CERTIFICATE_FILE_PATH)
                    || validString(node, DEVICE_PARAM_PRIVATE_KEY_PATH)) {
                logger.atInfo().kv("node", node.getFullName()).log("Closing cached http client for Greengrass v2 "
                        + "data client due to device config change");
                cleanHttpClient();
            }
            if (validString(node, DEVICE_PARAM_AWS_REGION) || validString(node, DEVICE_PARAM_ROOT_CA_PATH)
                    || validString(node, DEVICE_PARAM_CERTIFICATE_FILE_PATH) || validString(node,
                    DEVICE_PARAM_PRIVATE_KEY_PATH) || validString(node, DEVICE_PARAM_GG_DATA_PLANE_PORT)
                    || validString(node, DEVICE_PARAM_IOT_CRED_ENDPOINT) || validString(node,
                    DEVICE_PARAM_IOT_DATA_ENDPOINT)) {
                logger.atTrace().kv("what", what).kv("node", node.getFullName()).log();
                if (deviceConfigChanged.compareAndSet(false, true)) {
                    logger.atDebug().log("Queued re-validation of Greengrass v2 data client");
                }
                cleanClient();
            }
        });
    }

    @SuppressWarnings("PMD.NullAssignment")
    private void validateConfiguration() {
        try {
            deviceConfiguration.validate(true);
            configValidationError = null;
        } catch (DeviceConfigurationException e) {
            configValidationError = e.getMessage();
        }
    }

    @SuppressWarnings("PMD.NullAssignment")
    private void cleanClient() {
        try (LockScope ls = LockScope.lock(lock)) {
            if (this.greengrassV2DataClient != null) {
                this.greengrassV2DataClient.close();
                this.greengrassV2DataClient = null;
            }
        }
    }

    @SuppressWarnings("PMD.NullAssignment")
    private void cleanHttpClient() {
        try (LockScope ls = LockScope.lock(lock)) {
            if (this.cachedHttpClient != null) {
                this.cachedHttpClient.close();
                this.cachedHttpClient = null;
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
        if (deviceConfigChanged.compareAndSet(true, false)) {
            validateConfiguration();
        }
        return configValidationError;
    }

    /**
     * Initializes and returns GreengrassV2DataClient.
     * Note that this method can return null if there is a config validation error.
     * @throws TLSAuthException if the client is not configured properly.
     * @deprecated use fetchGreengrassV2DataClient instead.
     */
    @Deprecated
    public GreengrassV2DataClient getGreengrassV2DataClient() throws TLSAuthException {
        try (LockScope ls = LockScope.lock(lock)) {
            if (getConfigValidationError() != null) {
                logger.atWarn()
                        .log("Failed to validate config for Greengrass v2 data client: {}", configValidationError);
                return null;
            }

            if (greengrassV2DataClient == null) {
                configureClient(deviceConfiguration);
            }
            return greengrassV2DataClient;
        }
    }

    /**
     * Initializes and returns GreengrassV2DataClient.
     * @throws DeviceConfigurationException when fails to validate configs.
     * @throws TLSAuthException when fails to configure the client
     */
    public GreengrassV2DataClient fetchGreengrassV2DataClient() throws DeviceConfigurationException, TLSAuthException {
        try (LockScope ls = LockScope.lock(lock)) {
            if (getConfigValidationError() != null) {
                logger.atWarn()
                        .log("Failed to validate config for Greengrass v2 data client: {}", configValidationError);
                throw new DeviceConfigurationException(
                        "Failed to validate config for Greengrass v2 data client: " + configValidationError);
            }

            if (greengrassV2DataClient == null) {
                configureClient(deviceConfiguration);
            }
            return greengrassV2DataClient;
        }
    }

    // Caching a http client since it only needs to be recreated if the cert/keys change
    private void configureHttpClient(DeviceConfiguration deviceConfiguration) throws TLSAuthException {
        logger.atDebug().log("Configuring http client for greengrass v2 data client");
        ApacheHttpClient.Builder httpClientBuilder =
                ClientConfigurationUtils.getConfiguredClientBuilder(deviceConfiguration);
        cachedHttpClient = httpClientBuilder.build();
    }

    private void configureClient(DeviceConfiguration deviceConfiguration) throws TLSAuthException {
        if (cachedHttpClient == null) {
            configureHttpClient(deviceConfiguration);
        }
        logger.atDebug().log(CONFIGURING_GGV2_INFO_MESSAGE);
        String greengrassServiceEndpoint = ClientConfigurationUtils
                .getGreengrassServiceEndpoint(deviceConfiguration);
        GreengrassV2DataEndpointProvider endpointProvider = new GreengrassV2DataEndpointProvider() {
            @Override
            public CompletableFuture<Endpoint> resolveEndpoint(GreengrassV2DataEndpointParams endpointParams) {
                return CompletableFuture.supplyAsync(() -> Endpoint.builder()
                        .url(URI.create(greengrassServiceEndpoint))
                        .build());
            }
        };
        clientBuilder = GreengrassV2DataClient.builder()
            // Use an empty credential provider because our requests don't need SigV4
            // signing, as they are going through IoT Core instead
            .credentialsProvider(AnonymousCredentialsProvider.create())
            .endpointProvider(endpointProvider)
            .httpClient(cachedHttpClient)
            .overrideConfiguration(ClientOverrideConfiguration.builder().retryPolicy(RetryMode.STANDARD).build());


        String region = Coerce.toString(deviceConfiguration.getAWSRegion());

        if (!Utils.isEmpty(region)) {
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
