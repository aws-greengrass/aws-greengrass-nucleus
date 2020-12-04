/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.iot;

import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.AWSIotException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.ProxyUtils;
import software.amazon.awssdk.crt.http.HttpClientConnection;
import software.amazon.awssdk.crt.http.HttpClientConnectionManager;
import software.amazon.awssdk.crt.http.HttpClientConnectionManagerOptions;
import software.amazon.awssdk.crt.http.HttpException;
import software.amazon.awssdk.crt.io.ClientBootstrap;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.HostResolver;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.crt.io.TlsContext;
import software.amazon.awssdk.crt.io.TlsContextOptions;

import java.io.Closeable;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.inject.Inject;

import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_MQTT_NAMESPACE;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_AWS_REGION;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_CERTIFICATE_FILE_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_IOT_DATA_ENDPOINT;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_PRIVATE_KEY_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_ROOT_CA_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_THING_NAME;
import static com.aws.greengrass.mqttclient.MqttClient.EVENTLOOP_SHUTDOWN_TIMEOUT_SECONDS;

public class IotConnectionManager implements Closeable {
    private static final Logger LOGGER = LogManager.getLogger(IotConnectionManager.class);
    // Max wait time for device to establish mTLS connection with IOT core
    private static final long TIMEOUT_FOR_CONNECTION_SETUP_SECONDS = Duration.ofMinutes(1).getSeconds();
    private HttpClientConnectionManager connManager;

    private final EventLoopGroup eventLoopGroup;
    private final HostResolver resolver;
    private final ClientBootstrap clientBootstrap;

    /**
     * Constructor.
     *
     * @param deviceConfiguration Device configuration helper getting cert and keys for mTLS
     */
    @Inject
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public IotConnectionManager(final DeviceConfiguration deviceConfiguration) {
        eventLoopGroup = new EventLoopGroup(1);
        resolver = new HostResolver(eventLoopGroup);
        clientBootstrap = new ClientBootstrap(eventLoopGroup, resolver);
        try {
            this.connManager = initConnectionManager(deviceConfiguration);
            reconfigureOnConfigChange(deviceConfiguration);
        } catch (RuntimeException e) {
            // If we couldn't initialize the connection manager, then make sure to shutdown
            // everything which was started up
            clientBootstrap.close();
            resolver.close();
            eventLoopGroup.close();
            throw e;
        }
    }

    private void reconfigureOnConfigChange(DeviceConfiguration deviceConfiguration) {
        deviceConfiguration.onAnyChange((what, node) -> {
            if (WhatHappened.childChanged.equals(what) && node != null && (node.childOf(DEVICE_MQTT_NAMESPACE) || node
                    .childOf(DEVICE_PARAM_THING_NAME) || node.childOf(DEVICE_PARAM_IOT_DATA_ENDPOINT) || node
                    .childOf(DEVICE_PARAM_PRIVATE_KEY_PATH) || node.childOf(DEVICE_PARAM_CERTIFICATE_FILE_PATH) || node
                    .childOf(DEVICE_PARAM_ROOT_CA_PATH) || node.childOf(DEVICE_PARAM_AWS_REGION))) {
                this.connManager = initConnectionManager(deviceConfiguration);
            }
        });
    }

    private HttpClientConnectionManager initConnectionManager(DeviceConfiguration deviceConfiguration) {
        final String certPath = Coerce.toString(deviceConfiguration.getCertificateFilePath());
        final String keyPath = Coerce.toString(deviceConfiguration.getPrivateKeyFilePath());
        final String caPath = Coerce.toString(deviceConfiguration.getRootCAFilePath());
        try (TlsContextOptions tlsCtxOptions = TlsContextOptions.createWithMtlsFromPath(certPath, keyPath)) {
            tlsCtxOptions.overrideDefaultTrustStoreFromPath(null, caPath);
            return HttpClientConnectionManager
                    .create(new HttpClientConnectionManagerOptions().withClientBootstrap(clientBootstrap)
                            .withProxyOptions(ProxyUtils.getHttpProxyOptions(deviceConfiguration))
                            .withSocketOptions(new SocketOptions()).withTlsContext(new TlsContext(tlsCtxOptions))
                            .withUri(URI.create(
                                    "https://" + Coerce.toString(deviceConfiguration.getIotCredentialEndpoint()))));
        }
    }

    /**
     * Get a connection object for sending requests.
     *
     * @return {@link HttpClientConnection}
     * @throws AWSIotException when getting a connection from underlying manager fails.
     */
    public HttpClientConnection getConnection() throws AWSIotException {
        try {
            return connManager.acquireConnection().get(TIMEOUT_FOR_CONNECTION_SETUP_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException | HttpException e) {
            LOGGER.error("Getting connection failed for endpoint {} with error {} ", connManager.getUri(), e);
            throw new AWSIotException(e);
        }
    }

    /**
     * Get the host string underlying connection manager.
     *
     * @return Host string to be used in HTTP Host headers
     */
    public String getHost() {
        return connManager.getUri().getHost();
    }

    /**
     * Clean up underlying connections and close gracefully.
     */
    @Override
    public void close() {
        connManager.close();
        clientBootstrap.close();
        resolver.close();
        eventLoopGroup.close();
        try {
            eventLoopGroup.getShutdownCompleteFuture().get(EVENTLOOP_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            LOGGER.atError().log("Error shutting down event loop", e);
        } catch (TimeoutException e) {
            LOGGER.atError().log("Timed out shutting down event loop");
        }
    }
}
