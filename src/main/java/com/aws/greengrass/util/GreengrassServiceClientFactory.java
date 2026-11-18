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
import software.amazon.awssdk.core.exception.RetryableException;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.endpoints.Endpoint;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.greengrassv2data.GreengrassV2DataClient;
import software.amazon.awssdk.services.greengrassv2data.GreengrassV2DataClientBuilder;
import software.amazon.awssdk.services.greengrassv2data.endpoints.GreengrassV2DataEndpointParams;
import software.amazon.awssdk.services.greengrassv2data.endpoints.GreengrassV2DataEndpointProvider;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
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
    private static final List<String> HTTP_CLIENT_KEYS =
            Arrays.asList(DEVICE_PARAM_ROOT_CA_PATH, DEVICE_PARAM_CERTIFICATE_FILE_PATH, DEVICE_PARAM_PRIVATE_KEY_PATH);
    private static final List<String> SDK_CLIENT_KEYS = Arrays.asList(DEVICE_PARAM_AWS_REGION,
            DEVICE_PARAM_GG_DATA_PLANE_PORT, DEVICE_PARAM_IOT_CRED_ENDPOINT, DEVICE_PARAM_IOT_DATA_ENDPOINT);
    private final DeviceConfiguration deviceConfiguration;
    @Getter(AccessLevel.NONE)
    private final SwappableHttpClient cachedHttpClient;
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
        this.cachedHttpClient = new SwappableHttpClient(ApacheHttpClient.CLIENT_NAME, () -> {
            logger.atDebug().log("Configuring http client for greengrass v2 data client");
            return ClientConfigurationUtils.getConfiguredClientBuilder(deviceConfiguration).build();
        });
        deviceConfiguration.onAnyChange((what, node) -> {
            if (WhatHappened.interiorAdded.equals(what) || WhatHappened.timestampUpdated.equals(what)) {
                return;
            }
            boolean httpClientChanged = changed(node, HTTP_CLIENT_KEYS);
            if (!httpClientChanged && !changed(node, SDK_CLIENT_KEYS)) {
                return;
            }
            logger.atTrace().kv("what", what).kv("node", node.getFullName()).log();
            if (deviceConfigChanged.compareAndSet(false, true)) {
                logger.atDebug().log("Queued re-validation of Greengrass v2 data client");
            }
            if (httpClientChanged) {
                logger.atInfo().kv("node", node.getFullName()).log("Closing cached http client for Greengrass v2 "
                        + "data client due to device config change");
                cachedHttpClient.retire();
            } else {
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

    private boolean changed(Node node, List<String> keys) {
        return node != null && keys.stream().anyMatch(node::childOf) && Utils.isNotEmpty(Coerce.toString(node));
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
     * @deprecated use fetchGreengrassV2DataClient instead.
     * @throws TLSAuthException if the client is not configured properly.
     */
    @Deprecated
    public GreengrassV2DataClient getGreengrassV2DataClient() throws TLSAuthException {
        try (LockScope ls = LockScope.lock(lock)) {
            if (getConfigValidationError() != null) {
                logger.atWarn()
                        .log("Failed to validate config for Greengrass v2 data client: {}", configValidationError);
                return null;
            }

            return configuredClient();
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

            return configuredClient();
        }
    }

    private GreengrassV2DataClient configuredClient() throws TLSAuthException {
        // Build the http client now, so a bad cert, key or root CA fails here with the TLSAuthException callers expect
        cachedHttpClient.buildIfAbsent();
        if (greengrassV2DataClient == null) {
            configureClient(deviceConfiguration);
        }
        return greengrassV2DataClient;
    }

    private void configureClient(DeviceConfiguration deviceConfiguration) {
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

    /**
     * The http client the SDK client is built with. A cert, key or root CA change retires the Apache client behind it,
     * and the next request builds a new one from the config at that time. A request cut off by the retirement fails
     * with an {@link IOException}, so the SDK's own retry runs it again on the new client. {@link #close()} is final.
     * It retires the Apache client and no request after it builds a new one.
     */
    static final class SwappableHttpClient implements SdkHttpClient {
        private final String clientName;
        private final CrashableSupplier<SdkHttpClient, TLSAuthException> builder;
        private final Lock lock = LockFactory.newReentrantLock(this);
        private volatile SdkHttpClient delegate;
        private boolean closed;

        SwappableHttpClient(String clientName, CrashableSupplier<SdkHttpClient, TLSAuthException> builder) {
            this.clientName = clientName;
            this.builder = builder;
        }

        void buildIfAbsent() throws TLSAuthException {
            delegate();
        }

        private SdkHttpClient delegate() throws TLSAuthException {
            try (LockScope ls = LockScope.lock(lock)) {
                if (closed) {
                    // Not an IOException or RetryableException, so the SDK fails the request without retrying it
                    throw new IllegalStateException("HTTP client for Greengrass v2 data client is closed");
                }
                if (delegate == null) {
                    delegate = builder.apply();
                }
                return delegate;
            }
        }

        @SuppressWarnings({"PMD.NullAssignment", "PMD.CloseResource"})
        void retire() {
            try (LockScope ls = LockScope.lock(lock)) {
                if (delegate != null) {
                    // Clear the field first, so a request that the close cuts off sees that its client was retired
                    SdkHttpClient retired = delegate;
                    delegate = null;
                    retired.close();
                }
            }
        }

        @Override
        @SuppressWarnings({"PMD.CloseResource", "PMD.CompareObjectsWithEquals"})
        public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            SdkHttpClient client;
            try {
                client = delegate();
            } catch (TLSAuthException e) {
                // The SDK retries this one and passes any other unchecked exception straight to the caller
                throw RetryableException.create("Failed to configure http client for Greengrass v2 data client", e);
            }
            ExecutableHttpRequest prepared = client.prepareRequest(request);
            return new ExecutableHttpRequest() {
                @Override
                public HttpExecuteResponse call() throws IOException {
                    try {
                        return prepared.call();
                    } catch (IllegalStateException e) {
                        if (client != delegate) {
                            throw new IOException("HTTP client was closed by a device config change", e);
                        }
                        throw e;
                    }
                }

                @Override
                public void abort() {
                    prepared.abort();
                }
            };
        }

        // The SDK reads this once, when it builds its client, and that may be while no delegate exists
        @Override
        public String clientName() {
            return clientName;
        }

        @Override
        public void close() {
            try (LockScope ls = LockScope.lock(lock)) {
                closed = true;
                retire();
            }
        }
    }
}
