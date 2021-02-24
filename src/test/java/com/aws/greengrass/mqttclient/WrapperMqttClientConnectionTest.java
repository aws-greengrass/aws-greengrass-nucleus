/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class WrapperMqttClientConnectionTest {

    @Mock
    MqttClient mockMqttClient;
    @Captor
    ArgumentCaptor<SubscribeRequest> subRequestCaptor;
    @Captor
    ArgumentCaptor<PublishRequest> pubRequestCaptor;
    @Captor
    ArgumentCaptor<UnsubscribeRequest> unsubRequestCaptor;

    private static final String TEST_TOPIC = "testTopic";

    @BeforeEach
    void beforeEach() throws InterruptedException, ExecutionException, TimeoutException {
        lenient().doNothing().when(mockMqttClient).subscribe(subRequestCaptor.capture());
        lenient().doNothing().when(mockMqttClient).unsubscribe(unsubRequestCaptor.capture());
        lenient().when(mockMqttClient.publish(pubRequestCaptor.capture()))
                .thenReturn(CompletableFuture.completedFuture(0));
    }

    @Test
    void GIVEN_wrapper_connection_WHEN_do_unsupported_operation_THEN_exception_thrown() {
        try (WrapperMqttClientConnection wrapperConnection = new WrapperMqttClientConnection(mockMqttClient)) {
            assertThrows(UnsupportedOperationException.class, wrapperConnection::connect);
            assertThrows(UnsupportedOperationException.class, wrapperConnection::disconnect);
            assertThrows(UnsupportedOperationException.class, () -> wrapperConnection.onMessage((message) -> {}));
            assertThrows(UnsupportedOperationException.class,
                    () -> wrapperConnection.subscribe(TEST_TOPIC, QualityOfService.AT_MOST_ONCE));
        }
    }

    @Test
    void GIVEN_wrapper_connection_WHEN_send_requests_THEN_delegated_to_mqtt_client()
            throws ExecutionException, InterruptedException {
        try (WrapperMqttClientConnection wrapperConnection = new WrapperMqttClientConnection(mockMqttClient)) {
            wrapperConnection.subscribe(TEST_TOPIC, QualityOfService.AT_MOST_ONCE, (m) -> {
            }).get();
            SubscribeRequest subRequest = subRequestCaptor.getValue();
            assertEquals(TEST_TOPIC, subRequest.getTopic());
            assertEquals(QualityOfService.AT_MOST_ONCE, subRequest.getQos());

            wrapperConnection.publish(new MqttMessage(TEST_TOPIC, new byte[0]), QualityOfService.AT_MOST_ONCE, false).get();
            PublishRequest pubRequest = pubRequestCaptor.getValue();
            assertEquals(TEST_TOPIC, pubRequest.getTopic());
            assertEquals(QualityOfService.AT_MOST_ONCE, pubRequest.getQos());

            wrapperConnection.unsubscribe(TEST_TOPIC);
            UnsubscribeRequest unsubRequest = unsubRequestCaptor.getValue();
            assertEquals(TEST_TOPIC, unsubRequest.getTopic());

            // should throw exception if unsubscribe an unknown topic
            assertThrows(ExecutionException.class, () -> wrapperConnection.unsubscribe("unknown").get());
        }
    }

}