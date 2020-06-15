/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.mqtt;

import com.aws.iot.evergreen.config.ChildChanged;
import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.config.WhatHappened;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.deployment.DeviceConfiguration;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.util.Pair;
import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static com.aws.iot.evergreen.deployment.DeviceConfiguration.DEVICE_MQTT_NAMESPACE;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static com.aws.iot.evergreen.testcommons.testutilities.TestUtils.asyncAssertOnConsumer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.either;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({EGExtension.class, MockitoExtension.class})
@SuppressWarnings("PMD.CloseResource")
class MqttClientTest {
    @Mock
    AwsIotMqttConnectionBuilder builder;

    @Mock
    DeviceConfiguration deviceConfiguration;

    @Mock
    MqttClientConnection mockConnection;

    ExecutorService executorService = Executors.newSingleThreadExecutor();

    Configuration config = new Configuration(new Context());
    private final Consumer<MqttMessage> cb = (m) -> {
    };

    @BeforeEach
    void beforeEach() {
        when(deviceConfiguration.getMQTTNamespace()).thenReturn(config.lookupTopics("data"));
        lenient().when(builder.build()).thenReturn(mockConnection);
        lenient().when(mockConnection.connect()).thenReturn(CompletableFuture.completedFuture(false));
        lenient().when(mockConnection.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        lenient().when(mockConnection.unsubscribe(any())).thenReturn(CompletableFuture.completedFuture(0));
        lenient().when(mockConnection.publish(any(), any(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(0));
    }

    @AfterEach
    void afterEach() throws IOException {
        config.context.close();
        executorService.shutdown();
    }

    @SneakyThrows
    @Test
    void GIVEN_multiple_subset_subscriptions_WHEN_subscribe_or_unsubscribe_THEN_only_subscribes_and_unsubscribes_once()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = new MqttClient(deviceConfiguration, (c) -> builder, executorService);
        assertFalse(client.connected());

        client.subscribe(SubscribeRequest.builder().topic("A/B/+").callback(cb).build());

        verify(mockConnection).connect();
        verify(mockConnection).subscribe(eq("A/B/+"), eq(QualityOfService.AT_LEAST_ONCE));

        // This subscription shouldn't actually subscribe through the cloud because it is a subset of the previous sub
        client.subscribe(SubscribeRequest.builder().topic("A/B/C").callback(cb).build());
        verify(mockConnection, times(0)).subscribe(eq("A/B/C"), eq(QualityOfService.AT_LEAST_ONCE));

        // Even though we unsub locally, it should keep the cloud sub because a different on-device client needs it
        client.unsubscribe(UnsubscribeRequest.builder().topic("A/B/+").callback(cb).build());
        verify(mockConnection, times(0)).unsubscribe(any());

        // Now that we've unsubbed on device it can unsub from the cloud
        client.unsubscribe(UnsubscribeRequest.builder().topic("A/B/C").callback(cb).build());
        verify(mockConnection, times(1)).unsubscribe(eq("A/B/+"));
    }

    @Test
    void GIVEN_connection_WHEN_settings_change_THEN_reconnects()
            throws ExecutionException, InterruptedException, TimeoutException {
        ArgumentCaptor<ChildChanged> cc = ArgumentCaptor.forClass(ChildChanged.class);
        doNothing().when(deviceConfiguration).onAnyChange(cc.capture());
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, executorService));

        IndividualMqttClient iClient1 = mock(IndividualMqttClient.class);
        when(client.getNewMqttClient()).thenReturn(iClient1);

        client.subscribe(SubscribeRequest.builder().topic("A/B/+").callback(cb).build());

        cc.getValue().childChanged(WhatHappened.childChanged, config.lookupTopics(DEVICE_MQTT_NAMESPACE));
        verify(iClient1, timeout(500)).reconnect();

        client.close();
        verify(iClient1).close();
    }

    @Test
    void GIVEN_connection_has_50_subscriptions_THEN_new_connection_added_as_needed()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, executorService));
        IndividualMqttClient iClient1 = mock(IndividualMqttClient.class);
        IndividualMqttClient iClient2 = mock(IndividualMqttClient.class);
        when(client.getNewMqttClient()).thenReturn(iClient1).thenReturn(iClient2);
        when(iClient1.canAddNewSubscription()).thenReturn(false);

        // Have the MQTT client load the client with 50 subscriptions
        client.subscribe(SubscribeRequest.builder().topic("A").callback(cb).build());
        client.subscribe(SubscribeRequest.builder().topic("B").callback(cb).build());

        verify(client, times(3)).getNewMqttClient();
    }

    @Test
    void GIVEN_connection_has_0_subscriptions_THEN_all_but_last_connection_will_be_closed()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, executorService));
        IndividualMqttClient iClient1 = mock(IndividualMqttClient.class);
        IndividualMqttClient iClient2 = mock(IndividualMqttClient.class);
        when(client.getNewMqttClient()).thenReturn(iClient1).thenReturn(iClient2);
        when(iClient1.canAddNewSubscription()).thenReturn(false);
        when(iClient1.subscriptionCount()).thenReturn(1);
        when(iClient2.subscriptionCount()).thenReturn(0);

        client.subscribe(SubscribeRequest.builder().topic("A").callback(cb).build());
        client.subscribe(SubscribeRequest.builder().topic("B").callback(cb).build());
        client.publish(PublishRequest.builder().topic("A").payload(new byte[0]).build());

        verify(client, times(3)).getNewMqttClient();
        // Only 1 client is closed
        verify(iClient1, times(0)).close();
        verify(iClient2, times(1)).close();
    }

    @Test
    void GIVEN_mqttclient_WHEN_publish_THEN_message_published()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = new MqttClient(deviceConfiguration, (c) -> builder, executorService);
        assertFalse(client.connected());

        client.publish(PublishRequest.builder().topic("A/B").payload(ByteBuffer.allocate(1024).array()).build());
        verify(mockConnection).connect();

        ArgumentCaptor<MqttMessage> messageCaptor = ArgumentCaptor.forClass(MqttMessage.class);
        verify(mockConnection).publish(messageCaptor.capture(), eq(QualityOfService.AT_LEAST_ONCE), eq(false));

        assertEquals("A/B", messageCaptor.getValue().getTopic());
        assertEquals(1024, messageCaptor.getValue().getPayload().length);
    }

    @Test
    void GIVEN_incoming_message_WHEN_received_THEN_subscribers_are_called()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, executorService));
        IndividualMqttClient mockIndividual = mock(IndividualMqttClient.class);
        when(client.getNewMqttClient()).thenReturn(mockIndividual);
        assertFalse(client.connected());

        // Subscribe with wildcard first so that that is the active cloud subscription.
        // Then subscribe to 2 other topics which are included in the wildcard.
        // Then show that each subscription here is called only for the topic that it
        // subscribed to.

        Pair<CompletableFuture<Void>, Consumer<MqttMessage>> abPlus = asyncAssertOnConsumer((m) -> {
            assertThat(m.getTopic(), either(is("A/B/C")).or(is("A/B/D")));
        }, 2);
        client.subscribe(SubscribeRequest.builder().topic("A/B/+").callback(abPlus.getRight()).build());
        Pair<CompletableFuture<Void>, Consumer<MqttMessage>> abc = asyncAssertOnConsumer((m) -> {
            assertEquals("A/B/C", m.getTopic());
        }, 2);
        client.subscribe(SubscribeRequest.builder().topic("A/B/C").callback(abc.getRight()).build());
        Pair<CompletableFuture<Void>, Consumer<MqttMessage>> abd = asyncAssertOnConsumer((m) -> {
            assertEquals("A/B/D", m.getTopic());
        });
        client.subscribe(SubscribeRequest.builder().topic("A/B/D").callback(abd.getRight()).build());

        Consumer<MqttMessage> handler = client.getMessageHandlerForClient(mockIndividual);

        handler.accept(new MqttMessage("A/B/C", new byte[0]));
        handler.accept(new MqttMessage("A/B/D", new byte[0]));
        handler.accept(new MqttMessage("A/X/Y", new byte[0])); // No subscribers for this one

        abPlus.getLeft().get(0, TimeUnit.SECONDS);
        abd.getLeft().get(0, TimeUnit.SECONDS);

        // Ensure, that even after removing the wildcard subscription, the other topics still get
        // messages
        client.unsubscribe(UnsubscribeRequest.builder().topic("A/B/+").callback(abPlus.getRight()).build());
        handler.accept(new MqttMessage("A/B/C", new byte[0]));
        abc.getLeft().get(0, TimeUnit.SECONDS);
    }

    @Test
    void GIVEN_incoming_messages_to_2clients_WHEN_received_THEN_subscribers_are_called_without_duplication()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, executorService));
        assertFalse(client.connected());
        IndividualMqttClient mockIndividual1 = mock(IndividualMqttClient.class);
        IndividualMqttClient mockIndividual2 = mock(IndividualMqttClient.class);
        when(client.getNewMqttClient()).thenReturn(mockIndividual1).thenReturn(mockIndividual2);
        when(mockIndividual1.canAddNewSubscription()).thenReturn(true).thenReturn(false);

        Pair<CompletableFuture<Void>, Consumer<MqttMessage>> abc = asyncAssertOnConsumer((m) -> {
            assertEquals("A/B/C", m.getTopic());
        }, 1);
        client.subscribe(SubscribeRequest.builder().topic("A/B/C").callback(abc.getRight()).build());
        Pair<CompletableFuture<Void>, Consumer<MqttMessage>> abd = asyncAssertOnConsumer((m) -> {
            assertEquals("A/B/D", m.getTopic());
        }, 1);
        client.subscribe(SubscribeRequest.builder().topic("A/B/D").callback(abd.getRight()).build());
        Pair<CompletableFuture<Void>, Consumer<MqttMessage>> abPlus = asyncAssertOnConsumer((m) -> {
            assertThat(m.getTopic(), either(is("A/B/C")).or(is("A/B/D")).or(is("A/B/F")));
        }, 3);
        client.subscribe(SubscribeRequest.builder().topic("A/B/+").callback(abPlus.getRight()).build());

        Consumer<MqttMessage> handler1 = client.getMessageHandlerForClient(mockIndividual1);
        Consumer<MqttMessage> handler2 = client.getMessageHandlerForClient(mockIndividual2);

        // Send messages to BOTH handler1 and handler2 to show that we appropriately route and don't duplicate
        // messages when multiple overlapping subscriptions exist across individual clients

        // Send all to handler1
        handler1.accept(new MqttMessage("A/B/C", new byte[0]));
        handler1.accept(new MqttMessage("A/B/D", new byte[0]));
        handler1.accept(new MqttMessage("A/B/F", new byte[0]));
        handler1.accept(new MqttMessage("A/X/Y", new byte[0]));

        // Send all the same messages to handler2
        handler2.accept(new MqttMessage("A/B/C", new byte[0]));
        handler2.accept(new MqttMessage("A/B/D", new byte[0]));
        handler2.accept(new MqttMessage("A/B/F", new byte[0]));
        handler2.accept(new MqttMessage("A/X/Y", new byte[0])); // No subscribers for this one

        abPlus.getLeft().get(0, TimeUnit.SECONDS);
        abd.getLeft().get(0, TimeUnit.SECONDS);
        abc.getLeft().get(0, TimeUnit.SECONDS);
    }

    @Test
    void GIVEN_incoming_message_WHEN_received_and_subscriber_throws_THEN_still_calls_remaining_subscriptions(
            ExtensionContext context)
            throws ExecutionException, InterruptedException, TimeoutException {
        ignoreExceptionWithMessage(context, "Uncaught!");
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, executorService));
        IndividualMqttClient mockIndividual = mock(IndividualMqttClient.class);
        when(client.getNewMqttClient()).thenReturn(mockIndividual);
        assertFalse(client.connected());

        client.subscribe(SubscribeRequest.builder().topic("A/B/+").callback((m) -> {
            throw new RuntimeException("Uncaught!");
        }).build());
        Pair<CompletableFuture<Void>, Consumer<MqttMessage>> abc = asyncAssertOnConsumer((m) -> {
            assertEquals("A/B/C", m.getTopic());
        });
        client.subscribe(SubscribeRequest.builder().topic("A/B/C").callback(abc.getRight()).build());

        Consumer<MqttMessage> handler = client.getMessageHandlerForClient(mockIndividual);

        handler.accept(new MqttMessage("A/B/C", new byte[0]));
        abc.getLeft().get(0, TimeUnit.SECONDS);
    }
}
