/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.exceptions.DeploymentException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("PMD.CloseResource")
@ExtendWith({GGExtension.class, MockitoExtension.class})
class StandaloneMqttConnectionTest {

    @Mock
    AwsIotMqttConnectionBuilder builder;
    @Mock
    MqttClientConnection mqttConnection;

    @BeforeEach
    void beforeEach() {
        lenient().when(builder.withClientId(any())).thenReturn(builder);
        lenient().when(builder.build()).thenReturn(mqttConnection);
    }

    @Test
    void GIVEN_valid_endpoint_WHEN_connect_and_close_THEN_succeeds() throws Exception {
        when(mqttConnection.connect()).thenReturn(CompletableFuture.completedFuture(true));
        when(mqttConnection.disconnect()).thenReturn(CompletableFuture.completedFuture(null));

        StandaloneMqttConnection conn = new StandaloneMqttConnection(builder, "thing-preflight");
        conn.connect(5000);
        conn.close();

        verify(mqttConnection).connect();
        verify(mqttConnection).disconnect();
    }

    @Test
    void GIVEN_connected_WHEN_publish_THEN_succeeds() throws Exception {
        when(mqttConnection.connect()).thenReturn(CompletableFuture.completedFuture(true));
        when(mqttConnection.publish(any(MqttMessage.class), any(QualityOfService.class), eq(false)))
                .thenReturn(CompletableFuture.completedFuture(0));

        StandaloneMqttConnection conn = new StandaloneMqttConnection(builder, "thing-report");
        conn.connect(5000);
        conn.publish("test/topic", "payload".getBytes(), QualityOfService.AT_LEAST_ONCE, 5000);

        verify(mqttConnection).publish(any(MqttMessage.class), any(QualityOfService.class), eq(false));
    }

    @Test
    void GIVEN_timeout_WHEN_connect_THEN_throws_MQTT_CONNECTION_FAILED() {
        // Each attempt hangs (never completes), bounded by PER_ATTEMPT_TIMEOUT_MS
        when(mqttConnection.connect()).thenReturn(new CompletableFuture<>());

        StandaloneMqttConnection conn = new StandaloneMqttConnection(builder, "thing-preflight");
        DeploymentException ex = assertThrows(DeploymentException.class, () -> conn.connect(3000));

        assertTrue(ex.getErrorCodes().contains(DeploymentErrorCode.MQTT_CONNECTION_FAILED));
    }

    @Test
    void GIVEN_tls_error_WHEN_connect_THEN_throws_TLS_HANDSHAKE_FAILURE() {
        CompletableFuture<Boolean> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new Exception("TLS handshake failed"));
        when(mqttConnection.connect()).thenReturn(failedFuture);

        StandaloneMqttConnection conn = new StandaloneMqttConnection(builder, "thing-preflight");
        DeploymentException ex = assertThrows(DeploymentException.class, () -> conn.connect(5000));

        assertTrue(ex.getErrorCodes().contains(DeploymentErrorCode.TLS_HANDSHAKE_FAILURE));
    }

    @Test
    void GIVEN_auth_rejection_WHEN_connect_THEN_throws_MISSING_MQTT_CONNECT_POLICY() {
        CompletableFuture<Boolean> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new Exception("Not authorized, connection refused"));
        when(mqttConnection.connect()).thenReturn(failedFuture);

        StandaloneMqttConnection conn = new StandaloneMqttConnection(builder, "thing-preflight");
        DeploymentException ex = assertThrows(DeploymentException.class, () -> conn.connect(5000));

        assertTrue(ex.getErrorCodes().contains(DeploymentErrorCode.MISSING_MQTT_CONNECT_POLICY));
    }

    @Test
    void GIVEN_connection_WHEN_close_called_twice_THEN_no_exception() throws Exception {
        when(mqttConnection.connect()).thenReturn(CompletableFuture.completedFuture(true));
        when(mqttConnection.disconnect()).thenReturn(CompletableFuture.completedFuture(null));

        StandaloneMqttConnection conn = new StandaloneMqttConnection(builder, "thing-preflight");
        conn.connect(5000);
        conn.close();
        assertDoesNotThrow(conn::close);
    }

    @Test
    void GIVEN_thing_name_and_suffix_WHEN_connect_THEN_client_id_is_correct() throws Exception {
        when(mqttConnection.connect()).thenReturn(CompletableFuture.completedFuture(true));

        StandaloneMqttConnection conn = new StandaloneMqttConnection(builder, "myThing-preflight");
        conn.connect(5000);

        verify(builder).withClientId("myThing-preflight");
    }

    @Test
    void GIVEN_error_code_mapping_WHEN_tls_message_THEN_returns_TLS_HANDSHAKE_FAILURE() {
        assertEquals(DeploymentErrorCode.TLS_HANDSHAKE_FAILURE,
                StandaloneMqttConnection.mapExceptionToErrorCode(new Exception("SSL handshake error")));
    }

    @Test
    void GIVEN_error_code_mapping_WHEN_auth_message_THEN_returns_MISSING_MQTT_CONNECT_POLICY() {
        assertEquals(DeploymentErrorCode.MISSING_MQTT_CONNECT_POLICY,
                StandaloneMqttConnection.mapExceptionToErrorCode(new Exception("Not authorized")));
    }

    @Test
    void GIVEN_error_code_mapping_WHEN_generic_message_THEN_returns_MQTT_CONNECTION_FAILED() {
        assertEquals(DeploymentErrorCode.MQTT_CONNECTION_FAILED,
                StandaloneMqttConnection.mapExceptionToErrorCode(new Exception("some error")));
    }

    @Test
    void GIVEN_transient_failure_WHEN_connect_THEN_retries_and_succeeds(ExtensionContext extContext) throws Exception {
        ignoreExceptionOfType(extContext, ExecutionException.class);
        AtomicInteger attempts = new AtomicInteger(0);
        when(mqttConnection.connect()).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() < 3) {
                CompletableFuture<Boolean> f = new CompletableFuture<>();
                f.completeExceptionally(new Exception("socket operation timed out"));
                return f;
            }
            return CompletableFuture.completedFuture(true);
        });

        StandaloneMqttConnection conn = new StandaloneMqttConnection(builder, "thing-retry");
        conn.connect(60_000);

        assertEquals(3, attempts.get());
        verify(builder, atLeast(3)).build();
    }

    @Test
    void GIVEN_persistent_failure_WHEN_connect_THEN_retries_until_timeout(ExtensionContext extContext) {
        ignoreExceptionOfType(extContext, ExecutionException.class);
        CompletableFuture<Boolean> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new Exception("connection refused"));
        when(mqttConnection.connect()).thenReturn(failedFuture);

        StandaloneMqttConnection conn = new StandaloneMqttConnection(builder, "thing-retry");
        DeploymentException ex = assertThrows(DeploymentException.class, () -> conn.connect(30_000));

        // With 30s timeout and 10s per-attempt, maxAttempts=3. All fail → DeploymentException.
        verify(builder, atLeast(2)).build();
        assertTrue(ex.getErrorCodes().contains(DeploymentErrorCode.MQTT_CONNECTION_FAILED),
                "connection refused is a network error, not a policy error");
    }

    @Test
    void GIVEN_error_code_mapping_WHEN_null_cause_THEN_returns_MQTT_CONNECTION_FAILED() {
        assertEquals(DeploymentErrorCode.MQTT_CONNECTION_FAILED,
                StandaloneMqttConnection.mapExceptionToErrorCode(null));
    }
}
