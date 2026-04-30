/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.exceptions.DeploymentException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.security.SecurityService;
import com.aws.greengrass.security.exceptions.MqttConnectionProviderException;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.ProxyUtils;
import com.aws.greengrass.util.RetryUtils;
import com.aws.greengrass.util.Utils;
import software.amazon.awssdk.crt.http.HttpProxyOptions;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;

import java.io.Closeable;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * A standalone MQTT connection for one-shot operations (pre-flight checks, status reporting)
 * independent of the main MqttClient. Not thread-safe — callers must not share instances across threads.
 */
@SuppressWarnings({"PMD.NullAssignment"})
public class StandaloneMqttConnector implements Closeable {
    private static final Logger logger = LogManager.getLogger(StandaloneMqttConnector.class);
    private static final long DISCONNECT_TIMEOUT_MS = 5000;
    private static final long PER_ATTEMPT_TIMEOUT_MS = 10_000;

    private final AwsIotMqttConnectionBuilder builder;
    private final String clientId;
    private MqttClientConnection connection;

    /**
     * Create a standalone MQTT connection.
     *
     * @param builder  configured MQTT connection builder (with certs, CA, endpoint)
     * @param clientId MQTT client ID
     */
    public StandaloneMqttConnector(AwsIotMqttConnectionBuilder builder, String clientId) {
        this.builder = builder;
        this.clientId = clientId;
    }

    /**
     * Build a StandaloneMqttConnector configured with device identity, proxy, and the given endpoint.
     * Extracts builder setup from the deployment layer so callers don't need to know about proxy/TLS details.
     *
     * <p>Proxy options are built directly rather than via {@code ProxyUtils.getHttpProxyOptions()} because
     * that method requires a {@code @NonNull ClientTlsContext} for HTTPS proxy tunneling. Standalone
     * connections use HTTP CONNECT tunneling (the common case for MQTT) which works without a TLS context.</p>
     *
     * @param securityService     provides the MQTT connection builder with device certs
     * @param deviceConfiguration provides endpoint, proxy, port, and CA configuration
     * @param endpoint            the IoT data endpoint to connect to
     * @param clientIdSuffix      suffix appended to thing name for the MQTT client ID
     * @return a configured StandaloneMqttConnector ready to call {@link #connect(long)}
     * @throws MqttConnectionProviderException if the device identity builder cannot be created
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public static StandaloneMqttConnector of(SecurityService securityService,
                                              DeviceConfiguration deviceConfiguration,
                                              String endpoint, String clientIdSuffix)
            throws MqttConnectionProviderException {
        String thingName = Coerce.toString(deviceConfiguration.getThingName());
        String clientId = thingName + clientIdSuffix;
        AwsIotMqttConnectionBuilder mqttBuilder = securityService.getDeviceIdentityMqttConnectionBuilder();
        int mqttPort = Coerce.toInt(deviceConfiguration.getMQTTNamespace()
                .findOrDefault(MqttClient.DEFAULT_MQTT_PORT, MqttClient.MQTT_PORT_KEY));
        mqttBuilder.withEndpoint(endpoint)
                .withCertificateAuthorityFromPath(null,
                        Coerce.toString(deviceConfiguration.getRootCAFilePath()))
                .withPort((short) mqttPort)
                .withCleanSession(true);
        configureProxy(mqttBuilder, deviceConfiguration, endpoint);
        return new StandaloneMqttConnector(mqttBuilder, clientId);
    }

    private static void configureProxy(AwsIotMqttConnectionBuilder mqttBuilder,
                                       DeviceConfiguration deviceConfiguration, String endpoint) {
        String proxyUrl = deviceConfiguration.getProxyUrl();
        if (Utils.isEmpty(proxyUrl)) {
            return;
        }
        HttpProxyOptions httpProxyOptions = new HttpProxyOptions();
        httpProxyOptions.setHost(ProxyUtils.getHostFromProxyUrl(proxyUrl));
        httpProxyOptions.setPort(ProxyUtils.getPortFromProxyUrl(proxyUrl));
        httpProxyOptions.setConnectionType(HttpProxyOptions.HttpProxyConnectionType.Tunneling);
        String proxyUsername = deviceConfiguration.getProxyUsername();
        if (Utils.isNotEmpty(proxyUsername)) {
            httpProxyOptions.setAuthorizationType(HttpProxyOptions.HttpProxyAuthorizationType.Basic);
            httpProxyOptions.setAuthorizationUsername(proxyUsername);
            httpProxyOptions.setAuthorizationPassword(deviceConfiguration.getProxyPassword());
        }
        String noProxy = Coerce.toString(deviceConfiguration.getNoProxyAddresses());
        if (Utils.isNotEmpty(noProxy) && Utils.isNotEmpty(endpoint)
                && Arrays.stream(noProxy.split(",")).anyMatch(endpoint::matches)) {
            return;
        }
        mqttBuilder.withHttpProxyOptions(httpProxyOptions);
    }

    /**
     * Connect to the MQTT endpoint with retries using exponential backoff.
     * The timeout controls the number of attempts (timeoutMs / perAttemptTimeout), not a hard wall-clock
     * deadline — actual elapsed time may exceed timeoutMs due to backoff sleep between attempts.
     *
     * <p>All exceptions are retryable to support JITR/JITP provisioning flows where the first connection
     * attempt fails and triggers provisioning, then subsequent retries succeed.</p>
     *
     * @param timeoutMs timeout budget in milliseconds used to derive the number of connection attempts
     * @throws DeploymentException with appropriate error code if all attempts fail
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void connect(long timeoutMs) throws DeploymentException {
        if (timeoutMs <= 0) {
            throw new DeploymentException("MQTT connection timeout must be positive, got " + timeoutMs,
                    DeploymentErrorCode.MQTT_CONNECTION_FAILED);
        }
        long perAttemptTimeout = Math.min(timeoutMs, PER_ATTEMPT_TIMEOUT_MS);
        int maxAttempts = Math.max(1, (int) (timeoutMs / perAttemptTimeout));
        RetryUtils.RetryConfig retryConfig = RetryUtils.RetryConfig.builder()
                .initialRetryInterval(Duration.ofSeconds(1))
                .maxRetryInterval(Duration.ofSeconds(10))
                .maxAttempt(maxAttempts)
                // All exceptions are retryable — supports JITR/JITP where first connect triggers provisioning
                .retryableExceptions(Collections.singletonList(Exception.class))
                .build();
        try {
            RetryUtils.runWithRetry(retryConfig, () -> {
                connectionCleanup();
                builder.withClientId(clientId);
                connection = builder.build();
                try {
                    connection.connect().get(perAttemptTimeout, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    connectionCleanup();
                    throw e;
                }
                logger.atInfo().kv("clientId", clientId).log("Standalone MQTT connection established");
                return null;
            }, "standalone-mqtt-connect", logger);
        } catch (InterruptedException e) {
            connectionCleanup();
            Thread.currentThread().interrupt();
            throw new DeploymentException("MQTT connection interrupted", e,
                    DeploymentErrorCode.MQTT_CONNECTION_FAILED);
        } catch (Exception e) {
            connectionCleanup();
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new DeploymentException("MQTT connection failed", e,
                    mapExceptionToErrorCode(cause));
        }
    }

    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void close() {
        if (connection != null) {
            try {
                connection.disconnect().get(DISCONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                logger.atDebug().setCause(e).log("Error disconnecting standalone MQTT connection");
            } finally {
                connectionCleanup();
            }
        }
    }

    private void connectionCleanup() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    static DeploymentErrorCode mapExceptionToErrorCode(Throwable cause) {
        if (cause == null) {
            return DeploymentErrorCode.MQTT_CONNECTION_FAILED;
        }
        // Unwrap ExecutionException from CompletableFuture.get()
        Throwable actual = cause instanceof ExecutionException && cause.getCause() != null
                ? cause.getCause() : cause;
        String message = actual.getMessage() == null ? "" : actual.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("tls") || message.contains("ssl") || message.contains("handshake")
                || message.contains("certificate")) {
            return DeploymentErrorCode.TLS_HANDSHAKE_FAILURE;
        }
        if (message.contains("not authorized") || message.contains("policy")
                || message.contains("connack")) {
            return DeploymentErrorCode.MISSING_MQTT_CONNECT_POLICY;
        }
        return DeploymentErrorCode.MQTT_CONNECTION_FAILED;
    }
}
