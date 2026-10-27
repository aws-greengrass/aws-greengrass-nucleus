/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.exceptions.DeploymentException;
import com.aws.greengrass.security.SecurityService;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.CrtRuntimeException;
import software.amazon.awssdk.crt.io.ClientTlsContext;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttException;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import software.amazon.awssdk.crt.mqtt.MqttMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class StandaloneMqttConnectorTest {
    private static final int TLS_HANDSHAKE_RECORD = 0x16;
    private static final String REMOTE_ENDPOINT = "endpoint.iot.us-east-1.amazonaws.com";
    private static final String LOOPBACK = InetAddress.getLoopbackAddress().getHostAddress();

    @Mock
    AwsIotMqttConnectionBuilder builder;
    @Mock(answer = Answers.RETURNS_SELF)
    AwsIotMqttConnectionBuilder identityBuilder;
    @Mock
    MqttClientConnection mqttConnection;
    @Mock
    SecurityService securityService;
    @Mock
    DeviceConfiguration deviceConfiguration;

    @BeforeEach
    void beforeEach() {
        lenient().when(builder.withClientId(any())).thenReturn(builder);
        lenient().when(builder.build()).thenReturn(mqttConnection);
        lenient().when(mqttConnection.disconnect()).thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void GIVEN_valid_endpoint_WHEN_connect_and_close_THEN_succeeds() throws Exception {
        when(mqttConnection.connect()).thenReturn(CompletableFuture.completedFuture(true));

        try (StandaloneMqttConnector conn = new StandaloneMqttConnector(builder, "thing-preflight")) {
            conn.connect(5000);
            conn.close();

            verify(mqttConnection).connect();
            verify(mqttConnection).disconnect();
        }
    }

    @Test
    void GIVEN_timeout_WHEN_connect_THEN_throws_MQTT_CONNECTION_FAILED() {
        // Each attempt hangs (never completes), bounded by PER_ATTEMPT_TIMEOUT_MS
        when(mqttConnection.connect()).thenReturn(new CompletableFuture<>());

        try (StandaloneMqttConnector conn = new StandaloneMqttConnector(builder, "thing-preflight")) {
            DeploymentException ex = assertThrows(DeploymentException.class, () -> conn.connect(3000));

            assertTrue(ex.getErrorCodes().contains(DeploymentErrorCode.MQTT_CONNECTION_FAILED));
        }
    }

    @Test
    void GIVEN_tls_error_WHEN_connect_THEN_throws_TLS_HANDSHAKE_FAILURE() {
        CompletableFuture<Boolean> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new Exception("TLS handshake failed"));
        when(mqttConnection.connect()).thenReturn(failedFuture);

        try (StandaloneMqttConnector conn = new StandaloneMqttConnector(builder, "thing-preflight")) {
            DeploymentException ex = assertThrows(DeploymentException.class, () -> conn.connect(5000));

            assertTrue(ex.getErrorCodes().contains(DeploymentErrorCode.TLS_HANDSHAKE_FAILURE));
        }
    }

    @Test
    void GIVEN_auth_rejection_WHEN_connect_THEN_throws_MISSING_MQTT_CONNECT_POLICY() {
        CompletableFuture<Boolean> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new Exception("Not authorized, connection refused"));
        when(mqttConnection.connect()).thenReturn(failedFuture);

        try (StandaloneMqttConnector conn = new StandaloneMqttConnector(builder, "thing-preflight")) {
            DeploymentException ex = assertThrows(DeploymentException.class, () -> conn.connect(5000));

            assertTrue(ex.getErrorCodes().contains(DeploymentErrorCode.MISSING_MQTT_CONNECT_POLICY));
        }
    }

    @Test
    void GIVEN_connection_WHEN_close_called_twice_THEN_no_exception() throws Exception {
        when(mqttConnection.connect()).thenReturn(CompletableFuture.completedFuture(true));

        try (StandaloneMqttConnector conn = new StandaloneMqttConnector(builder, "thing-preflight")) {
            conn.connect(5000);
            conn.close();
            assertDoesNotThrow(conn::close);
            verify(builder).close();
        }
    }

    @Test
    void GIVEN_thing_name_and_suffix_WHEN_connect_THEN_client_id_is_correct() throws Exception {
        when(mqttConnection.connect()).thenReturn(CompletableFuture.completedFuture(true));

        try (StandaloneMqttConnector conn = new StandaloneMqttConnector(builder, "myThing-preflight")) {
            conn.connect(5000);

            verify(builder).withClientId("myThing-preflight");
        }
    }

    @Test
    void GIVEN_error_code_mapping_WHEN_tls_message_THEN_returns_TLS_HANDSHAKE_FAILURE() {
        assertEquals(DeploymentErrorCode.TLS_HANDSHAKE_FAILURE,
                StandaloneMqttConnector.mapExceptionToErrorCode(new Exception("SSL handshake error")));
    }

    @Test
    void GIVEN_error_code_mapping_WHEN_auth_message_THEN_returns_MISSING_MQTT_CONNECT_POLICY() {
        assertEquals(DeploymentErrorCode.MISSING_MQTT_CONNECT_POLICY,
                StandaloneMqttConnector.mapExceptionToErrorCode(new Exception("Not authorized")));
    }

    @Test
    void GIVEN_error_code_mapping_WHEN_generic_message_THEN_returns_MQTT_CONNECTION_FAILED() {
        assertEquals(DeploymentErrorCode.MQTT_CONNECTION_FAILED,
                StandaloneMqttConnector.mapExceptionToErrorCode(new Exception("some error")));
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

        try (StandaloneMqttConnector conn = new StandaloneMqttConnector(builder, "thing-retry")) {
            conn.connect(60_000);

            assertEquals(3, attempts.get());
            verify(builder, atLeast(3)).build();
        }
    }

    @Test
    void GIVEN_persistent_failure_WHEN_connect_THEN_retries_until_timeout(ExtensionContext extContext) {
        ignoreExceptionOfType(extContext, ExecutionException.class);
        CompletableFuture<Boolean> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new Exception("connection refused"));
        when(mqttConnection.connect()).thenReturn(failedFuture);

        try (StandaloneMqttConnector conn = new StandaloneMqttConnector(builder, "thing-retry")) {
            DeploymentException ex = assertThrows(DeploymentException.class, () -> conn.connect(30_000));

            // With 30s timeout and 10s per-attempt, maxAttempts=3. All fail → DeploymentException.
            verify(builder, atLeast(2)).build();
            assertTrue(ex.getErrorCodes().contains(DeploymentErrorCode.MQTT_CONNECTION_FAILED),
                    "connection refused is a network error, not a policy error");
        }
    }

    @Test
    void GIVEN_error_code_mapping_WHEN_null_cause_THEN_returns_MQTT_CONNECTION_FAILED() {
        assertEquals(DeploymentErrorCode.MQTT_CONNECTION_FAILED,
                StandaloneMqttConnector.mapExceptionToErrorCode(null));
    }

    // --- publish() tests ---

    @Test
    void GIVEN_connected_WHEN_publish_THEN_succeeds() throws Exception {
        when(mqttConnection.connect()).thenReturn(CompletableFuture.completedFuture(true));
        when(mqttConnection.publish(any(MqttMessage.class)))
                .thenReturn(CompletableFuture.completedFuture(0));

        try (StandaloneMqttConnector conn = new StandaloneMqttConnector(builder, "thing-publish")) {
            conn.connect(5000);
            conn.publish("test/topic", "hello".getBytes(StandardCharsets.UTF_8),
                    QualityOfService.AT_LEAST_ONCE, 5000);

            verify(mqttConnection).publish(any(MqttMessage.class));
        }
    }

    @Test
    void GIVEN_not_connected_WHEN_publish_THEN_throws_deployment_exception() {
        try (StandaloneMqttConnector conn = new StandaloneMqttConnector(builder, "thing-publish")) {
            DeploymentException ex = assertThrows(DeploymentException.class,
                    () -> conn.publish("test/topic", "hello".getBytes(StandardCharsets.UTF_8),
                            QualityOfService.AT_LEAST_ONCE, 5000));
            assertTrue(ex.getMessage().contains("not connected"));
        }
    }

    @Test
    void GIVEN_transient_publish_failure_WHEN_publish_THEN_retries_and_succeeds(
            ExtensionContext extContext) throws Exception {
        ignoreExceptionOfType(extContext, ExecutionException.class);
        when(mqttConnection.connect()).thenReturn(CompletableFuture.completedFuture(true));

        AtomicInteger publishAttempts = new AtomicInteger(0);
        when(mqttConnection.publish(any(MqttMessage.class))).thenAnswer(invocation -> {
            if (publishAttempts.incrementAndGet() < 3) {
                CompletableFuture<Integer> f = new CompletableFuture<>();
                f.completeExceptionally(new Exception("PUBACK timeout"));
                return f;
            }
            return CompletableFuture.completedFuture(0);
        });

        try (StandaloneMqttConnector conn = new StandaloneMqttConnector(builder, "thing-publish")) {
            conn.connect(60_000);
            conn.publish("test/topic", "hello".getBytes(StandardCharsets.UTF_8),
                    QualityOfService.AT_LEAST_ONCE, 60_000);

            assertEquals(3, publishAttempts.get());
        }
    }

    @Test
    void GIVEN_persistent_publish_failure_WHEN_publish_THEN_throws_after_retries(
            ExtensionContext extContext) {
        ignoreExceptionOfType(extContext, ExecutionException.class);
        when(mqttConnection.connect()).thenReturn(CompletableFuture.completedFuture(true));

        CompletableFuture<Integer> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new Exception("connection lost"));
        when(mqttConnection.publish(any(MqttMessage.class))).thenReturn(failedFuture);

        try (StandaloneMqttConnector conn = new StandaloneMqttConnector(builder, "thing-publish")) {
            assertDoesNotThrow(() -> conn.connect(5000));

            DeploymentException ex = assertThrows(DeploymentException.class,
                    () -> conn.publish("test/topic", "hello".getBytes(StandardCharsets.UTF_8),
                            QualityOfService.AT_LEAST_ONCE, 30_000));
            assertTrue(ex.getMessage().contains("publish failed"));
        }
    }

    // --- proxy wire tests: loopback listeners stand in for the proxy and the IoT endpoint ---

    @Test
    void GIVEN_https_proxy_WHEN_connect_THEN_proxy_connection_starts_with_tls_handshake(
            ExtensionContext extContext) throws Exception {
        try (ServerSocket proxy = loopbackListener()) {
            assertEquals(TLS_HANDSHAKE_RECORD, connectAndCapture(extContext,
                    "https://" + LOOPBACK + ":" + proxy.getLocalPort(), REMOTE_ENDPOINT, MqttClient.DEFAULT_MQTT_PORT, proxy,
                    StandaloneMqttConnectorTest::firstByte));
        }
    }

    @Test
    void GIVEN_http_proxy_WHEN_connect_THEN_proxy_connection_starts_with_plaintext_connect(
            ExtensionContext extContext) throws Exception {
        try (ServerSocket proxy = loopbackListener()) {
            assertEquals('C', connectAndCapture(extContext, "http://" + LOOPBACK + ":" + proxy.getLocalPort(),
                    REMOTE_ENDPOINT, MqttClient.DEFAULT_MQTT_PORT, proxy, StandaloneMqttConnectorTest::firstByte));
        }
    }

    @Test
    void GIVEN_proxy_url_with_credentials_WHEN_connect_THEN_proxy_receives_basic_auth(
            ExtensionContext extContext) throws Exception {
        try (ServerSocket proxy = loopbackListener()) {
            String request = connectAndCapture(extContext, "http://user:pass@" + LOOPBACK + ":" + proxy.getLocalPort(),
                    REMOTE_ENDPOINT, MqttClient.DEFAULT_MQTT_PORT, proxy, StandaloneMqttConnectorTest::readHttpHead);
            assertTrue(request.startsWith("CONNECT " + REMOTE_ENDPOINT + ":"), request);
            // base64("user:pass")
            assertTrue(request.contains("Proxy-Authorization: Basic dXNlcjpwYXNz\r\n"), request);
        }
    }

    @Test
    void GIVEN_endpoint_in_no_proxy_WHEN_connect_THEN_connects_directly_without_proxy(
            ExtensionContext extContext) throws Exception {
        try (ServerSocket proxy = loopbackListener(); ServerSocket endpoint = loopbackListener()) {
            when(deviceConfiguration.getNoProxyAddresses()).thenReturn(LOOPBACK);
            assertEquals(TLS_HANDSHAKE_RECORD, connectAndCapture(extContext,
                    "http://" + LOOPBACK + ":" + proxy.getLocalPort(), LOOPBACK, endpoint.getLocalPort(), endpoint,
                    StandaloneMqttConnectorTest::firstByte));
            proxy.setSoTimeout(200);
            assertThrows(SocketTimeoutException.class, proxy::accept);
        }
    }

    @Test
    void GIVEN_unparseable_proxy_url_WHEN_connect_THEN_fails_with_connection_error_and_close_releases_builder(
            ExtensionContext extContext) throws Exception {
        ignoreExceptionOfType(extContext, IllegalArgumentException.class);
        try (Context context = new Context()) {
            stubMockDeviceIdentity(context);
            when(deviceConfiguration.getProxyUrl()).thenReturn("https://proxy host:8080");

            try (StandaloneMqttConnector conn = StandaloneMqttConnector.forEndpointSwitch(securityService,
                    deviceConfiguration, REMOTE_ENDPOINT)) {
                DeploymentException e = assertThrows(DeploymentException.class, () -> conn.connect(1000));
                assertEquals(IllegalArgumentException.class, e.getCause().getClass());
                assertTrue(e.getErrorCodes().contains(DeploymentErrorCode.MQTT_CONNECTION_FAILED));
            }
            verify(identityBuilder).close();
        }
    }

    @Test
    void GIVEN_unreadable_root_ca_WHEN_connect_THEN_same_failure_with_or_without_proxy(ExtensionContext extContext)
            throws Exception {
        ignoreExceptionOfType(extContext, CrtRuntimeException.class);
        DeploymentException direct = connectWithUnreadableRootCa(null);
        for (String proxyUrl : new String[]{"http://" + LOOPBACK + ":3128", "https://" + LOOPBACK + ":3128"}) {
            DeploymentException viaProxy = connectWithUnreadableRootCa(proxyUrl);
            assertEquals(direct.getCause().getClass(), viaProxy.getCause().getClass(), proxyUrl);
            assertEquals(direct.getErrorCodes(), viaProxy.getErrorCodes(), proxyUrl);
        }
    }

    @Test
    void GIVEN_https_proxy_WHEN_connect_and_close_twice_THEN_one_proxy_tls_context_is_closed_once() throws Exception {
        when(mqttConnection.connect()).thenReturn(CompletableFuture.completedFuture(true));
        try (Context context = new Context();
             MockedConstruction<ClientTlsContext> tlsContexts = mockConstruction(ClientTlsContext.class)) {
            stubMockDeviceIdentity(context);
            when(deviceConfiguration.getProxyUrl()).thenReturn("https://" + LOOPBACK + ":3128");

            try (StandaloneMqttConnector conn = StandaloneMqttConnector.forEndpointSwitch(securityService,
                    deviceConfiguration, REMOTE_ENDPOINT)) {
                conn.connect(5000);
                conn.close();
            }
            assertEquals(1, tlsContexts.constructed().size());
            verify(tlsContexts.constructed().get(0)).close();
            verify(identityBuilder).close();
        }
    }

    @Test
    void GIVEN_http_proxy_WHEN_connect_THEN_proxy_used_without_tls_context() throws Exception {
        when(mqttConnection.connect()).thenReturn(CompletableFuture.completedFuture(true));
        try (Context context = new Context();
             MockedConstruction<ClientTlsContext> tlsContexts = mockConstruction(ClientTlsContext.class)) {
            stubMockDeviceIdentity(context);
            when(deviceConfiguration.getProxyUrl()).thenReturn("http://" + LOOPBACK + ":3128");

            try (StandaloneMqttConnector conn = StandaloneMqttConnector.forEndpointSwitch(securityService,
                    deviceConfiguration, REMOTE_ENDPOINT)) {
                conn.connect(5000);
            }
            verify(identityBuilder).withHttpProxyOptions(any());
            assertEquals(0, tlsContexts.constructed().size());
        }
    }

    @Test
    void GIVEN_mqtt_port_above_short_range_WHEN_create_THEN_builder_gets_configured_port() throws Exception {
        try (Context context = new Context()) {
            stubMockDeviceIdentity(context);
            deviceConfiguration.getMQTTNamespace().lookup(MqttClient.MQTT_PORT_KEY).withValue(40_000);

            try (StandaloneMqttConnector conn = StandaloneMqttConnector.forEndpointSwitch(securityService,
                    deviceConfiguration, REMOTE_ENDPOINT)) {
                verify(identityBuilder).withPort(40_000);
            }
        }
    }

    private DeploymentException connectWithUnreadableRootCa(String proxyUrl) throws Exception {
        try (Context context = new Context()) {
            stubDeviceIdentity(context, MqttClient.DEFAULT_MQTT_PORT);
            when(deviceConfiguration.getProxyUrl()).thenReturn(proxyUrl);
            when(deviceConfiguration.getRootCAFilePath()).thenReturn(
                    new Configuration(context).lookup("rootCaPath").withValue("/nonexistent/AmazonRootCA1.pem"));

            try (StandaloneMqttConnector conn = StandaloneMqttConnector.forEndpointSwitch(securityService,
                    deviceConfiguration, REMOTE_ENDPOINT)) {
                return assertThrows(DeploymentException.class, () -> conn.connect(1000));
            }
        }
    }

    private void stubMockDeviceIdentity(Context context) throws Exception {
        lenient().when(identityBuilder.build()).thenReturn(mqttConnection);
        when(securityService.getDeviceIdentityMqttConnectionBuilder()).thenReturn(identityBuilder);
        when(deviceConfiguration.getMQTTNamespace()).thenReturn(new Configuration(context).lookupTopics("mqtt"));
    }

    private static ServerSocket loopbackListener() throws IOException {
        return new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    }

    private void stubDeviceIdentity(Context context, int mqttPort) throws Exception {
        when(securityService.getDeviceIdentityMqttConnectionBuilder())
                .thenReturn(AwsIotMqttConnectionBuilder.newDefaultBuilder());
        Topics mqtt = new Configuration(context).lookupTopics("mqtt");
        mqtt.lookup(MqttClient.MQTT_PORT_KEY).withValue(mqttPort);
        when(deviceConfiguration.getMQTTNamespace()).thenReturn(mqtt);
    }

    /**
     * Runs the real factory and connect() and returns what {@code reader} read from the first connection that
     * {@code listener} accepted.
     */
    private <T> T connectAndCapture(ExtensionContext extContext, String proxyUrl, String endpoint, int mqttPort,
                                    ServerSocket listener, IoFunction<T> reader) throws Exception {
        ignoreExceptionOfType(extContext, ExecutionException.class);
        ignoreExceptionOfType(extContext, MqttException.class);
        try (Context context = new Context()) {
            listener.setSoTimeout(5000);
            CompletableFuture<T> captured = CompletableFuture.supplyAsync(() -> {
                try (Socket s = listener.accept(); InputStream in = s.getInputStream()) {
                    return reader.apply(in);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            stubDeviceIdentity(context, mqttPort);
            when(deviceConfiguration.getProxyUrl()).thenReturn(proxyUrl);

            try (StandaloneMqttConnector conn = StandaloneMqttConnector.forEndpointSwitch(securityService,
                    deviceConfiguration, endpoint)) {
                assertThrows(DeploymentException.class, () -> conn.connect(2000));
            }
            return captured.get(5, TimeUnit.SECONDS);
        }
    }

    private static Integer firstByte(InputStream in) throws IOException {
        return in.read();
    }

    private static String readHttpHead(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (head.indexOf("\r\n\r\n") < 0) {
            int b = in.read();
            if (b == -1) {
                break;
            }
            head.append((char) b);
        }
        return head.toString();
    }

    @FunctionalInterface
    private interface IoFunction<T> {
        T apply(InputStream in) throws IOException;
    }
}
