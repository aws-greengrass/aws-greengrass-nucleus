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
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.mqttclient.spool.Spool;
import com.aws.greengrass.mqttclient.spool.SpoolMessage;
import com.aws.greengrass.mqttclient.spool.SpoolerConfig;
import com.aws.greengrass.mqttclient.spool.SpoolerStorageType;
import com.aws.greengrass.mqttclient.spool.SpoolerStoreException;
import com.aws.greengrass.mqttclient.v5.PubAck;
import com.aws.greengrass.mqttclient.v5.Publish;
import com.aws.greengrass.mqttclient.v5.QOS;
import com.aws.greengrass.mqttclient.v5.Subscribe;
import com.aws.greengrass.mqttclient.v5.SubscribeResponse;
import com.aws.greengrass.security.SecurityService;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.testing.TestFeatureParameterInterface;
import com.aws.greengrass.testing.TestFeatureParameters;
import com.aws.greengrass.util.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.CrtRuntimeException;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttException;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.crt.mqtt5.Mqtt5Client;
import software.amazon.awssdk.crt.mqtt5.Mqtt5ClientOptions;
import software.amazon.awssdk.crt.mqtt5.OnConnectionSuccessReturn;
import software.amazon.awssdk.crt.mqtt5.PublishResult;
import software.amazon.awssdk.crt.mqtt5.packets.PubAckPacket;
import software.amazon.awssdk.crt.mqtt5.packets.SubAckPacket;
import software.amazon.awssdk.crt.mqtt5.packets.UnsubAckPacket;
import software.amazon.awssdk.iot.AwsIotMqtt5ClientBuilder;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_MQTT_NAMESPACE;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_AWS_REGION;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_CERTIFICATE_FILE_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_IOT_DATA_ENDPOINT;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_PRIVATE_KEY_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_ROOT_CA_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_THING_NAME;
import static com.aws.greengrass.mqttclient.MqttClient.CONNECT_LIMIT_PERMITS_FEATURE;
import static com.aws.greengrass.mqttclient.MqttClient.DEFAULT_MQTT_MAX_OF_PUBLISH_RETRY_COUNT;
import static com.aws.greengrass.mqttclient.MqttClient.MQTT_MAX_LIMIT_OF_MESSAGE_SIZE_IN_BYTES;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static com.aws.greengrass.testcommons.testutilities.TestUtils.asyncAssertOnConsumer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.either;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
@SuppressWarnings({"PMD.CloseResource", "PMD.ExcessiveClassLength"})
class MqttClientTest {

    @Mock
    AwsIotMqttConnectionBuilder builder;

    @Mock
    DeviceConfiguration deviceConfiguration;

    @Mock
    MqttClientConnection mockConnection;

    @Mock
    Spool spool;

    @Mock
    Kernel kernel;

    @Mock
    TestFeatureParameterInterface DEFAULT_HANDLER;

    @Mock(answer = Answers.RETURNS_SELF)
    AwsIotMqtt5ClientBuilder mockMqtt5Builder;

    @Mock
    Mqtt5Client mockMqtt5Client;

    ScheduledExecutorService ses = new ScheduledThreadPoolExecutor(1);
    ExecutorService executorService = TestUtils.synchronousExecutorService();

    Topics mqttNamespace;
    Configuration config = new Configuration(new Context());
    private final Consumer<MqttMessage> cb = (m) -> {
    };
    @Captor
    private ArgumentCaptor<Mqtt5ClientOptions.LifecycleEvents> lifecycleEventCaptor;

    @BeforeEach
    void beforeEach() throws Exception {
        lenient().when(DEFAULT_HANDLER.retrieveWithDefault(eq(Double.class), eq(CONNECT_LIMIT_PERMITS_FEATURE), any()))
                .thenReturn(Double.MAX_VALUE);
        TestFeatureParameters.internalEnableTestingFeatureParameters(DEFAULT_HANDLER);
        mqttNamespace = config.lookupTopics("mqtt");
        Topics spoolerNamespace = config.lookupTopics("spooler");
        mqttNamespace.lookup(MqttClient.MQTT_OPERATION_TIMEOUT_KEY).withValue(0);
        mqttNamespace.lookup(MqttClient.MQTT_MAX_IN_FLIGHT_PUBLISHES_KEY)
                .withValue(MqttClient.IOT_MAX_LIMIT_IN_FLIGHT_OF_QOS1_PUBLISHES + 1);
        mqttNamespace.lookup(MqttClient.MQTT_MAX_OF_MESSAGE_SIZE_IN_BYTES_KEY)
                .withValue(MQTT_MAX_LIMIT_OF_MESSAGE_SIZE_IN_BYTES + 1);
        when(deviceConfiguration.getMQTTNamespace()).thenReturn(mqttNamespace);
        lenient().when(deviceConfiguration.isDeviceConfiguredToTalkToCloud()).thenReturn(true);
        lenient().when(deviceConfiguration.getSpoolerNamespace()).thenReturn(spoolerNamespace);
        lenient().when(builder.build()).thenReturn(mockConnection);
        lenient().when(builder.toAwsIotMqtt5ClientBuilder()).thenReturn(mockMqtt5Builder);
        lenient().when(mockMqtt5Builder.build()).thenReturn(mockMqtt5Client);
        lenient().when(mockConnection.connect()).thenReturn(CompletableFuture.completedFuture(false));
        lenient().when(mockConnection.disconnect()).thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(mockConnection.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        lenient().when(mockMqtt5Client.subscribe(any())).thenReturn(CompletableFuture.completedFuture(mock(SubAckPacket.class, Answers.RETURNS_MOCKS)));
        lenient().when(mockMqtt5Client.unsubscribe(any()))
                .thenReturn(CompletableFuture.completedFuture(mock(UnsubAckPacket.class, Answers.RETURNS_MOCKS)));
        lenient().when(mockMqtt5Client.publish(any())).thenReturn(CompletableFuture.completedFuture(mock(PublishResult.class, Answers.RETURNS_MOCKS)));
        lenient().when(mockMqtt5Builder.withLifeCycleEvents(lifecycleEventCaptor.capture())).thenReturn(mockMqtt5Builder);
        lenient().doAnswer((i) -> {
            lifecycleEventCaptor.getValue()
                    .onConnectionSuccess(mockMqtt5Client, mock(OnConnectionSuccessReturn.class, Answers.RETURNS_MOCKS));
            return null;
        }).when(mockMqtt5Client).start();
        lenient().when(mockConnection.unsubscribe(any())).thenReturn(CompletableFuture.completedFuture(0));
        lenient().when(mockConnection.publish(any(), any(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(0));
    }

    @AfterEach
    void afterEach() throws IOException {
        config.context.close();
        ses.shutdownNow();
        executorService.shutdownNow();
        TestFeatureParameters.internalDisableTestingFeatureParameters();
    }

    @ParameterizedTest
    @CsvSource({"10000,10000", "10000,10001"})
    void GIVEN_ping_timeout_gte_keep_alive_WHEN_mqtt_client_connects_THEN_throws_exception(int keepAlive,
                                                                                          int pingTimeout) {
        mqttNamespace.lookup(MqttClient.MQTT_KEEP_ALIVE_TIMEOUT_KEY).withValue(keepAlive);
        mqttNamespace.lookup(MqttClient.MQTT_PING_TIMEOUT_KEY).withValue(pingTimeout);
        MqttClient mqttClient = new MqttClient(deviceConfiguration, ses, executorService,
                mock(SecurityService.class), kernel);
        ExecutionException e = assertThrows(ExecutionException.class, () -> mqttClient.getNewMqttClient().connect().get());
        assertEquals(MqttException.class, e.getCause().getClass());
    }

    @Test
    void GIVEN_device_not_configured_to_talk_to_cloud_WHEN_publish_THEN_throws_exception()
            throws InterruptedException {
        lenient().when(deviceConfiguration.isDeviceConfiguredToTalkToCloud()).thenReturn(false);
        MqttClient client = new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService);
        PublishRequest testRequest =
                PublishRequest.builder().topic("test").qos(QualityOfService.AT_LEAST_ONCE).payload(new byte[0]).build();
        try {
            client.publish(testRequest).whenComplete((r, t) -> {
                assertNotNull(t);
                assertTrue(t.getCause() instanceof SpoolerStoreException);
            }).get();
        } catch (ExecutionException e) {
            // Ignore. Expected to throw and already handled
        }
    }

    @Test
    void GIVEN_multiple_subset_subscriptions_WHEN_subscribe_or_unsubscribe_THEN_only_subscribes_and_unsubscribes_once()
            throws ExecutionException, InterruptedException, TimeoutException, MqttRequestException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService));
        assertFalse(client.connected());

        client.subscribe(SubscribeRequest.builder().topic("A/B/+").callback(cb).build());

        boolean mqtt5 = false;
        if (Mockito.mockingDetails(mockConnection).getInvocations().isEmpty()) {
            mqtt5 = true;
        }

        if (mqtt5) {
            verify(mockMqtt5Client).start();
            verify(mockMqtt5Client).subscribe(any());
        } else {
            verify(mockConnection, times(2)).connect();
            verify(mockConnection).subscribe(eq("A/B/+"), eq(QualityOfService.AT_LEAST_ONCE));
        }

        // This subscription shouldn't actually subscribe through the cloud because it is a subset of the previous sub
        client.subscribe(SubscribeRequest.builder().topic("A/B/C").callback(cb).build());
        // "retry" request to verify that we deduplicate callbacks
        client.subscribe(SubscribeRequest.builder().topic("A/B/C").callback(cb).build());

        if (mqtt5) {
            // verify we've still only called subscribe once
            verify(mockMqtt5Client, atMostOnce()).subscribe(any());

            // Verify that if someone retries, then we will deduplicate their callback. If we did this improperly,
            // then we'd have 3 unique values for callback instead of only 2.
            ArgumentCaptor<Subscribe> captor = ArgumentCaptor.forClass(Subscribe.class);
            verify(client, times(3)).subscribe(captor.capture());
            assertEquals(2,
                    captor.getAllValues().stream().map(Subscribe::getCallback).collect(Collectors.toSet()).size());
        } else {
            verify(mockConnection, times(0)).subscribe(eq("A/B/C"), eq(QualityOfService.AT_LEAST_ONCE));
        }

        // Even though we unsub locally, it should keep the cloud sub because a different on-device client needs it
        client.unsubscribe(UnsubscribeRequest.builder().topic("A/B/+").callback(cb).build());

        if (mqtt5) {
            verify(mockMqtt5Client, times(0)).unsubscribe(any());
        } else {
            verify(mockConnection, times(0)).unsubscribe(any());
        }

        // Now that we've unsubbed on device it can unsub from the cloud
        client.unsubscribe(UnsubscribeRequest.builder().topic("A/B/C").callback(cb).build());
        if (mqtt5) {
            verify(mockMqtt5Client, times(1)).unsubscribe(any());
        } else {
            verify(mockConnection, times(1)).unsubscribe(eq("A/B/+"));
        }
    }

    @Test
    void GIVEN_connection_WHEN_subscribe_timesout_but_then_completes_THEN_subsequent_subscribe_calls_dont_call_cloud()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService);

        assertFalse(client.connected());
        CompletableFuture<Integer> cf = new CompletableFuture<>();
        CompletableFuture<SubAckPacket> cf2 = new CompletableFuture<>();
        lenient().when(mockConnection.subscribe(any(), any())).thenReturn(cf);
        lenient().when(mockMqtt5Client.subscribe(any())).thenReturn(cf2);

        assertThrows(TimeoutException.class,
                () -> client.subscribe(SubscribeRequest.builder().topic("A/B/+").callback(cb).build()));
        cf.complete(0);
        cf2.complete(mock(SubAckPacket.class, Answers.RETURNS_MOCKS));

        // This subscribe call won't result in a cloud call because the previous subscribe succeeded _after_
        // the timeout
        client.subscribe(SubscribeRequest.builder().topic("A/B/+").callback(cb).build());
        if (Mockito.mockingDetails(mockConnection).getInvocations().isEmpty()) {
            // Verify mqtt 5
            verify(mockMqtt5Client).start();
            verify(mockMqtt5Client).subscribe(any());
        } else {
            // Verify mqtt 3
            verify(mockConnection, times(2)).connect();
            verify(mockConnection).subscribe(eq("A/B/+"), eq(QualityOfService.AT_LEAST_ONCE));
        }
    }

    @Test
    void GIVEN_connection_WHEN_settings_change_THEN_reconnects_on_valid_changes()
            throws InterruptedException, ExecutionException, TimeoutException {
        ArgumentCaptor<ChildChanged> cc = ArgumentCaptor.forClass(ChildChanged.class);
        doNothing().when(deviceConfiguration).onAnyChange(cc.capture());
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, ses, executorService, kernel));
        mqttNamespace.context.waitForPublishQueueToClear();

        AwsIotMqttClient iClient1 = mock(AwsIotMqttClient.class);
        when(iClient1.subscribe(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(client.getNewMqttClient()).thenReturn(iClient1);

        // no reconnect if no connections
        cc.getValue().childChanged(WhatHappened.childChanged, config.lookupTopics("test1"));
        verify(iClient1, never()).reconnect(anyLong());

        client.subscribe(SubscribeRequest.builder().topic("A/B/+").callback(cb).build());

        // no reconnect if unrelated node changes
        cc.getValue().childChanged(WhatHappened.childChanged, config.lookupTopics("test2"));
        verify(iClient1, never()).reconnect(anyLong());

        // no reconnect if aws region changed but no proxy configured
        cc.getValue().childChanged(WhatHappened.childChanged, config.lookupTopics(DEVICE_PARAM_AWS_REGION));
        verify(iClient1, never()).reconnect(anyLong());

        // do reconnect if changed node is relevant to client config and reconnect is required
        // this increases branch coverage
        List<String> topicsToTest = Arrays.asList(DEVICE_MQTT_NAMESPACE, DEVICE_PARAM_THING_NAME,
                DEVICE_PARAM_IOT_DATA_ENDPOINT, DEVICE_PARAM_PRIVATE_KEY_PATH, DEVICE_PARAM_CERTIFICATE_FILE_PATH,
                DEVICE_PARAM_ROOT_CA_PATH);
        int reconnectCount = 0;
        for (String topic : topicsToTest) {
            cc.getValue().childChanged(WhatHappened.childChanged, config.lookupTopics(topic, "test"));
            verify(iClient1, timeout(5000).times(++reconnectCount)).reconnect(anyLong());
        }

        client.close();
        verify(iClient1).closeOnShutdown();

        // After closing the MQTT client, unsubscribe should throw an exception and not try to unsubscribe.
        ExecutionException ee = assertThrows(ExecutionException.class, () -> client.unsubscribe((UnsubscribeRequest) null));
        assertThat(ee.getCause(), instanceOf(MqttRequestException.class));
        assertThat(ee.getCause().getMessage(), containsString("shut down"));
    }

    @Test
    void GIVEN_connection_has_50_subscriptions_THEN_new_connection_added_as_needed()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, ses, executorService, kernel));
        AwsIotMqttClient iClient1 = mock(AwsIotMqttClient.class);
        AwsIotMqttClient iClient2 = mock(AwsIotMqttClient.class);
        when(iClient1.subscribe(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(iClient2.subscribe(any())).thenReturn(CompletableFuture.completedFuture(null));
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
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, ses, executorService, kernel));
        AwsIotMqttClient iClient1 = mock(AwsIotMqttClient.class);
        AwsIotMqttClient iClient2 = mock(AwsIotMqttClient.class);
        AwsIotMqttClient iClient3 = mock(AwsIotMqttClient.class);
        when(iClient1.subscribe(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(iClient2.subscribe(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(iClient3.subscribe(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(client.getNewMqttClient()).thenReturn(iClient1).thenReturn(iClient2).thenReturn(iClient3);
        when(iClient1.canAddNewSubscription()).thenReturn(false);
        when(iClient2.isConnectionClosable()).thenReturn(true);
        when(iClient2.canAddNewSubscription()).thenReturn(false).thenReturn(true);
        when(iClient3.canAddNewSubscription()).thenReturn(true);
        when(iClient3.isConnectionClosable()).thenReturn(true);

        client.subscribe(SubscribeRequest.builder().topic("A").callback(cb).build());
        client.subscribe(SubscribeRequest.builder().topic("B").callback(cb).build());
        client.subscribe(SubscribeRequest.builder().topic("C").callback(cb).build());
        when(iClient1.canAddNewSubscription()).thenReturn(true);
        client.subscribe(SubscribeRequest.builder().topic("D").callback(cb).build());

        verify(client, times(4)).getNewMqttClient();
        // Client 1 is not closed. Other clients are closed.
        verify(iClient1, never()).close();
        verify(iClient2, times(1)).close();
        verify(iClient3, times(1)).close();
    }

    @Test
    void GIVEN_multiple_connections_WHEN_connection_removed_THEN_new_connection_gets_new_clientId()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, ses, executorService, kernel));
        AwsIotMqttClient iClient1 = mock(AwsIotMqttClient.class);
        AwsIotMqttClient iClient2 = mock(AwsIotMqttClient.class);
        AwsIotMqttClient iClient3 = mock(AwsIotMqttClient.class);
        when(iClient1.subscribe(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(iClient2.subscribe(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(iClient3.subscribe(any())).thenReturn(CompletableFuture.completedFuture(null));

        assertEquals(0, client.getNextClientIdNumber());

        when(client.getNewMqttClient()).thenReturn(iClient1).thenReturn(iClient2).thenReturn(iClient3);
        when(iClient1.getClientIdNum()).thenReturn(0);
        when(iClient2.getClientIdNum()).thenReturn(1);
        when(iClient3.getClientIdNum()).thenReturn(2);

        // Let connection 2 close, while keeping connection 3 open. This tests for regressions
        // in clientId numbering when there are gaps in the client ID number.
        // ie. if we have clients 0, 1, 2 and then client 1 closes. We now have 0, 2.
        // A new connection should be made with client ID 1.

        when(iClient1.canAddNewSubscription()).thenReturn(false);
        when(iClient2.isConnectionClosable()).thenReturn(true);
        when(iClient2.canAddNewSubscription()).thenReturn(false).thenReturn(true);
        when(iClient3.canAddNewSubscription()).thenReturn(true);
        when(iClient3.isConnectionClosable()).thenReturn(false);

        client.subscribe(SubscribeRequest.builder().topic("A").callback(cb).build());
        client.subscribe(SubscribeRequest.builder().topic("B").callback(cb).build());
        client.subscribe(SubscribeRequest.builder().topic("C").callback(cb).build());
        assertEquals(3, client.getNextClientIdNumber());
        when(iClient1.canAddNewSubscription()).thenReturn(true);
        client.subscribe(SubscribeRequest.builder().topic("D").callback(cb).build());
        assertEquals(1, client.getNextClientIdNumber());

        verify(client, times(4)).getNewMqttClient();
        verify(iClient1, never()).close();
        verify(iClient2, times(1)).close();
        verify(iClient3, never()).close();
    }

    @Test
    void GIVEN_incoming_message_WHEN_received_THEN_subscribers_are_called()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, ses, executorService, kernel));
        AwsIotMqttClient mockIndividual = mock(AwsIotMqttClient.class);
        when(mockIndividual.subscribe(any())).thenReturn(CompletableFuture.completedFuture(null));
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

        Consumer<Publish> handler = client.getMessageHandlerForClient(mockIndividual);

        handler.accept(Publish.builder().topic("A/B/C").payload(new byte[0]).build());
        handler.accept(Publish.builder().topic("A/B/D").payload(new byte[0]).build());
        handler.accept(Publish.builder().topic("A/X/Y").payload(new byte[0]).build()); // No subscribers for this one

        abPlus.getLeft().get(0, TimeUnit.SECONDS);
        abd.getLeft().get(0, TimeUnit.SECONDS);

        // Ensure, that even after removing the wildcard subscription, the other topics still get
        // messages
        client.unsubscribe(UnsubscribeRequest.builder().topic("A/B/+").callback(abPlus.getRight()).build());
        handler.accept(Publish.builder().topic("A/B/C").payload(new byte[0]).build());
        abc.getLeft().get(0, TimeUnit.SECONDS);
    }

    @Test
    void GIVEN_incoming_message_on_wrong_client_WHEN_received_THEN_subscribers_are_still_called()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, ses, executorService, kernel));
        AwsIotMqttClient mockClient1 = mock(AwsIotMqttClient.class);
        AwsIotMqttClient mockClient2 = mock(AwsIotMqttClient.class);
        when(mockClient1.subscribe(any())).thenReturn(CompletableFuture.completedFuture(null));
        // All subscriptions will go through mockClient1, but we're going to send the messages via mockClient2
        when(client.getNewMqttClient()).thenReturn(mockClient1);
        assertFalse(client.connected());

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

        Consumer<Publish> handlerForClient2 = client.getMessageHandlerForClient(mockClient2);

        handlerForClient2.accept(Publish.builder().topic("A/B/C").payload(new byte[0]).build());
        handlerForClient2.accept(Publish.builder().topic("A/B/D").payload(new byte[0]).build());
        handlerForClient2.accept(Publish.builder().topic("A/X/Y").payload(new byte[0]).build()); // No subscribers for this one

        abPlus.getLeft().get(0, TimeUnit.SECONDS);
        abd.getLeft().get(0, TimeUnit.SECONDS);

        // Ensure, that even after removing the wildcard subscription, the other topics still get
        // messages
        client.unsubscribe(UnsubscribeRequest.builder().topic("A/B/+").callback(abPlus.getRight()).build());
        handlerForClient2.accept(Publish.builder().topic("A/B/C").payload(new byte[0]).build());
        abc.getLeft().get(0, TimeUnit.SECONDS);
    }

    @Test
    void GIVEN_3_connections_with_2_able_accept_new_WHEN_subscribe_THEN_closes_connection_with_no_subscribers()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, ses, executorService, kernel));
        assertFalse(client.connected());
        AwsIotMqttClient mockIndividual1 = mock(AwsIotMqttClient.class);
        AwsIotMqttClient mockIndividual2 = mock(AwsIotMqttClient.class);
        AwsIotMqttClient mockIndividual3 = mock(AwsIotMqttClient.class);
        when(mockIndividual2.subscribe(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(mockIndividual1.canAddNewSubscription()).thenReturn(false);
        when(mockIndividual2.canAddNewSubscription()).thenReturn(true);
        when(mockIndividual3.canAddNewSubscription()).thenReturn(true);
        when(mockIndividual2.isConnectionClosable()).thenReturn(false);
        when(mockIndividual3.isConnectionClosable()).thenReturn(true);

        client.getConnections().add(mockIndividual1);
        client.getConnections().add(mockIndividual2);
        client.getConnections().add(mockIndividual3);

        Pair<CompletableFuture<Void>, Consumer<MqttMessage>> abc = asyncAssertOnConsumer((m) -> {
            assertEquals("A/B/C", m.getTopic());
        }, 1);
        client.subscribe(SubscribeRequest.builder().topic("A/B/C").callback(abc.getRight()).build());

        assertEquals(2, client.getConnections().size());
        verify(mockIndividual1, never()).close();
        verify(mockIndividual2, never()).close();
        verify(mockIndividual3, atMostOnce()).close();
        verify(mockIndividual2, atMostOnce()).subscribe(any());
    }

    @Test
    void GIVEN_3_connections_with_2_able_accept_new_with_in_progress_WHEN_subscribe_THEN_does_not_close_any_connections()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, ses, executorService, kernel));
        assertFalse(client.connected());
        AwsIotMqttClient mockIndividual1 = mock(AwsIotMqttClient.class);
        AwsIotMqttClient mockIndividual2 = mock(AwsIotMqttClient.class);
        AwsIotMqttClient mockIndividual3 = mock(AwsIotMqttClient.class);
        when(mockIndividual2.subscribe(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(mockIndividual1.canAddNewSubscription()).thenReturn(false);
        when(mockIndividual2.canAddNewSubscription()).thenReturn(true);
        when(mockIndividual3.canAddNewSubscription()).thenReturn(true);
        when(mockIndividual3.isConnectionClosable()).thenReturn(false);

        client.getConnections().add(mockIndividual1);
        client.getConnections().add(mockIndividual2);
        client.getConnections().add(mockIndividual3);

        Pair<CompletableFuture<Void>, Consumer<MqttMessage>> abc = asyncAssertOnConsumer((m) -> {
            assertEquals("A/B/C", m.getTopic());
        }, 1);
        client.subscribe(SubscribeRequest.builder().topic("A/B/C").callback(abc.getRight()).build());

        assertEquals(3, client.getConnections().size());
        verify(mockIndividual1, never()).close();
        verify(mockIndividual2, never()).close();
        verify(mockIndividual3, never()).close();
    }

    @Test
    void GIVEN_incoming_messages_to_2clients_WHEN_received_THEN_subscribers_are_called_without_duplication()
            throws ExecutionException, InterruptedException, TimeoutException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, ses, executorService, kernel));
        assertFalse(client.connected());
        AwsIotMqttClient mockIndividual1 = mock(AwsIotMqttClient.class);
        AwsIotMqttClient mockIndividual2 = mock(AwsIotMqttClient.class);
        when(mockIndividual1.subscribe(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(mockIndividual2.subscribe(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(client.getNewMqttClient()).thenReturn(mockIndividual1).thenReturn(mockIndividual2);
        when(mockIndividual1.canAddNewSubscription()).thenReturn(false);
        when(mockIndividual2.canAddNewSubscription()).thenReturn(true);

        Pair<CompletableFuture<Void>, Consumer<MqttMessage>> abc = asyncAssertOnConsumer((m) -> {
            assertEquals("A/B/C", m.getTopic());
        }, 1);
        client.subscribe(SubscribeRequest.builder().topic("A/B/C").callback(abc.getRight()).build());
        Pair<CompletableFuture<Void>, Consumer<MqttMessage>> abd = asyncAssertOnConsumer((m) -> {
            assertEquals("A/B/D", m.getTopic());
        }, 2);
        client.subscribe(SubscribeRequest.builder().topic("A/B/D").callback(abd.getRight()).build());
        Pair<CompletableFuture<Void>, Consumer<MqttMessage>> abPlus = asyncAssertOnConsumer((m) -> {
            assertThat(m.getTopic(), either(is("A/B/C")).or(is("A/B/D")).or(is("A/B/F")));
        }, 5);
        client.subscribe(SubscribeRequest.builder().topic("A/B/+").callback(abPlus.getRight()).build());

        Consumer<Publish> handler1 = client.getMessageHandlerForClient(mockIndividual1);
        Consumer<Publish> handler2 = client.getMessageHandlerForClient(mockIndividual2);

        // Send messages to BOTH handler1 and handler2 to show that we appropriately route and don't duplicate
        // messages when multiple overlapping subscriptions exist across individual clients

        // Send all to handler1
        handler1.accept(Publish.builder().topic("A/B/C").payload(new byte[0]).build());
        handler1.accept(Publish.builder().topic("A/B/D").payload(new byte[0]).build());
        handler1.accept(Publish.builder().topic("A/B/F").payload(new byte[0]).build());
        handler1.accept(Publish.builder().topic("A/X/Y").payload(new byte[0]).build());

        // Send all the same messages to handler2
        handler2.accept(Publish.builder().topic("A/B/C").payload(new byte[0]).build());
        handler2.accept(Publish.builder().topic("A/B/D").payload(new byte[0]).build());
        handler2.accept(Publish.builder().topic("A/B/F").payload(new byte[0]).build());
        handler2.accept(Publish.builder().topic("A/X/Y").payload(new byte[0]).build()); // No subscribers for this one

        abPlus.getLeft().get(0, TimeUnit.SECONDS);
        abd.getLeft().get(0, TimeUnit.SECONDS);
        abc.getLeft().get(0, TimeUnit.SECONDS);
    }

    @Test
    void GIVEN_incoming_message_WHEN_received_and_subscriber_throws_THEN_still_calls_remaining_subscriptions(
            ExtensionContext context) throws ExecutionException, InterruptedException, TimeoutException {
        ignoreExceptionWithMessage(context, "Uncaught!");
        MqttClient client = spy(new MqttClient(deviceConfiguration, (c) -> builder, ses, executorService, kernel));
        AwsIotMqttClient mockIndividual = mock(AwsIotMqttClient.class);
        when(mockIndividual.subscribe(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(client.getNewMqttClient()).thenReturn(mockIndividual);
        assertFalse(client.connected());

        client.subscribe(SubscribeRequest.builder().topic("A/B/+").callback((m) -> {
            throw new RuntimeException("Uncaught!");
        }).build());
        Pair<CompletableFuture<Void>, Consumer<MqttMessage>> abc = asyncAssertOnConsumer((m) -> {
            assertEquals("A/B/C", m.getTopic());
        });
        client.subscribe(SubscribeRequest.builder().topic("A/B/C").callback(abc.getRight()).build());

        Consumer<Publish> handler = client.getMessageHandlerForClient(mockIndividual);

        handler.accept(Publish.builder().topic("A/B/C").payload(new byte[0]).build());
        abc.getLeft().get(0, TimeUnit.SECONDS);
    }

    @Test
    void GIVEN_keep_qos_0_when_offline_is_false_and_mqtt_is_offline_WHEN_publish_THEN_future_complete_exceptionally()
            throws InterruptedException, SpoolerStoreException {

        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService));
        PublishRequest request = PublishRequest.builder().topic("spool").payload(new byte[0])
                .qos(QualityOfService.AT_MOST_ONCE).build();
        SpoolerConfig config = SpoolerConfig.builder().keepQos0WhenOffline(false)
                .spoolSizeInBytes(25L).storageType(SpoolerStorageType.Memory)
                .build();
        when(spool.getSpoolConfig()).thenReturn(config);

        CompletableFuture<Integer> future = client.publish(request);

        assertTrue(future.isCompletedExceptionally());
        verify(spool, never()).addMessage(any());
    }

    @Test
    void GIVEN_keep_qos_0_when_offline_is_false_and_mqtt_is_online_WHEN_publish_THEN_return_future_complete()
            throws ExecutionException, InterruptedException, SpoolerStoreException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, true, (c) -> builder, executorService));
        PublishRequest request = PublishRequest.builder().topic("spool").payload(new byte[0])
                .qos(QualityOfService.AT_MOST_ONCE).build();
        SpoolMessage message = SpoolMessage.builder().id(0L).request(request.toPublish()).build();

        when(spool.addMessage(request.toPublish())).thenReturn(message);
        when(spool.popId()).thenThrow(InterruptedException.class);

        CompletableFuture<Integer> future = client.publish(request);

        assertEquals(0, future.get());
        verify(spool, times(1)).addMessage(request.toPublish());
        verify(spool, never()).getSpoolConfig();
        Thread.interrupted(); // Clear interrupt flag set by throwing InterruptedException
    }

    @Test
    void GIVEN_qos_is_1_and_mqtt_is_offline_WHEN_publish_THEN_return_future_complete()
            throws ExecutionException, InterruptedException, SpoolerStoreException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService));
        PublishRequest request = PublishRequest.builder().topic("spool").payload(new byte[0])
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        SpoolMessage message = SpoolMessage.builder().id(0L).request(request.toPublish()).build();
        when(spool.addMessage(request.toPublish())).thenReturn(message);

        CompletableFuture<Integer> future = client.publish(request);

        assertEquals(0, future.get());
        verify(spool, times(1)).addMessage(request.toPublish());
        verify(spool, never()).getSpoolConfig();
    }

    @Test
    void GIVEN_add_message_to_spooler_throw_spooler_load_exception_WHEN_publish_THEN_return_future_complete_exceptionally(ExtensionContext context)
            throws SpoolerStoreException, InterruptedException {

        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService));
        PublishRequest request = PublishRequest.builder().topic("spool").payload(new byte[10])
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        when(spool.addMessage(any())).thenThrow(new SpoolerStoreException("spooler is full"));

        ignoreExceptionOfType(context, SpoolerStoreException.class);
        CompletableFuture<Integer> future = client.publish(request);

        assertTrue(future.isCompletedExceptionally());
        verify(spool, times(1)).addMessage(request.toPublish());
        verify(spool, never()).getSpoolConfig();
    }

    @Test
    void GIVEN_add_message_to_spooler_throw_interrupted_exception_WHEN_publish_THEN_return_future_complete_exceptionally(ExtensionContext context)
            throws InterruptedException, SpoolerStoreException {

        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService));
        PublishRequest request = PublishRequest.builder().topic("spool").payload(new byte[0])
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        when(spool.addMessage(any())).thenThrow(InterruptedException.class);

        ignoreExceptionOfType(context, InterruptedException.class);
        CompletableFuture<Integer> future = spy(client.publish(request));

        verify(spool, times(1)).addMessage(request.toPublish());
        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    void GIVEN_publish_request_successfully_WHEN_spool_single_message_THEN_remove_message_from_spooler_queue()
            throws InterruptedException {

        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, true, (c) -> builder, executorService));
        long id = 1L;
        when(spool.popId()).thenReturn(id);
        PublishRequest request = PublishRequest.builder().topic("spool")
                .payload("What's up".getBytes(StandardCharsets.UTF_8))
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        SpoolMessage message = SpoolMessage.builder().id(id).request(request.toPublish()).build();
        when(spool.getMessageById(id)).thenReturn(message);
        AwsIotMqttClient awsIotMqttClient = mock(AwsIotMqttClient.class);
        when(awsIotMqttClient.publish(any())).thenReturn(CompletableFuture.completedFuture(null));

        client.publishSingleSpoolerMessage(awsIotMqttClient);

        verify(spool).removeMessageById(anyLong());
        verify(awsIotMqttClient).publish(any());
        verify(spool, never()).addId(anyLong());
    }

    @Test
    void GIVEN_publish_request_unsuccessfully_WHEN_spool_single_message_THEN_add_id_back_to_spooler_if_will_retry(ExtensionContext context)
            throws InterruptedException {

        ignoreExceptionOfType(context, ExecutionException.class);

        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, true, (c) -> builder,
                executorService));

        long id = 1L;
        when(spool.popId()).thenReturn(id);
        PublishRequest request = PublishRequest.builder().topic("spool")
                .payload("What's up".getBytes(StandardCharsets.UTF_8))
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        SpoolMessage message = SpoolMessage.builder().id(id).request(request.toPublish()).build();
        when(spool.getMessageById(id)).thenReturn(message);
        AwsIotMqttClient awsIotMqttClient = mock(AwsIotMqttClient.class);
        CompletableFuture<PubAck> future = new CompletableFuture<>();
        future.completeExceptionally(new ExecutionException("exception", new Throwable()));
        when(awsIotMqttClient.publish(any())).thenReturn(future);

        client.publishSingleSpoolerMessage(awsIotMqttClient);

        verify(awsIotMqttClient).publish(any());
        verify(spool, never()).removeMessageById(anyLong());
        verify(spool).addId(anyLong());
    }

    @Test
    void GIVEN_publish_request_with_bad_reason_code_WHEN_spool_single_message_THEN_add_id_back_to_spooler_if_will_retry(ExtensionContext context)
            throws InterruptedException {

        ignoreExceptionOfType(context, ExecutionException.class);

        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, true, (c) -> builder,
                executorService));

        long id = 1L;
        when(spool.popId()).thenReturn(id);
        PublishRequest request = PublishRequest.builder().topic("spool")
                .payload("What's up".getBytes(StandardCharsets.UTF_8))
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        SpoolMessage message = SpoolMessage.builder().id(id).request(request.toPublish()).build();
        when(spool.getMessageById(id)).thenReturn(message);
        AwsIotMqtt5Client awsIotMqttClient = mock(AwsIotMqtt5Client.class);
        // Retryable exception
        CompletableFuture<PubAck> future =
                CompletableFuture.completedFuture(new PubAck(PubAckPacket.PubAckReasonCode.QUOTA_EXCEEDED.getValue(),
                        "", Collections.emptyList()));
        when(awsIotMqttClient.publish(any())).thenReturn(future);

        client.publishSingleSpoolerMessage(awsIotMqttClient);

        verify(awsIotMqttClient).publish(any());
        verify(spool, never()).removeMessageById(anyLong());
        verify(spool).addId(anyLong());

        // Non-retryable exception
        future =
                CompletableFuture.completedFuture(new PubAck(PubAckPacket.PubAckReasonCode.TOPIC_NAME_INVALID.getValue(),
                        "", Collections.emptyList()));
        when(awsIotMqttClient.publish(any())).thenReturn(future);

        client.publishSingleSpoolerMessage(awsIotMqttClient);

        verify(awsIotMqttClient, times(2)).publish(any());
        verify(spool).removeMessageById(id);
        verify(spool, times(2)).addId(id);
    }

    @Test
    void GIVEN_publish_request_unsuccessfully_WHEN_spool_single_message_THEN_not_retry_if_have_retried_max_times(ExtensionContext context)
            throws InterruptedException {

        ignoreExceptionOfType(context, ExecutionException.class);

        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, true, (c) -> builder,
                executorService));

        long id = 1L;
        when(spool.popId()).thenReturn(id);
        PublishRequest request = PublishRequest.builder().topic("spool")
                .payload("What's up".getBytes(StandardCharsets.UTF_8))
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        SpoolMessage message = SpoolMessage.builder().id(id).request(request.toPublish()).build();
        message.getRetried().set(DEFAULT_MQTT_MAX_OF_PUBLISH_RETRY_COUNT);
        when(spool.getMessageById(id)).thenReturn(message);
        AwsIotMqttClient awsIotMqttClient = mock(AwsIotMqttClient.class);
        CompletableFuture<PubAck> future = new CompletableFuture<>();
        future.completeExceptionally(new ExecutionException("exception", new Throwable()));
        when(awsIotMqttClient.publish(any())).thenReturn(future);

        client.publishSingleSpoolerMessage(awsIotMqttClient);

        verify(awsIotMqttClient).publish(any());
        verify(spool, times(1)).removeMessageById(anyLong());
        verify(spool, never()).addId(anyLong());
    }

    @Test
    void GIVEN_spool_pop_id_interrupted_WHEN_spool_message_THEN_stop_spooling_message(ExtensionContext context)
            throws InterruptedException {

        ignoreExceptionOfType(context, InterruptedException.class);

        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, true, (c) -> builder, executorService));
        client.setMqttOnline(true);
        long id = 1L;
        when(spool.popId()).thenReturn(id).thenThrow(InterruptedException.class);
        PublishRequest request = PublishRequest.builder().topic("spool")
                .payload("What's up".getBytes(StandardCharsets.UTF_8))
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        SpoolMessage message = SpoolMessage.builder().id(id).request(request.toPublish()).build();
        when(spool.getMessageById(id)).thenReturn(message);

        AwsIotMqttClient awsIotMqttClient = mock(AwsIotMqttClient.class);
        when(client.getNewMqttClient()).thenReturn(awsIotMqttClient);
        when(awsIotMqttClient.connect()).thenReturn(CompletableFuture.completedFuture(true));
        when(client.getNewMqttClient()).thenReturn(awsIotMqttClient);
        when(awsIotMqttClient.publish(any())).thenReturn(CompletableFuture.completedFuture(null));

        client.runSpooler();

        verify(client).runSpooler();
        verify(awsIotMqttClient).publish(any());
        verify(spool).getMessageById(anyLong());
        verify(spool).removeMessageById(anyLong());
        // The 3rd call is to trigger Interrupted Exception and exit the loop
        verify(spool, times(2)).popId();
        verify(client, times(2)).publishSingleSpoolerMessage(awsIotMqttClient);
        Thread.interrupted(); // Clear interrupt flag set by throwing InterruptedException
    }

    @Test
    void GIVEN_publish_request_execution_exception_WHEN_spool_message_THEN_continue_spooling_message(ExtensionContext context)
            throws InterruptedException {
        ignoreExceptionOfType(context, ExecutionException.class);
        ignoreExceptionOfType(context, InterruptedException.class);

        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, true, (c) -> builder, executorService));
        client.setMqttOnline(true);

        long id = 1L;
        when(spool.popId()).thenReturn(id).thenReturn(id).thenThrow(InterruptedException.class);
        Publish request = Publish.builder().topic("spool")
                .payload("What's up".getBytes(StandardCharsets.UTF_8))
                .qos(QOS.AT_LEAST_ONCE).build();
        SpoolMessage message = SpoolMessage.builder().id(id).request(request).build();
        when(spool.getMessageById(id)).thenReturn(message);

        AwsIotMqttClient awsIotMqttClient = mock(AwsIotMqttClient.class);
        when(client.getNewMqttClient()).thenReturn(awsIotMqttClient);
        when(awsIotMqttClient.connect()).thenReturn(CompletableFuture.completedFuture(true));
        CompletableFuture<PubAck> future = new CompletableFuture<>();
        future.completeExceptionally(new ExecutionException("exception", new Throwable()));
        when(awsIotMqttClient.publish(any())).thenReturn(future);

        client.runSpooler();

        verify(client).runSpooler();
        verify(awsIotMqttClient, times(2)).publish(any());
        verify(spool, times(2)).getMessageById(anyLong());
        verify(spool, never()).removeMessageById(anyLong());
        // The 3rd call is to trigger Interrupted Exception and exit the loop
        verify(spool, times(3)).popId();
        verify(client, times(3)).publishSingleSpoolerMessage(awsIotMqttClient);
        Thread.interrupted(); // Clear interrupt flag set by throwing InterruptedException
    }


    @Test
    void GIVEN_connection_resumed_WHEN_callback_THEN_start_spool_messages(ExtensionContext context)
            throws InterruptedException {

        ignoreExceptionOfType(context, InterruptedException.class);
        ignoreExceptionWithMessage(context, "interrupted");

        // The mqttClient is initiated when connectivity is offline
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false,
                (c) -> builder, executorService));
        Long id  = 1L;
        Publish request = Publish.builder().topic("spool")
                .payload("What's up".getBytes(StandardCharsets.UTF_8))
                .qos(QOS.AT_LEAST_ONCE).build();
        SpoolMessage message = SpoolMessage.builder().id(id).request(request).build();
        when(spool.getMessageById(id)).thenReturn(message);
        // Throw an InterruptedException to break the while loop in the client.spoolMessages()
        when(spool.popId()).thenReturn(id).thenThrow(new InterruptedException("interrupted"));

        client.getCallbacks().onConnectionResumed(false);

        // Confirm the spooler was working
        verify(spool, times(1)).getMessageById(anyLong());
        verify(spool, times(2)).popId();

        SpoolerConfig config = SpoolerConfig.builder().spoolSizeInBytes(10L)
                .storageType(SpoolerStorageType.Memory).keepQos0WhenOffline(false).build();
        when(spool.getSpoolConfig()).thenReturn(config);

        client.getCallbacks().onConnectionInterrupted(1);

        verify(spool).getSpoolConfig();
        verify(spool).popOutMessagesWithQosZero();
        Thread.interrupted(); // Clear interrupt flag set by throwing InterruptedException
    }

    @Test
    void GIVEN_connection_interrupted_WHEN_callback_THEN_drop_messages_if_required() {
        // The mqttClient is initiated when connectivity is offline
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false,
                (c) -> builder, executorService));

        SpoolerConfig config = SpoolerConfig.builder().spoolSizeInBytes(10L)
                .storageType(SpoolerStorageType.Memory).keepQos0WhenOffline(false).build();
        when(spool.getSpoolConfig()).thenReturn(config);

        client.getCallbacks().onConnectionInterrupted(1);

        verify(spool).getSpoolConfig();
        verify(spool).popOutMessagesWithQosZero();
    }

    @Test
    void GIVEN_message_size_exceeds_max_limit_WHEN_publish_THEN_future_complete_exceptionally() throws SpoolerStoreException, InterruptedException, MqttRequestException {
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService));
        PublishRequest request = PublishRequest.builder().topic("spool")
                .payload(new byte[MQTT_MAX_LIMIT_OF_MESSAGE_SIZE_IN_BYTES + 1])
                .qos(QualityOfService.AT_LEAST_ONCE).build();

        CompletableFuture<Integer> future = client.publish(request);

        assertTrue(future.isCompletedExceptionally());
        verify(spool, never()).addMessage(any());
    }

    public static Stream<Arguments> validSubscribeTopics() {
        return Stream.concat(validPublishTopics(),
                Stream.of(
                        // mqtt shared subscriptions topic (mqtt5 only)
                        Arguments.of("$share/share_name/my/example/topic/with/up/to/seven/levels", "mqtt5"),
                        Arguments.of("$share/share_name/my/example/topic/with/max/size/000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
                                "mqtt5"),
                        // wildcards
                        Arguments.of("a/b/+", "mqtt3"),
                        Arguments.of("a/b/+", "mqtt5"),
                        Arguments.of("a/b/#", "mqtt3"),
                        Arguments.of("a/b/#", "mqtt5")
                ));
    }

    @ParameterizedTest
    @MethodSource("validSubscribeTopics")
    void GIVEN_valid_topic_WHEN_subscribe_THEN_success(String topic, String mqttVersion) throws Exception {
        withMqttVersion(mqttVersion);
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService));
        client.subscribe(SubscribeRequest.builder()
                .topic(topic)
                .callback(cb)
                .build());
    }

    public static Stream<Arguments> validPublishTopics() {
        return Stream.of(
                // basic ingest topic
                Arguments.of("$aws/rules/rule_name/my/example/topic/with/up/to/seven/levels", "mqtt3"),
                Arguments.of("$aws/rules/rule_name/my/example/topic/with/up/to/seven/levels", "mqtt5"),
                // special case: reserved topic with > 7 levels (mqtt5 only)
                Arguments.of("$aws/iotwireless/events/eventName/eventType/sidewalk/resourceType/resourceId/id", "mqtt5"),
                // unreserved topic
                Arguments.of("my/example/topic/with/up/to/seven/levels", "mqtt3"),
                Arguments.of("my/example/topic/with/up/to/seven/levels", "mqtt5"),
                // basic ingest topic that's 256 bytes
                Arguments.of("$aws/rules/rule_name/my/example/topic/with/max/size/000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
                        "mqtt3"),
                Arguments.of("$aws/rules/rule_name/my/example/topic/with/max/size/000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
                        "mqtt5"),
                // other reserved topic that's 512 bytes (arbitrary limit)
                // rather than having to maintain prefixes for every possibility,
                // rely on server-side validation
                Arguments.of("$aws/iotwireless/events/eventName/eventType/sidewalk/resourceType/resourceId/000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
                        "mqtt5"),
                // unreserved topic that's 256 bytes
                Arguments.of("my/example/topic/with/max/size/000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
                        "mqtt3"),
                Arguments.of("my/example/topic/with/max/size/000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
                        "mqtt5")
        );
    }

    @ParameterizedTest
    @MethodSource("validPublishTopics")
    void GIVEN_valid_topic_WHEN_publish_THEN_success(String topic, String mqttVersion) throws Exception {
        withMqttVersion(mqttVersion);
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService));
        CompletableFuture<Integer> future = client.publish(PublishRequest.builder()
                .topic(topic)
                .payload(new byte[1])
                .qos(QualityOfService.AT_LEAST_ONCE)
                .build());
        assertEquals(0, future.get());
        verify(spool).addMessage(any());
    }

    public static Stream<Arguments> invalidSubscribeTopics() {
        return Stream.of(
                Arguments.of("", "mqtt3"),
                Arguments.of("      ", "mqtt3"),
                // basic ingest
                Arguments.of("$aws/rules/rule_name/my/example/topic/with/more/than/seven/levels/whoops", "mqtt3"),
                Arguments.of("$aws/rules/rule_name/my/example/topic/with/more/than/seven/levels/whoops", "mqtt5"),
                // mqtt shared subscriptions
                Arguments.of("$share/share_name/my/example/topic/with/more/than/seven", "mqtt3"), // no shared subscriptions for mqtt3
                Arguments.of("$share/share_name/my/example/topic/with/more/than/seven/levels/whoops", "mqtt5"),
                // reserved topic with too many levels (mqtt3)
                Arguments.of("$aws/iotwireless/events/eventName/eventType/sidewalk/resourceType/resourceId/id",
                        "mqtt3"),
                // unreserved topic
                Arguments.of( "my/example/topic/with/more/than/seven/levels/whoops", "mqtt3"),
                Arguments.of( "my/example/topic/with/more/than/seven/levels/whoops", "mqtt5"),
                // basic ingest topic that's 1 byte greater than 256 bytes
                Arguments.of("$aws/rules/rule_name/my/example/topic/thats/too/large/00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
                        "mqtt3"),
                Arguments.of("$aws/rules/rule_name/my/example/topic/thats/too/large/00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
                        "mqtt5"),
                // mqtt shared subscription topic that's 1 byte greater than 256 bytes
                Arguments.of("$share/share_name/my/example/topic/thats/too/large/00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
                        "mqtt3"),
                Arguments.of("$share/share_name/my/example/topic/thats/too/large/00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
                        "mqtt5"),
                // other reserved topic that's 1 byte greater than 512 bytes (arbitrary limit)
                // rather than having to maintain prefixes for every possibility,
                // rely on server-side validation
                Arguments.of("$aws/some/other/reserved/topic/too/large/0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
                        "mqtt3"),
                Arguments.of("$aws/iotwireless/events/eventName/eventType/sidewalk/resourceType/resourceId/0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
                        "mqtt5"),
                // unreserved topic that's 1 byte greater than 256 bytes
                Arguments.of("my/example/topic/thats/too/large/00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
                        "mqtt3"),
                Arguments.of("my/example/topic/thats/too/large/00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
                        "mqtt5")
        );
    }

    @ParameterizedTest
    @MethodSource("invalidSubscribeTopics")
    void GIVEN_invalid_topic_WHEN_subscribe_THEN_failure(String topic, String mqttVersion) {
        withMqttVersion(mqttVersion);
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService));
        assertThrows(ExecutionException.class, () -> client.subscribe(SubscribeRequest.builder()
                .topic(topic)
                .callback(cb)
                .build()));
    }

    public static Stream<Arguments> invalidPublishTopics() {
        return Stream.concat(invalidSubscribeTopics(),
                Stream.of(
                        // shared subscriptions
                        Arguments.of("$share/share_name/my/example/topic/with/more/than/seven", "mqtt5"),
                        // wildcard topics
                        Arguments.of("abc/+", "mqtt3"),
                        Arguments.of("abc/+", "mqtt5"),
                        Arguments.of("abc/#", "mqtt3"),
                        Arguments.of("abc/#", "mqtt5")
                ));
    }

    @ParameterizedTest
    @MethodSource("invalidPublishTopics")
    void GIVEN_invalid_topic_WHEN_publish_THEN_failure(String topic, String mqttVersion) throws Exception {
        withMqttVersion(mqttVersion);
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService));
        CompletableFuture<Integer> future = client.publish(PublishRequest.builder()
                .topic(topic)
                .payload(new byte[1])
                .qos(QualityOfService.AT_LEAST_ONCE)
                .build());
        assertTrue(future.isCompletedExceptionally());
        verify(spool, never()).addMessage(any());
    }

    @Test
    void GIVEN_subscribe_fails_THEN_deprecated_subscribe_throws(ExtensionContext context)
            throws ExecutionException, InterruptedException, TimeoutException, MqttRequestException {
        ignoreExceptionUltimateCauseOfType(context, CrtRuntimeException.class);
        MqttClient client = spy(new MqttClient(deviceConfiguration, spool, false, (c) -> builder, executorService));
        SubscribeRequest request = SubscribeRequest.builder().topic("A").callback(cb).build();

        // Handles exceptions thrown by mqtt client
        doThrow(CrtRuntimeException.class).when(mockMqtt5Client).subscribe(any());

        ExecutionException ee = assertThrows(ExecutionException.class, () -> client.subscribe(request));
        assertThat(ee.getCause(), instanceOf(CrtRuntimeException.class));

        // Does not throw on null response
        doReturn(CompletableFuture.completedFuture(null)).when(client).subscribe(any(Subscribe.class));
        client.subscribe(request);
        reset(client);

        // Throws if Subscription fails with a reason code
        doReturn(CompletableFuture.completedFuture(new SubscribeResponse(null,
                SubAckPacket.SubAckReasonCode.UNSPECIFIED_ERROR.getValue(), null))).when(client).subscribe(any(Subscribe.class));
        ee = assertThrows(ExecutionException.class, () -> client.subscribe(request));
        assertThat(ee.getCause(), instanceOf(MqttException.class));
    }

    private void withMqttVersion(String version) {
        mqttNamespace.lookup(MqttClient.MQTT_VERSION_KEY).withValue(version);
    }
}
