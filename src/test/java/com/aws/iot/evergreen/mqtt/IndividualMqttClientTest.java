/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.mqtt;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("PMD.CloseResource")
@ExtendWith({EGExtension.class, MockitoExtension.class})
class IndividualMqttClientTest {
    @Mock
    AwsIotMqttConnectionBuilder builder;

    @Mock
    MqttClientConnection connection;

    @Captor
    ArgumentCaptor<MqttClientConnectionEvents> events;

    @BeforeEach
    void beforeEach() {
        when(connection.connect()).thenReturn(CompletableFuture.completedFuture(false));
        when(connection.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(connection.disconnect()).thenReturn(CompletableFuture.completedFuture(null));
        when(builder.withConnectionEventCallbacks(events.capture())).thenReturn(builder);
    }

    @Test
    void GIVEN_individual_client_THEN_it_tracks_connection_state_correctly()
            throws ExecutionException, InterruptedException, TimeoutException {
        Topics mockTopic = mock(Topics.class);
        when(mockTopic.findOrDefault(any(), any())).thenReturn(1000);
        IndividualMqttClient client = new IndividualMqttClient(() -> builder, (x) -> null, "A", mockTopic);

        assertFalse(client.connected());

        when(builder.build()).thenReturn(connection);
        // Call subscribe which will cause the client to connect
        client.subscribe("A", QualityOfService.AT_LEAST_ONCE);

        assertTrue(client.connected());
        client.reconnect();
        verify(connection).close();
        verify(connection).disconnect();
        assertTrue(client.connected());

        // Ensure that we track connection state through the callbacks
        events.getValue().onConnectionInterrupted(0);
        assertFalse(client.connected());

        events.getValue().onConnectionResumed(true);
        assertTrue(client.connected());

        client.close();
        assertFalse(client.connected());
        verify(connection, times(2)).disconnect();
        verify(connection, times(2)).close();
    }
}
