/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import com.aws.greengrass.config.ChildChanged;
import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.mqttclient.spool.Spool;
import com.aws.greengrass.mqttclient.spool.SpoolerConfig;
import com.aws.greengrass.mqttclient.spool.SpoolerStoreException;
import com.aws.greengrass.mqttclient.spool.SpoolerStorageType;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Pair;
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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_MQTT_NAMESPACE;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static com.aws.greengrass.testcommons.testutilities.TestUtils.asyncAssertOnConsumer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.either;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
@SuppressWarnings("PMD.CloseResource")
class MqttClientTest {
    @Mock
    AwsIotMqttConnectionBuilder builder;

    @Mock
    DeviceConfiguration deviceConfiguration;

    @Mock
    MqttClientConnection mockConnection;

    @Mock
    Spool spool;

    ScheduledExecutorService ses = new ScheduledThreadPoolExecutor(1);

    Configuration config = new Configuration(new Context());
    private final Consumer<MqttMessage> cb = (m) -> {
    };

    @BeforeEach
    void beforeEach() {
        Topics mqttNamespace = config.lookupTopics("mqtt");
        Topics spoolerNamespace = config.lookupTopics("spooler");
        mqttNamespace.lookup(MqttClient.MQTT_OPERATION_TIMEOUT_KEY).withValue(0);
        when(deviceConfiguration.getMQTTNamespace()).thenReturn(mqttNamespace);
        lenient().when(deviceConfiguration.isDeviceConfiguredToTalkToCloud()).thenReturn(true);
        lenient().when(deviceConfiguration.getSpoolerNamespace()).thenReturn(spoolerNamespace);
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
        ses.shutdownNow();
    }

    @Test
    void GIVEN_multiple_subset_subscriptions_WHEN_subscribe_or_unsubscribe_THEN_only_subscribes_and_unsubscribes_once()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = new MqttClient(deviceConfiguration, (c) -> builder, ses);
        assertFalse(client.connected());

        client.subscribe(SubscribeRequest.builder().topic("A/B/+").callback(cb).build());

        verify(mockConnection, times(1)).connect();
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
    void GIVEN_connection_WHEN_subscribe_timesout_but_then_completes_THEN_subsequent_subscribe_calls_dont_call_cloud()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = new MqttClient(deviceConfiguration, (c) -> builder, ses);
        assertFalse(client.connected());
        CompletableFuture<Integer> cf = new CompletableFuture<>();
        lenient().when(mockConnection.subscribe(any(), any())).thenReturn(cf);

        assertThrows(TimeoutException.class,
                () -> client.subscribe(SubscribeRequest.builder().topic("A/B/+").callback(cb).build()));
        cf.complete(0);

        // This subscribe call won't result in a cloud call because the previous subscribe succeeded _after_
        // the timeout
        client.subscribe(SubscribeRequest.builder().topic("A/B/+").callback(cb).build());
        verify(mockConnection).connect();
        verify(mockConnection).subscribe(eq("A/B/+"), eq(QualityOfService.AT_LEAST_ONCE));
    }

    @Test
    void GIVEN_connection_WHEN_settings_change_THEN_reconnects()
            throws ExecutionException, InterruptedException, TimeoutException {
        ArgumentCaptor<ChildChanged> cc = ArgumentCaptor.forClass(ChildChanged.class);
        doNothing().when(deviceConfiguration).onAnyChange(cc.capture());
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, ses));

        AwsIotMqttClient iClient1 = mock(AwsIotMqttClient.class);
        when(iClient1.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(client.getNewMqttClient()).thenReturn(iClient1);

        client.subscribe(SubscribeRequest.builder().topic("A/B/+").callback(cb).build());

        cc.getValue().childChanged(WhatHappened.childChanged, config.lookupTopics(DEVICE_MQTT_NAMESPACE));
        verify(iClient1, timeout(5000)).reconnect();

        client.close();
        verify(iClient1).close();
    }

    @Test
    void GIVEN_connection_has_50_subscriptions_THEN_new_connection_added_as_needed()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, ses));
        AwsIotMqttClient iClient1 = mock(AwsIotMqttClient.class);
        AwsIotMqttClient iClient2 = mock(AwsIotMqttClient.class);
        when(iClient1.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(iClient2.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
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
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, ses));
        AwsIotMqttClient iClient1 = mock(AwsIotMqttClient.class);
        AwsIotMqttClient iClient2 = mock(AwsIotMqttClient.class);
        when(iClient1.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(iClient2.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(client.getNewMqttClient()).thenReturn(iClient1).thenReturn(iClient2);
        when(iClient1.canAddNewSubscription()).thenReturn(false);

        client.subscribe(SubscribeRequest.builder().topic("A").callback(cb).build());
        client.subscribe(SubscribeRequest.builder().topic("B").callback(cb).build());

        verify(client, times(3)).getNewMqttClient();
        verify(iClient1, times(0)).close();
        verify(iClient2, times(0)).close();
    }

    @Test
    void GIVEN_incoming_message_WHEN_received_THEN_subscribers_are_called()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, ses));
        AwsIotMqttClient mockIndividual = mock(AwsIotMqttClient.class);
        when(mockIndividual.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
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
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, ses));
        assertFalse(client.connected());
        AwsIotMqttClient mockIndividual1 = mock(AwsIotMqttClient.class);
        AwsIotMqttClient mockIndividual2 = mock(AwsIotMqttClient.class);
        when(mockIndividual1.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(mockIndividual2.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(client.getNewMqttClient()).thenReturn(mockIndividual1).thenReturn(mockIndividual2);
        when(mockIndividual1.canAddNewSubscription()).thenReturn(false);
        when(mockIndividual2.canAddNewSubscription()).thenReturn(true);
        when(mockIndividual1.subscriptionCount()).thenReturn(1);
        when(mockIndividual2.subscriptionCount()).thenReturn(1);

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
            ExtensionContext context) throws ExecutionException, InterruptedException, TimeoutException {
        ignoreExceptionWithMessage(context, "Uncaught!");
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, ses));
        AwsIotMqttClient mockIndividual = mock(AwsIotMqttClient.class);
        when(mockIndividual.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
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

    @Test
    void GIVEN_keep_qos_0_when_offline_is_false_and_mqtt_is_offline_WHEN_publish_THEN_future_complete_exceptionally()
            throws InterruptedException, SpoolerStoreException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, ses, false));
        PublishRequest request = PublishRequest.builder().topic("spool").payload(new byte[0])
                .qos(QualityOfService.AT_MOST_ONCE).build();
        SpoolerConfig config = SpoolerConfig.builder().keepQos0WhenOffline(false)
                .spoolSizeInBytes(25L).storageType(SpoolerStorageType.Memory)
                .build();
        when(spool.getSpoolConfig()).thenReturn(config);

        CompletableFuture<Integer> future = client.publish(request);

        assertTrue(future.isCompletedExceptionally());
        verify(spool, never()).addMessage(request);
    }

    @Test
    void GIVEN_keep_qos_0_when_offline_is_false_and_mqtt_is_online_WHEN_publish_THEN_return_future_complete()
            throws ExecutionException, InterruptedException, SpoolerStoreException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, ses, true));
        PublishRequest request = PublishRequest.builder().topic("spool").payload(new byte[0])
                .qos(QualityOfService.AT_MOST_ONCE).build();
        when(spool.addMessage(request)).thenReturn(0L);

        CompletableFuture<Integer> future = client.publish(request);

        assertEquals(0, future.get());
        verify(spool, times(1)).addMessage(request);
        verify(spool, never()).getSpoolConfig();
    }

    @Test
    void GIVEN_qos_is_1_and_mqtt_is_offline_WHEN_publish_THEN_return_future_complete()
            throws ExecutionException, InterruptedException, SpoolerStoreException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, ses, false));
        PublishRequest request = PublishRequest.builder().topic("spool").payload(new byte[0])
                .qos(QualityOfService.AT_LEAST_ONCE).build();

        CompletableFuture<Integer> future = client.publish(request);

        assertEquals(0, future.get());
        verify(spool, times(1)).addMessage(request);
        verify(spool, never()).getSpoolConfig();
    }

    @Test
    void GIVEN_add_message_to_spooler_throw_spooler_load_exception_WHEN_publish_THEN_return_future_complete_exceptionally(ExtensionContext context)
            throws SpoolerStoreException, InterruptedException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, ses, false));
        PublishRequest request = PublishRequest.builder().topic("spool").payload(new byte[10])
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        when(spool.addMessage(any())).thenThrow(new SpoolerStoreException("spooler is full"));

        ignoreExceptionOfType(context, SpoolerStoreException.class);
        CompletableFuture<Integer> future = client.publish(request);

        assertTrue(future.isCompletedExceptionally());
        verify(spool, times(1)).addMessage(request);
        verify(spool, never()).getSpoolConfig();
    }

    @Test
    void GIVEN_add_message_to_spooler_throw_interrupted_exception_WHEN_publish_THEN_return_future_complete_exceptionally(ExtensionContext context)
            throws InterruptedException, SpoolerStoreException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, ses, false));
        PublishRequest request = PublishRequest.builder().topic("spool").payload(new byte[0])
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        when(spool.addMessage(any())).thenThrow(InterruptedException.class);

        ignoreExceptionOfType(context, InterruptedException.class);
        CompletableFuture<Integer> future = spy(client.publish(request));

        verify(spool, times(1)).addMessage(request);
        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    void GIVEN_published_request_with_popped_id_is_null_WHEN_spool_message_THEN_remove_message_by_id()
            throws InterruptedException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, ses, true));

        Long id = 1L;
        when(spool.getCurrentMessageCount()).thenReturn(1).thenReturn(0);
        when(spool.popId()).thenReturn(id);
        when(spool.getMessageById(id)).thenReturn(null);
        AwsIotMqttClient awsIotMqttClient = mock(AwsIotMqttClient.class);
        when(client.getNewMqttClient()).thenReturn(awsIotMqttClient);
        when(awsIotMqttClient.connect()).thenReturn(CompletableFuture.completedFuture(true));

        client.spoolTask();

        verify(spool, never()).addId(anyLong());
        verify(spool, never()).removeMessageById(anyLong());
    }

    @Test
    void GIVEN_publish_request_successfully_WHEN_spool_message_THEN_remove_message_from_spooler_queue()
            throws InterruptedException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, ses, true));

        long id = 1L;
        when(spool.getCurrentMessageCount()).thenReturn(1).thenReturn(0);
        when(spool.popId()).thenReturn(id);
        PublishRequest request = PublishRequest.builder().topic("spool")
                .payload("What's up".getBytes(StandardCharsets.UTF_8))
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        when(spool.getMessageById(id)).thenReturn(request);
        AwsIotMqttClient awsIotMqttClient = mock(AwsIotMqttClient.class);
        when(client.getNewMqttClient()).thenReturn(awsIotMqttClient);
        when(awsIotMqttClient.connect()).thenReturn(CompletableFuture.completedFuture(true));
        when(awsIotMqttClient.publish(any(), any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(0));

        client.spoolTask();

        verify(spool).removeMessageById(anyLong());
        verify(awsIotMqttClient).publish(any(), any(), anyBoolean());
        verify(spool, never()).addId(anyLong());
    }

    @Test
    void GIVEN_publish_request_with_interrupted_exception_WHEN_spool_message_THEN_stop_spooling_message(ExtensionContext context)
            throws InterruptedException {
        ignoreExceptionWithMessage(context, "interrupted");
        ignoreExceptionOfType(context, ExecutionException.class);

        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, ses, true));
        long id = 1L;
        when(spool.getCurrentMessageCount()).thenReturn(1);
        when(spool.popId()).thenReturn(id);
        PublishRequest request = PublishRequest.builder().topic("spool")
                .payload("What's up".getBytes(StandardCharsets.UTF_8))
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        when(spool.getMessageById(id)).thenReturn(request);

        AwsIotMqttClient awsIotMqttClient = mock(AwsIotMqttClient.class);
        when(client.getNewMqttClient()).thenReturn(awsIotMqttClient);
        when(awsIotMqttClient.connect()).thenReturn(CompletableFuture.completedFuture(true));
        CompletableFuture<Integer> future = new CompletableFuture<>();
        future.completeExceptionally(new InterruptedException("interrupted"));
        when(awsIotMqttClient.publish(any(), any(), anyBoolean())).thenReturn(future);

        client.spoolTask();

        verify(awsIotMqttClient, atLeastOnce()).publish(any(), any(), anyBoolean());
        verify(spool).addId(anyLong());
        verify(spool, never()).removeMessageById(anyLong());
    }
}
