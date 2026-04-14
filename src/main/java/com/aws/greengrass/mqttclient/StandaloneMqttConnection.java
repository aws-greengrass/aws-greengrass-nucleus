/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.exceptions.DeploymentException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.RetryUtils;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;

import java.io.Closeable;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A standalone MQTT connection for one-shot operations (pre-flight checks, status reporting)
 * independent of the main MqttClient. Not thread-safe — callers must not share instances across threads.
 */
@SuppressWarnings({"PMD.NullAssignment"})
public class StandaloneMqttConnection implements Closeable {
    private static final Logger logger = LogManager.getLogger(StandaloneMqttConnection.class);
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
    public StandaloneMqttConnection(AwsIotMqttConnectionBuilder builder, String clientId) {
        this.builder = builder;
        this.clientId = clientId;
    }

    /**
     * Connect to the MQTT endpoint with retries using exponential backoff within the given timeout window.
     *
     * @param timeoutMs total timeout in milliseconds for all connection attempts
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
                .retryableExceptions(Arrays.asList(ExecutionException.class, TimeoutException.class))
                .build();
        try {
            RetryUtils.runWithRetry(retryConfig, () -> {
                connectionCleanup();
                builder.withClientId(clientId);
                connection = builder.build();
                connection.connect().get(perAttemptTimeout, TimeUnit.MILLISECONDS);
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
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new DeploymentException("MQTT connection failed", e,
                    mapExceptionToErrorCode(cause));
        }
    }

    /**
     * Publish a message. Used by Step 5 (status reporting to source account) — remove this comment when used.
     *
     * @param topic     MQTT topic
     * @param payload   message payload
     * @param qos       quality of service
     * @param timeoutMs publish timeout in milliseconds
     * @throws DeploymentException on failure
     */
    @SuppressWarnings("PMD.AvoidInstanceofChecksInCatchClause")
    public void publish(String topic, byte[] payload, QualityOfService qos, long timeoutMs)
            throws DeploymentException {
        if (connection == null) {
            throw new DeploymentException("Not connected", DeploymentErrorCode.MQTT_CONNECTION_FAILED);
        }
        try {
            MqttMessage message = new MqttMessage(topic, payload, qos);
            connection.publish(message, qos, false).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeploymentException("MQTT publish failed", e, DeploymentErrorCode.MQTT_CONNECTION_FAILED);
        } catch (ExecutionException | TimeoutException e) {
            throw new DeploymentException("MQTT publish failed", e, DeploymentErrorCode.MQTT_CONNECTION_FAILED);
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
        Throwable actual = cause instanceof java.util.concurrent.ExecutionException && cause.getCause() != null
                ? cause.getCause() : cause;
        String message = actual.getMessage() == null ? "" : actual.getMessage().toLowerCase();
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
