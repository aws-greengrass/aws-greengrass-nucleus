/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.mqttproxy;

import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.PublishRequest;
import com.aws.greengrass.mqttclient.SubscribeRequest;
import com.aws.greengrass.mqttclient.UnsubscribeRequest;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCService;
import software.amazon.awssdk.aws.greengrass.model.IoTCoreMessage;
import software.amazon.awssdk.aws.greengrass.model.MQTTMessage;
import software.amazon.awssdk.aws.greengrass.model.PublishToIoTCoreRequest;
import software.amazon.awssdk.aws.greengrass.model.PublishToIoTCoreResponse;
import software.amazon.awssdk.aws.greengrass.model.QOS;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToIoTCoreRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToIoTCoreResponse;
import software.amazon.awssdk.crt.eventstream.ServerConnectionContinuation;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.eventstreamrpc.AuthenticationData;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.aws.greengrass.authorization.AuthorizationHandler.ANY_REGEX;
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
public class MqttProxyIPCAgentTest {
    private static final String TEST_SERVICE = "TestService";
    private static final String TEST_TOPIC = "TestTopic";
    private static final byte[] TEST_PAYLOAD = "TestPayload".getBytes(StandardCharsets.UTF_8);

    private static final String TEST_SINGLE_LEVEL_WILDCARD = "topic/with/single/level/+/wildcard";
    private static final String TEST_MULTI_LEVEL_WILDCARD = "topic/with/multi/level/wildcard/#";
    private static final List<String> TEST_SINGLE_LEVEL_AUTHORIZED = Arrays.asList(
            "topic/with/single/level/+/wildcard",
            "topic/with/single/level/abc/wildcard");
    private static final List<String> TEST_MULTI_LEVEL_AUTHORIZED = Arrays.asList(
            "topic/with/multi/level/wildcard/#",
            "topic/with/multi/level/wildcard/+/abc",
            "topic/with/multi/level/wildcard/abc/#",
            "topic/with/multi/level/wildcard/abc",
            "topic/with/multi/level/wildcard/abc/xyz");

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
    void setup() {
        lenient().when(mockContext.getContinuation()).thenReturn(mock(ServerConnectionContinuation.class));
        lenient().when(mockContext.getAuthenticationData()).thenReturn(mockAuthenticationData);
        lenient().when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_SERVICE);
        mqttProxyIPCAgent = new MqttProxyIPCAgent();
        mqttProxyIPCAgent.setMqttClient(mqttClient);
        mqttProxyIPCAgent.setAuthorizationHandler(authorizationHandler);
    }

    @Test
    void GIVEN_MqttProxyIPCAgent_WHEN_publish_on_topic_THEN_message_published() throws Exception {
        PublishToIoTCoreRequest publishToIoTCoreRequest = new PublishToIoTCoreRequest();
        publishToIoTCoreRequest.setPayload(TEST_PAYLOAD);
        publishToIoTCoreRequest.setTopicName(TEST_TOPIC);
        publishToIoTCoreRequest.setRetain(true);
        publishToIoTCoreRequest.setQos(QOS.AT_LEAST_ONCE);

        CompletableFuture<Integer> completableFuture = new CompletableFuture<>();
        completableFuture.complete(0);
        when(mqttClient.publish(any())).thenReturn(completableFuture);
        when(authorizationHandler.getAuthorizedResources(any(), any(), any()))
                .thenReturn(Collections.singletonList(TEST_TOPIC));
        ArgumentCaptor<PublishRequest> publishRequestArgumentCaptor = ArgumentCaptor.forClass(PublishRequest.class);

        try (MqttProxyIPCAgent.PublishToIoTCoreOperationHandler publishToIoTCoreOperationHandler
                     = mqttProxyIPCAgent.getPublishToIoTCoreOperationHandler(mockContext)) {
            PublishToIoTCoreResponse publishToIoTCoreResponse
                    = publishToIoTCoreOperationHandler.handleRequest(publishToIoTCoreRequest);

            assertNotNull(publishToIoTCoreResponse);
            verify(authorizationHandler).getAuthorizedResources(MQTT_PROXY_SERVICE_NAME, TEST_SERVICE,
                    GreengrassCoreIPCService.PUBLISH_TO_IOT_CORE);

            verify(mqttClient).publish(publishRequestArgumentCaptor.capture());
            PublishRequest capturedPublishRequest = publishRequestArgumentCaptor.getValue();
            assertThat(capturedPublishRequest.getPayload(), is(TEST_PAYLOAD));
            assertThat(capturedPublishRequest.getTopic(), is(TEST_TOPIC));
            assertThat(capturedPublishRequest.isRetain(), is(true));
            assertThat(capturedPublishRequest.getQos(), is(QualityOfService.AT_LEAST_ONCE));
        }
    }

    @Test
    void GIVEN_MqttProxyIPCAgent_WHEN_subscribe_to_topic_THEN_topic_subscribed() throws Exception {
        SubscribeToIoTCoreRequest subscribeToIoTCoreRequest = new SubscribeToIoTCoreRequest();
        subscribeToIoTCoreRequest.setTopicName(TEST_TOPIC);
        subscribeToIoTCoreRequest.setQos(QOS.AT_LEAST_ONCE);

        when(authorizationHandler.getAuthorizedResources(any(), any(), any()))
                .thenReturn(Collections.singletonList(TEST_TOPIC));
        ArgumentCaptor<SubscribeRequest> subscribeRequestArgumentCaptor
                = ArgumentCaptor.forClass(SubscribeRequest.class);
        ArgumentCaptor<UnsubscribeRequest> unsubscribeRequestArgumentCaptor
                = ArgumentCaptor.forClass(UnsubscribeRequest.class);
        ArgumentCaptor<IoTCoreMessage> ioTCoreMessageArgumentCaptor = ArgumentCaptor.forClass(IoTCoreMessage.class);

        try (MqttProxyIPCAgent.SubscribeToIoTCoreOperationHandler subscribeToIoTCoreOperationHandler
                     = spy(mqttProxyIPCAgent.getSubscribeToIoTCoreOperationHandler(mockContext))) {
            SubscribeToIoTCoreResponse subscribeToIoTCoreResponse
                    = subscribeToIoTCoreOperationHandler.handleRequest(subscribeToIoTCoreRequest);

            assertNotNull(subscribeToIoTCoreResponse);
            verify(authorizationHandler).getAuthorizedResources(MQTT_PROXY_SERVICE_NAME, TEST_SERVICE,
                    GreengrassCoreIPCService.SUBSCRIBE_TO_IOT_CORE);

            verify(mqttClient).subscribe(subscribeRequestArgumentCaptor.capture());
            SubscribeRequest capturedSubscribeRequest = subscribeRequestArgumentCaptor.getValue();
            assertThat(capturedSubscribeRequest.getTopic(), is(TEST_TOPIC));
            assertThat(capturedSubscribeRequest.getQos(), is(QualityOfService.AT_LEAST_ONCE));

            Consumer<MqttMessage> callback = capturedSubscribeRequest.getCallback();
            MqttMessage message = new MqttMessage(TEST_TOPIC, TEST_PAYLOAD);
            doReturn(new CompletableFuture<>()).when(subscribeToIoTCoreOperationHandler).sendStreamEvent(any());
            callback.accept(message);
            verify(subscribeToIoTCoreOperationHandler).sendStreamEvent(ioTCoreMessageArgumentCaptor.capture());
            MQTTMessage mqttMessage = ioTCoreMessageArgumentCaptor.getValue().getMessage();
            assertThat(mqttMessage.getPayload(), is(TEST_PAYLOAD));
            assertThat(mqttMessage.getTopicName(), is(TEST_TOPIC));

            subscribeToIoTCoreOperationHandler.onStreamClosed();
            verify(mqttClient).unsubscribe(unsubscribeRequestArgumentCaptor.capture());
            UnsubscribeRequest capturedUnsubscribedRequest = unsubscribeRequestArgumentCaptor.getValue();
            assertThat(capturedUnsubscribedRequest.getTopic(), is(TEST_TOPIC));
            assertThat(capturedUnsubscribedRequest.getCallback(), is(callback));
        }
    }

    @Test
    void GIVEN_wildcard_resources_WHEN_doAuthorization_THEN_authorized() throws Exception {
        when(authorizationHandler.getAuthorizedResources(any(), any(), any()))
                .thenReturn(Arrays.asList(TEST_SINGLE_LEVEL_WILDCARD, TEST_MULTI_LEVEL_WILDCARD));

        for (String topic : TEST_SINGLE_LEVEL_AUTHORIZED) {
            mqttProxyIPCAgent.doAuthorization(GreengrassCoreIPCService.SUBSCRIBE_TO_IOT_CORE, TEST_SERVICE, topic);
        }
        for (String topic : TEST_MULTI_LEVEL_AUTHORIZED) {
            mqttProxyIPCAgent.doAuthorization(GreengrassCoreIPCService.SUBSCRIBE_TO_IOT_CORE, TEST_SERVICE, topic);
        }
    }

    @Test
    void GIVEN_wildcard_resources_WHEN_doAuthorization_with_unauthorized_topic_THEN_not_authorized() throws Exception {
        when(authorizationHandler.getAuthorizedResources(any(), any(), any()))
                .thenReturn(Arrays.asList(TEST_SINGLE_LEVEL_WILDCARD, TEST_MULTI_LEVEL_WILDCARD));

        assertThrows(AuthorizationException.class, () -> mqttProxyIPCAgent.doAuthorization(
                GreengrassCoreIPCService.SUBSCRIBE_TO_IOT_CORE, TEST_SERVICE, TEST_TOPIC));
    }

    @Test
    void GIVEN_star_resource_WHEN_doAuthorization_THEN_authorized() throws Exception {
        when(authorizationHandler.getAuthorizedResources(any(), any(), any())).thenReturn(Arrays.asList(ANY_REGEX));

        mqttProxyIPCAgent.doAuthorization(GreengrassCoreIPCService.SUBSCRIBE_TO_IOT_CORE, TEST_SERVICE, TEST_TOPIC);
    }
}
