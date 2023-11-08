/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import com.aws.greengrass.componentmanager.KernelConfigResolver;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.crt.mqtt5.Mqtt5ClientOptions;
import software.amazon.awssdk.crt.mqtt5.OnDisconnectionReturn;
import software.amazon.awssdk.iot.AwsIotMqtt5ClientBuilder;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;

import static com.github.grantwest.eventually.EventuallyLambdaMatcher.eventuallyEval;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("PMD.CloseResource")
@ExtendWith({GGExtension.class, MockitoExtension.class})
class AwsIotMqtt5ClientTest {
    @Mock
    AwsIotMqtt5ClientBuilder builder;

    @Mock
    MqttClientConnectionEvents mockCallback1;

    @Mock
    MqttClientConnectionEvents mockCallback2;

    @Spy
    CallbackEventManager callbackEventManager;

    Topics topics = Topics.of(new Context(), KernelConfigResolver.CONFIGURATION_CONFIG_KEY, null);

    ExecutorService executorService = Executors.newFixedThreadPool(1);

    @Mock
    ScheduledExecutorService ses;

    @BeforeEach
    void beforeEach() {
        callbackEventManager.addToCallbackEvents(mockCallback1);
        callbackEventManager.addToCallbackEvents(mockCallback2);
    }

    @AfterEach
    void tearDown() throws IOException {
        executorService.shutdownNow();
        topics.getContext().close();
    }

    @Test
    void GIVEN_multiple_callbacks_in_callbackEventManager_WHEN_connections_are_interrupted_purposely_THEN_no_callbacks_are_called() {
        AwsIotMqtt5Client client1 = new AwsIotMqtt5Client(() -> builder, (x) -> null, "A", 0, topics,
                callbackEventManager, executorService, ses);
        client1.disableRateLimiting();
        AwsIotMqtt5Client client2 = new AwsIotMqtt5Client(() -> builder, (x) -> null, "B", 0, topics,
                callbackEventManager, executorService, ses);
        client2.disableRateLimiting();
        callbackEventManager.runOnConnectionResumed(false);
        assertTrue(callbackEventManager.hasCallbacked());
        int errorCode = 5153;

        OnDisconnectionReturn disconnectEvent = mock(OnDisconnectionReturn.class);
        when(disconnectEvent.getErrorCode()).thenReturn(errorCode);
        client1.getConnectionEventCallback().onDisconnection(null, disconnectEvent);
        verify(callbackEventManager, never()).runOnConnectionInterrupted(anyInt());
        verify(mockCallback1, never()).onConnectionInterrupted(anyInt());
        verify(mockCallback2, never()).onConnectionInterrupted(anyInt());

        client2.getConnectionEventCallback().onDisconnection(null, disconnectEvent);
        verify(callbackEventManager, never()).runOnConnectionInterrupted(anyInt());
        verify(mockCallback1, never()).onConnectionInterrupted(anyInt());
        verify(mockCallback2, never()).onConnectionInterrupted(anyInt());

        assertTrue(callbackEventManager.hasCallbacked());
    }

    @Test
    void GIVEN_connected_client_WHEN_reconnect_THEN_client_configured_to_resume_session() {
        try (AwsIotMqtt5ClientBuilder builder = AwsIotMqtt5ClientBuilder.newMqttBuilder("localhost");
             AwsIotMqtt5Client client = new AwsIotMqtt5Client(() -> builder, (x) -> null, "A", 0, topics,
                     callbackEventManager, executorService, ses)) {
            // running async because reconnect will never complete,
            // but it will trigger the code path that sets client session behavior
            executorService.submit(() -> {
                try {
                    client.reconnect(0L);
                } catch (TimeoutException | ExecutionException | InterruptedException ignore) {
                }
            });
            assertThat("session behavior is rejoin always", () -> client.getClient().getClientOptions().getSessionBehavior(),
                    eventuallyEval(is(Mqtt5ClientOptions.ClientSessionBehavior.REJOIN_ALWAYS)));
        }
    }
}
