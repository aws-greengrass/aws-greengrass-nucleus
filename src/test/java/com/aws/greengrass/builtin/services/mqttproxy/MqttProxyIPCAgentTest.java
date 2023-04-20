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
import software.amazon.awssdk.crt.eventstream.ServerConnectionContinuation;
import software.amazon.awssdk.eventstreamrpc.AuthenticationData;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.aws.greengrass.ipc.modules.MqttProxyIPCService.MQTT_PROXY_SERVICE_NAME;
import static org.hamcrest.MatcherAssert.assertThat;
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

@ExtendWith({MockitoExtension.class, GGExtension.class})
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
