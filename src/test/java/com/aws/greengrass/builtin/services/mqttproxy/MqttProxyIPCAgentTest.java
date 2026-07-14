/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.mqttproxy;

import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.AuthorizationHandler.ResourceLookupPolicy;
import com.aws.greengrass.authorization.Permission;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.MqttRequestException;
import com.aws.greengrass.mqttclient.spool.SpoolerStoreException;
import com.aws.greengrass.mqttclient.v5.Publish;
import com.aws.greengrass.mqttclient.v5.PublishResponse;
import com.aws.greengrass.mqttclient.v5.Subscribe;
import com.aws.greengrass.mqttclient.v5.Unsubscribe;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCService;
import software.amazon.awssdk.aws.greengrass.model.InvalidArgumentsError;
import software.amazon.awssdk.aws.greengrass.model.IoTCoreMessage;
import software.amazon.awssdk.aws.greengrass.model.MQTTMessage;
import software.amazon.awssdk.aws.greengrass.model.PublishToIoTCoreRequest;
import software.amazon.awssdk.aws.greengrass.model.PublishToIoTCoreResponse;
import software.amazon.awssdk.aws.greengrass.model.QOS;
import software.amazon.awssdk.aws.greengrass.model.ServiceError;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToIoTCoreRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToIoTCoreResponse;
import software.amazon.awssdk.aws.greengrass.model.SubscriptionMode;
import software.amazon.awssdk.aws.greengrass.model.UnauthorizedError;
import software.amazon.awssdk.crt.eventstream.ServerConnectionContinuation;
import software.amazon.awssdk.eventstreamrpc.AuthenticationData;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.aws.greengrass.ipc.modules.MqttProxyIPCService.MQTT_PROXY_SERVICE_NAME;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class MqttProxyIPCAgentTest {
    private static final String TEST_SERVICE = "TestService";
    private static final String TEST_TOPIC = "TestTopic";
    private static final byte[] TEST_PAYLOAD = "TestPayload".getBytes(StandardCharsets.UTF_8);

    @Mock
    OperationContinuationHandlerContext mockContext;

    @Mock
    AuthenticationData mockAuthenticationData;

    @Mock
    MqttClient mqttClient;

    @Mock
    AuthorizationHandler authorizationHandler;

    private MqttProxyIPCAgent mqttProxyIPCAgent;

    @BeforeEach
    void setup() throws MqttRequestException {
        lenient().when(mockContext.getContinuation()).thenReturn(mock(ServerConnectionContinuation.class));
        lenient().when(mockContext.getAuthenticationData()).thenReturn(mockAuthenticationData);
        lenient().when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_SERVICE);
        mqttProxyIPCAgent = new MqttProxyIPCAgent();
        mqttProxyIPCAgent.setMqttClient(mqttClient);
        mqttProxyIPCAgent.setAuthorizationHandler(authorizationHandler);
        lenient().when(mqttClient.subscribe(any(Subscribe.class))).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(mqttClient.unsubscribe(any(Unsubscribe.class))).thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void GIVEN_MqttProxyIPCAgent_WHEN_publish_on_topic_THEN_message_published() throws Exception {
        PublishToIoTCoreRequest publishToIoTCoreRequest = new PublishToIoTCoreRequest();
        publishToIoTCoreRequest.setPayload(TEST_PAYLOAD);
        publishToIoTCoreRequest.setTopicName(TEST_TOPIC);
        publishToIoTCoreRequest.setQos(QOS.AT_LEAST_ONCE);

        when(mqttClient.publish(any(Publish.class))).thenReturn(new PublishResponse());
        when(authorizationHandler.isAuthorized(any(), any(), any())).thenReturn(true);
        ArgumentCaptor<Publish> publishRequestArgumentCaptor = ArgumentCaptor.forClass(Publish.class);

        try (MqttProxyIPCAgent.PublishToIoTCoreOperationHandler publishToIoTCoreOperationHandler
                     = mqttProxyIPCAgent.getPublishToIoTCoreOperationHandler(mockContext)) {
            PublishToIoTCoreResponse publishToIoTCoreResponse
                    = publishToIoTCoreOperationHandler.handleRequest(publishToIoTCoreRequest);

            assertNotNull(publishToIoTCoreResponse);
            verify(authorizationHandler).isAuthorized(MQTT_PROXY_SERVICE_NAME, Permission.builder().principal(TEST_SERVICE)
                    .operation(GreengrassCoreIPCService.PUBLISH_TO_IOT_CORE)
                    .resource(TEST_TOPIC).build(), ResourceLookupPolicy.MQTT_STYLE);

            verify(mqttClient).publish(publishRequestArgumentCaptor.capture());
            Publish capturedPublishRequest = publishRequestArgumentCaptor.getValue();
            assertThat(capturedPublishRequest.getPayload(), is(TEST_PAYLOAD));
            assertThat(capturedPublishRequest.getTopic(), is(TEST_TOPIC));
            assertThat(capturedPublishRequest.getQos(), is(com.aws.greengrass.mqttclient.v5.QOS.AT_LEAST_ONCE));
        }
    }

    @Test
    void GIVEN_full_mqtt_spool_WHEN_publish_on_topic_THEN_service_error_thrown() throws Exception {
        PublishToIoTCoreRequest publishToIoTCoreRequest = new PublishToIoTCoreRequest();
        publishToIoTCoreRequest.setPayload(TEST_PAYLOAD);
        publishToIoTCoreRequest.setTopicName(TEST_TOPIC);
        publishToIoTCoreRequest.setQos(QOS.AT_LEAST_ONCE);

        when(mqttClient.publish(any(Publish.class))).thenThrow(new SpoolerStoreException("Spool full"));
        when(authorizationHandler.isAuthorized(any(), any(), any())).thenReturn(true);

        try (MqttProxyIPCAgent.PublishToIoTCoreOperationHandler publishToIoTCoreOperationHandler
                     = mqttProxyIPCAgent.getPublishToIoTCoreOperationHandler(mockContext)) {
            assertThrows(ServiceError.class, () -> {
                publishToIoTCoreOperationHandler.handleRequest(publishToIoTCoreRequest);
            });
        }
    }

    @Test
    void GIVEN_MqttProxyIPCAgent_WHEN_subscribe_to_topic_THEN_topic_subscribed() throws Exception {
        SubscribeToIoTCoreRequest subscribeToIoTCoreRequest = new SubscribeToIoTCoreRequest();
        subscribeToIoTCoreRequest.setTopicName(TEST_TOPIC);
        subscribeToIoTCoreRequest.setQos(QOS.AT_LEAST_ONCE);

        when(authorizationHandler.isAuthorized(any(), any(), any())).thenReturn(true);
        ArgumentCaptor<Subscribe> subscribeRequestArgumentCaptor
                = ArgumentCaptor.forClass(Subscribe.class);
        ArgumentCaptor<Unsubscribe> unsubscribeRequestArgumentCaptor
                = ArgumentCaptor.forClass(Unsubscribe.class);
        ArgumentCaptor<IoTCoreMessage> ioTCoreMessageArgumentCaptor = ArgumentCaptor.forClass(IoTCoreMessage.class);

        try (MqttProxyIPCAgent.SubscribeToIoTCoreOperationHandler subscribeToIoTCoreOperationHandler
                     = spy(mqttProxyIPCAgent.getSubscribeToIoTCoreOperationHandler(mockContext))) {
            SubscribeToIoTCoreResponse subscribeToIoTCoreResponse =
                    subscribeToIoTCoreOperationHandler.handleRequestAsync(subscribeToIoTCoreRequest)
                            .get(1, TimeUnit.SECONDS);
            subscribeToIoTCoreOperationHandler.afterHandleRequest();

            assertNotNull(subscribeToIoTCoreResponse);
            verify(authorizationHandler).isAuthorized(MQTT_PROXY_SERVICE_NAME, Permission.builder().principal(TEST_SERVICE)
                    .operation(GreengrassCoreIPCService.SUBSCRIBE_TO_IOT_CORE)
                    .resource(TEST_TOPIC).build(), ResourceLookupPolicy.MQTT_STYLE);

            verify(mqttClient).subscribe(subscribeRequestArgumentCaptor.capture());
            Subscribe capturedSubscribeRequest = subscribeRequestArgumentCaptor.getValue();
            assertThat(capturedSubscribeRequest.getTopic(), is(TEST_TOPIC));
            assertThat(capturedSubscribeRequest.getQos(), is(com.aws.greengrass.mqttclient.v5.QOS.AT_LEAST_ONCE));

            Consumer<Publish> callback = capturedSubscribeRequest.getCallback();
            Publish message = Publish.builder().payload(TEST_PAYLOAD).topic(TEST_TOPIC).build();
            doReturn(new CompletableFuture<>()).when(subscribeToIoTCoreOperationHandler).sendStreamEvent(any());
            callback.accept(message);
            verify(subscribeToIoTCoreOperationHandler).sendStreamEvent(ioTCoreMessageArgumentCaptor.capture());
            MQTTMessage mqttMessage = ioTCoreMessageArgumentCaptor.getValue().getMessage();
            assertThat(mqttMessage.getPayload(), is(TEST_PAYLOAD));
            assertThat(mqttMessage.getTopicName(), is(TEST_TOPIC));

            subscribeToIoTCoreOperationHandler.onStreamClosed();
            verify(mqttClient).unsubscribe(unsubscribeRequestArgumentCaptor.capture());
            Unsubscribe capturedUnsubscribedRequest = unsubscribeRequestArgumentCaptor.getValue();
            assertThat(capturedUnsubscribedRequest.getTopic(), is(TEST_TOPIC));
            assertThat(capturedUnsubscribedRequest.getSubscriptionCallback(), is(callback));
            // Absent subscriptionMode must take the cloud path (skipCloudSubscribe defaults to false).
            assertThat(capturedSubscribeRequest.isSkipCloudSubscribe(), is(false));
        }
    }

    // ---- RECEIVE_ONLY subscribe ----

    @Test
    void GIVEN_receive_only_and_no_qos_WHEN_subscribe_THEN_receive_only_registered_and_no_error() throws Exception {
        SubscribeToIoTCoreRequest subscribeToIoTCoreRequest = new SubscribeToIoTCoreRequest();
        subscribeToIoTCoreRequest.setTopicName(TEST_TOPIC);
        subscribeToIoTCoreRequest.setSubscriptionMode(SubscriptionMode.RECEIVE_ONLY);
        // No qos is set: RECEIVE_ONLY does not require it.

        when(authorizationHandler.isAuthorized(any(), any(), any())).thenReturn(true);
        ArgumentCaptor<Subscribe> subscribeRequestArgumentCaptor = ArgumentCaptor.forClass(Subscribe.class);
        ArgumentCaptor<Unsubscribe> unsubscribeRequestArgumentCaptor = ArgumentCaptor.forClass(Unsubscribe.class);
        ArgumentCaptor<IoTCoreMessage> ioTCoreMessageArgumentCaptor = ArgumentCaptor.forClass(IoTCoreMessage.class);

        try (MqttProxyIPCAgent.SubscribeToIoTCoreOperationHandler subscribeToIoTCoreOperationHandler
                     = spy(mqttProxyIPCAgent.getSubscribeToIoTCoreOperationHandler(mockContext))) {
            SubscribeToIoTCoreResponse response =
                    subscribeToIoTCoreOperationHandler.handleRequestAsync(subscribeToIoTCoreRequest)
                            .get(1, TimeUnit.SECONDS);
            subscribeToIoTCoreOperationHandler.afterHandleRequest();

            assertNotNull(response);
            // Authorization is checked for the requested topic.
            verify(authorizationHandler).isAuthorized(MQTT_PROXY_SERVICE_NAME, Permission.builder()
                    .principal(TEST_SERVICE).operation(GreengrassCoreIPCService.SUBSCRIBE_TO_IOT_CORE)
                    .resource(TEST_TOPIC).build(), ResourceLookupPolicy.MQTT_STYLE);

            verify(mqttClient).subscribe(subscribeRequestArgumentCaptor.capture());
            Subscribe capturedSubscribeRequest = subscribeRequestArgumentCaptor.getValue();
            assertThat(capturedSubscribeRequest.getTopic(), is(TEST_TOPIC));
            assertThat(capturedSubscribeRequest.isSkipCloudSubscribe(), is(true));

            // Inbound delivery streams to the component.
            Consumer<Publish> callback = capturedSubscribeRequest.getCallback();
            Publish message = Publish.builder().payload(TEST_PAYLOAD).topic(TEST_TOPIC).build();
            doReturn(new CompletableFuture<>()).when(subscribeToIoTCoreOperationHandler).sendStreamEvent(any());
            callback.accept(message);
            verify(subscribeToIoTCoreOperationHandler).sendStreamEvent(ioTCoreMessageArgumentCaptor.capture());
            MQTTMessage mqttMessage = ioTCoreMessageArgumentCaptor.getValue().getMessage();
            assertThat(mqttMessage.getTopicName(), is(TEST_TOPIC));

            // Stream close unsubscribes the registration.
            subscribeToIoTCoreOperationHandler.onStreamClosed();
            verify(mqttClient).unsubscribe(unsubscribeRequestArgumentCaptor.capture());
            assertThat(unsubscribeRequestArgumentCaptor.getValue().getTopic(), is(TEST_TOPIC));
            assertThat(unsubscribeRequestArgumentCaptor.getValue().getSubscriptionCallback(), is(callback));
        }
    }

    @Test
    void GIVEN_receive_only_with_qos_supplied_WHEN_subscribe_THEN_qos_ignored_and_receive_only_registered() throws Exception {
        SubscribeToIoTCoreRequest subscribeToIoTCoreRequest = new SubscribeToIoTCoreRequest();
        subscribeToIoTCoreRequest.setTopicName(TEST_TOPIC);
        subscribeToIoTCoreRequest.setQos(QOS.AT_LEAST_ONCE);
        subscribeToIoTCoreRequest.setSubscriptionMode(SubscriptionMode.RECEIVE_ONLY);

        when(authorizationHandler.isAuthorized(any(), any(), any())).thenReturn(true);
        ArgumentCaptor<Subscribe> subscribeRequestArgumentCaptor = ArgumentCaptor.forClass(Subscribe.class);

        try (MqttProxyIPCAgent.SubscribeToIoTCoreOperationHandler subscribeToIoTCoreOperationHandler
                     = spy(mqttProxyIPCAgent.getSubscribeToIoTCoreOperationHandler(mockContext))) {
            SubscribeToIoTCoreResponse response =
                    subscribeToIoTCoreOperationHandler.handleRequestAsync(subscribeToIoTCoreRequest)
                            .get(1, TimeUnit.SECONDS);
            assertNotNull(response);

            verify(mqttClient).subscribe(subscribeRequestArgumentCaptor.capture());
            Subscribe capturedSubscribeRequest = subscribeRequestArgumentCaptor.getValue();
            // The supplied qos is ignored; the request is registered receive-only.
            assertThat(capturedSubscribeRequest.isSkipCloudSubscribe(), is(true));
        }
    }

    @Test
    void GIVEN_receive_only_with_invalid_qos_WHEN_subscribe_THEN_qos_validation_skipped() throws Exception {
        SubscribeToIoTCoreRequest subscribeToIoTCoreRequest = new SubscribeToIoTCoreRequest();
        subscribeToIoTCoreRequest.setTopicName(TEST_TOPIC);
        // "10" is not a valid qos; the request must still succeed in RECEIVE_ONLY mode.
        subscribeToIoTCoreRequest.setQos("10");
        subscribeToIoTCoreRequest.setSubscriptionMode(SubscriptionMode.RECEIVE_ONLY);

        when(authorizationHandler.isAuthorized(any(), any(), any())).thenReturn(true);
        ArgumentCaptor<Subscribe> subscribeRequestArgumentCaptor = ArgumentCaptor.forClass(Subscribe.class);

        try (MqttProxyIPCAgent.SubscribeToIoTCoreOperationHandler subscribeToIoTCoreOperationHandler
                     = mqttProxyIPCAgent.getSubscribeToIoTCoreOperationHandler(mockContext)) {
            SubscribeToIoTCoreResponse response =
                    subscribeToIoTCoreOperationHandler.handleRequestAsync(subscribeToIoTCoreRequest)
                            .get(1, TimeUnit.SECONDS);
            assertNotNull(response);

            verify(mqttClient).subscribe(subscribeRequestArgumentCaptor.capture());
            assertThat(subscribeRequestArgumentCaptor.getValue().isSkipCloudSubscribe(), is(true));
        }
    }

    @Test
    void GIVEN_explicit_subscribe_mode_WHEN_subscribe_THEN_cloud_path_taken() throws Exception {
        SubscribeToIoTCoreRequest subscribeToIoTCoreRequest = new SubscribeToIoTCoreRequest();
        subscribeToIoTCoreRequest.setTopicName(TEST_TOPIC);
        subscribeToIoTCoreRequest.setQos(QOS.AT_LEAST_ONCE);
        subscribeToIoTCoreRequest.setSubscriptionMode(SubscriptionMode.SUBSCRIBE);

        when(authorizationHandler.isAuthorized(any(), any(), any())).thenReturn(true);
        ArgumentCaptor<Subscribe> subscribeRequestArgumentCaptor = ArgumentCaptor.forClass(Subscribe.class);

        try (MqttProxyIPCAgent.SubscribeToIoTCoreOperationHandler subscribeToIoTCoreOperationHandler
                     = spy(mqttProxyIPCAgent.getSubscribeToIoTCoreOperationHandler(mockContext))) {
            subscribeToIoTCoreOperationHandler.handleRequestAsync(subscribeToIoTCoreRequest).get(1, TimeUnit.SECONDS);

            verify(mqttClient).subscribe(subscribeRequestArgumentCaptor.capture());
            Subscribe capturedSubscribeRequest = subscribeRequestArgumentCaptor.getValue();
            assertThat(capturedSubscribeRequest.isSkipCloudSubscribe(), is(false));
            assertThat(capturedSubscribeRequest.getQos(), is(com.aws.greengrass.mqttclient.v5.QOS.AT_LEAST_ONCE));
        }
    }

    @Test
    void GIVEN_unknown_subscription_mode_WHEN_subscribe_THEN_defaults_to_cloud_path() throws Exception {
        SubscribeToIoTCoreRequest subscribeToIoTCoreRequest = new SubscribeToIoTCoreRequest();
        subscribeToIoTCoreRequest.setTopicName(TEST_TOPIC);
        subscribeToIoTCoreRequest.setQos(QOS.AT_LEAST_ONCE);
        // An unrecognized mode string takes the SUBSCRIBE (cloud) path.
        subscribeToIoTCoreRequest.setSubscriptionMode("BOGUS");

        when(authorizationHandler.isAuthorized(any(), any(), any())).thenReturn(true);
        ArgumentCaptor<Subscribe> subscribeRequestArgumentCaptor = ArgumentCaptor.forClass(Subscribe.class);

        try (MqttProxyIPCAgent.SubscribeToIoTCoreOperationHandler subscribeToIoTCoreOperationHandler
                     = spy(mqttProxyIPCAgent.getSubscribeToIoTCoreOperationHandler(mockContext))) {
            subscribeToIoTCoreOperationHandler.handleRequestAsync(subscribeToIoTCoreRequest).get(1, TimeUnit.SECONDS);

            verify(mqttClient).subscribe(subscribeRequestArgumentCaptor.capture());
            assertThat(subscribeRequestArgumentCaptor.getValue().isSkipCloudSubscribe(), is(false));
        }
    }

    @Test
    void GIVEN_receive_only_and_unauthorized_WHEN_subscribe_THEN_unauthorized_error() throws Exception {
        SubscribeToIoTCoreRequest subscribeToIoTCoreRequest = new SubscribeToIoTCoreRequest();
        subscribeToIoTCoreRequest.setTopicName(TEST_TOPIC);
        subscribeToIoTCoreRequest.setSubscriptionMode(SubscriptionMode.RECEIVE_ONLY);

        when(authorizationHandler.isAuthorized(any(), any(), any())).thenReturn(false);

        try (MqttProxyIPCAgent.SubscribeToIoTCoreOperationHandler subscribeToIoTCoreOperationHandler
                     = mqttProxyIPCAgent.getSubscribeToIoTCoreOperationHandler(mockContext)) {
            // An unauthorized RECEIVE_ONLY subscribe fails with UnauthorizedError.
            assertThrows(UnauthorizedError.class, () ->
                    subscribeToIoTCoreOperationHandler.handleRequestAsync(subscribeToIoTCoreRequest)
                            .get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void GIVEN_receive_only_and_mqtt_client_rejects_WHEN_subscribe_THEN_service_error(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, MqttRequestException.class);
        SubscribeToIoTCoreRequest subscribeToIoTCoreRequest = new SubscribeToIoTCoreRequest();
        subscribeToIoTCoreRequest.setTopicName(TEST_TOPIC);
        subscribeToIoTCoreRequest.setSubscriptionMode(SubscriptionMode.RECEIVE_ONLY);

        when(authorizationHandler.isAuthorized(any(), any(), any())).thenReturn(true);
        // MqttClient.subscribe throws MqttRequestException; the handler surfaces it as a ServiceError.
        when(mqttClient.subscribe(any(Subscribe.class))).thenThrow(
                new MqttRequestException("Unable to register subscription for topic: " + TEST_TOPIC));

        try (MqttProxyIPCAgent.SubscribeToIoTCoreOperationHandler subscribeToIoTCoreOperationHandler
                     = mqttProxyIPCAgent.getSubscribeToIoTCoreOperationHandler(mockContext)) {
            ServiceError e = assertThrows(ServiceError.class, () ->
                    subscribeToIoTCoreOperationHandler.handleRequestAsync(subscribeToIoTCoreRequest)
                            .get(1, TimeUnit.SECONDS));
            assertThat(e.getMessage(), containsString(TEST_TOPIC));
        }
    }

    @Test
    void GIVEN_MqttProxyIPCAgent_WHEN_publish_with_invalid_qos_THEN_error_thrown() throws Exception {
        PublishToIoTCoreRequest publishToIoTCoreRequest = new PublishToIoTCoreRequest();
        publishToIoTCoreRequest.setPayload(TEST_PAYLOAD);
        publishToIoTCoreRequest.setTopicName(TEST_TOPIC);
        publishToIoTCoreRequest.setQos("10");

        when(authorizationHandler.isAuthorized(any(), any(), any())).thenReturn(true);

        try (MqttProxyIPCAgent.PublishToIoTCoreOperationHandler publishToIoTCoreOperationHandler
                     = mqttProxyIPCAgent.getPublishToIoTCoreOperationHandler(mockContext)) {
            assertThrows(InvalidArgumentsError.class, () -> {
                publishToIoTCoreOperationHandler.handleRequest(publishToIoTCoreRequest);
            });
        }
    }

    @Test
    void GIVEN_MqttProxyIPCAgent_WHEN_publish_with_no_qos_THEN_error_thrown() throws Exception {
        PublishToIoTCoreRequest publishToIoTCoreRequest = new PublishToIoTCoreRequest();
        publishToIoTCoreRequest.setPayload(TEST_PAYLOAD);
        publishToIoTCoreRequest.setTopicName(TEST_TOPIC);

        when(authorizationHandler.isAuthorized(any(), any(), any())).thenReturn(true);

        try (MqttProxyIPCAgent.PublishToIoTCoreOperationHandler publishToIoTCoreOperationHandler
                     = mqttProxyIPCAgent.getPublishToIoTCoreOperationHandler(mockContext)) {
            assertThrows(InvalidArgumentsError.class, () -> {
                publishToIoTCoreOperationHandler.handleRequest(publishToIoTCoreRequest);
            });
        }
    }

    @Test
    void GIVEN_MqttProxyIPCAgent_WHEN_publish_with_no_topic_THEN_error_thrown() throws Exception {
        PublishToIoTCoreRequest publishToIoTCoreRequest = new PublishToIoTCoreRequest();
        publishToIoTCoreRequest.setPayload(TEST_PAYLOAD);
        publishToIoTCoreRequest.setQos(QOS.AT_LEAST_ONCE);

        try (MqttProxyIPCAgent.PublishToIoTCoreOperationHandler publishToIoTCoreOperationHandler
                     = mqttProxyIPCAgent.getPublishToIoTCoreOperationHandler(mockContext)) {
            assertThrows(InvalidArgumentsError.class, () -> {
                publishToIoTCoreOperationHandler.handleRequest(publishToIoTCoreRequest);
            });
        }
    }

    @Test
    void GIVEN_MqttProxyIPCAgent_WHEN_publish_with_no_payload_THEN_error_thrown() throws Exception {
        PublishToIoTCoreRequest publishToIoTCoreRequest = new PublishToIoTCoreRequest();
        publishToIoTCoreRequest.setTopicName(TEST_TOPIC);
        publishToIoTCoreRequest.setQos(QOS.AT_LEAST_ONCE);

        when(authorizationHandler.isAuthorized(any(), any(), any())).thenReturn(true);

        try (MqttProxyIPCAgent.PublishToIoTCoreOperationHandler publishToIoTCoreOperationHandler
                     = mqttProxyIPCAgent.getPublishToIoTCoreOperationHandler(mockContext)) {
            assertThrows(InvalidArgumentsError.class, () -> {
                publishToIoTCoreOperationHandler.handleRequest(publishToIoTCoreRequest);
            });
        }
    }

    @Test
    void GIVEN_MqttProxyIPCAgent_WHEN_subscribe_with_invalid_qos_THEN_error_thrown() throws Exception {
        SubscribeToIoTCoreRequest subscribeToIoTCoreRequest = new SubscribeToIoTCoreRequest();
        subscribeToIoTCoreRequest.setTopicName(TEST_TOPIC);
        subscribeToIoTCoreRequest.setQos("10");

        when(authorizationHandler.isAuthorized(any(), any(), any())).thenReturn(true);

        try (MqttProxyIPCAgent.SubscribeToIoTCoreOperationHandler subscribeToIoTCoreOperationHandler
                     = mqttProxyIPCAgent.getSubscribeToIoTCoreOperationHandler(mockContext)) {
            assertThrows(InvalidArgumentsError.class, () -> {
                subscribeToIoTCoreOperationHandler.handleRequestAsync(subscribeToIoTCoreRequest).get(1, TimeUnit.SECONDS);
            });
        }
    }

    @Test
    void GIVEN_MqttProxyIPCAgent_WHEN_subscribe_with_no_qos_THEN_error_thrown() throws Exception {
        SubscribeToIoTCoreRequest subscribeToIoTCoreRequest = new SubscribeToIoTCoreRequest();
        subscribeToIoTCoreRequest.setTopicName(TEST_TOPIC);

        when(authorizationHandler.isAuthorized(any(), any(), any())).thenReturn(true);

        try (MqttProxyIPCAgent.SubscribeToIoTCoreOperationHandler subscribeToIoTCoreOperationHandler
                     = mqttProxyIPCAgent.getSubscribeToIoTCoreOperationHandler(mockContext)) {
            assertThrows(InvalidArgumentsError.class, () -> {
                subscribeToIoTCoreOperationHandler.handleRequestAsync(subscribeToIoTCoreRequest).get(1, TimeUnit.SECONDS);
            });
        }
    }

    @Test
    void GIVEN_MqttProxyIPCAgent_WHEN_subscribe_with_no_topic_THEN_error_thrown() throws Exception {
        SubscribeToIoTCoreRequest subscribeToIoTCoreRequest = new SubscribeToIoTCoreRequest();
        subscribeToIoTCoreRequest.setQos(QOS.AT_LEAST_ONCE);

        try (MqttProxyIPCAgent.SubscribeToIoTCoreOperationHandler subscribeToIoTCoreOperationHandler
                     = mqttProxyIPCAgent.getSubscribeToIoTCoreOperationHandler(mockContext)) {
            assertThrows(InvalidArgumentsError.class, () -> {
                subscribeToIoTCoreOperationHandler.handleRequestAsync(subscribeToIoTCoreRequest).get(1, TimeUnit.SECONDS);
            });
        }
    }
}
