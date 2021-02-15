/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("PMD.CloseResource")
@ExtendWith({GGExtension.class, MockitoExtension.class})
class AwsIotMqttClientTest {

    private static final int VERIFY_TIMEOUT_MILLIS = 1000;

    @Mock
    AwsIotMqttConnectionBuilder builder;

    @Mock
    MqttClientConnection connection;

    @Mock
    MqttClientConnectionEvents mockCallback1;

    @Mock
    MqttClientConnectionEvents mockCallback2;

    @Captor
    ArgumentCaptor<MqttClientConnectionEvents> events;

    CallbackEventManager callbackEventManager;
    Topics mockTopic;

    // same as what we use in Kernel
    private ExecutorService executorService;
    private ScheduledExecutorService ses;

    @BeforeEach
    void beforeEach() {
        callbackEventManager = spy(new CallbackEventManager());
        callbackEventManager.addToCallbackEvents(mockCallback1);
        callbackEventManager.addToCallbackEvents(mockCallback2);
        mockTopic = mock(Topics.class);
        executorService = Executors.newCachedThreadPool();
        ses = new ScheduledThreadPoolExecutor(4);
    }

    @AfterEach
    void cleanup() throws InterruptedException {
        executorService.shutdownNow();
        ses.shutdownNow();
        ses.awaitTermination(5, TimeUnit.SECONDS);
        executorService.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void GIVEN_client_WHEN_disconnect_without_ever_connecting_THEN_succeeds()
            throws ExecutionException, InterruptedException {
        AwsIotMqttClient client = new AwsIotMqttClient(() -> builder, (x) -> null, "A", mockTopic,
                callbackEventManager, executorService, ses);
        assertNull(client.disconnect().get());
    }

    @Test
    void GIVEN_client_connection_fails_WHEN_subscribe_THEN_throws_exception(ExtensionContext context) {
        String testExceptionMsg = "testing";
        CompletableFuture<Boolean> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new Exception(testExceptionMsg));
        ignoreExceptionUltimateCauseWithMessage(context, testExceptionMsg);
        when(connection.connect()).thenReturn(failedFuture);
        doNothing().when(connection).onMessage(any());
        when(builder.build()).thenReturn(connection);

        AwsIotMqttClient client = new AwsIotMqttClient(() -> builder, (x) -> null, "A", mockTopic,
                callbackEventManager, executorService, ses);
        assertThrows(ExecutionException.class, () -> {
            client.subscribe("test", QualityOfService.AT_MOST_ONCE).get();
        });
    }

    @Test
    void GIVEN_individual_client_THEN_it_tracks_connection_state_correctly()
            throws ExecutionException, InterruptedException, TimeoutException {
        when(mockTopic.findOrDefault(any(), any())).thenReturn(1000);
        when(connection.connect()).thenReturn(CompletableFuture.completedFuture(false));
        when(connection.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(connection.disconnect()).thenReturn(CompletableFuture.completedFuture(null));
        when(builder.withConnectionEventCallbacks(events.capture())).thenReturn(builder);

        AwsIotMqttClient client = new AwsIotMqttClient(() -> builder, (x) -> null, "A", mockTopic,
                callbackEventManager, executorService, ses);
        assertFalse(client.connected());

        when(builder.build()).thenReturn(connection);
        // Call subscribe which will cause the client to connect
        client.subscribe("A", QualityOfService.AT_LEAST_ONCE);

        assertTrue(client.connected());
        client.reconnect();
        verify(connection, times(2)).close();
        verify(connection, times(2)).disconnect();
        assertTrue(client.connected());

        // Ensure that we track connection state through the callbacks
        events.getValue().onConnectionInterrupted(0);
        assertFalse(client.connected());

        events.getValue().onConnectionResumed(true);
        assertTrue(client.connected());

        client.close();
        assertFalse(client.connected());
        verify(connection, times(3)).disconnect();
        verify(connection, times(3)).close();
    }

    @Test
    void GIVEN_individual_client_THEN_it_tracks_subscriptions_correctly()
            throws ExecutionException, InterruptedException, TimeoutException {
        when(mockTopic.findOrDefault(any(), any())).thenReturn(1000);
        when(connection.connect()).thenReturn(CompletableFuture.completedFuture(false));
        when(connection.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(connection.unsubscribe(any())).thenReturn(CompletableFuture.completedFuture(0));
        when(connection.disconnect()).thenReturn(CompletableFuture.completedFuture(null));
        when(builder.withConnectionEventCallbacks(events.capture())).thenReturn(builder);

        AwsIotMqttClient client = new AwsIotMqttClient(() -> builder, (x) -> null, "A", mockTopic,
                callbackEventManager, executorService, ses);
        when(builder.build()).thenReturn(connection);

        Map<String, QualityOfService> expectedSubs = new HashMap<>();
        expectedSubs.put("A", QualityOfService.AT_LEAST_ONCE);
        client.subscribe("A", QualityOfService.AT_LEAST_ONCE).get();
        assertEquals(expectedSubs, client.getSubscriptionTopics());

        client.reconnect();
        assertEquals(expectedSubs, client.getSubscriptionTopics());

        expectedSubs.put("B", QualityOfService.AT_MOST_ONCE);
        client.subscribe("B", QualityOfService.AT_MOST_ONCE).get();

        events.getValue().onConnectionInterrupted(0);
        assertEquals(expectedSubs, client.getSubscriptionTopics());

        events.getValue().onConnectionResumed(true);
        assertEquals(expectedSubs, client.getSubscriptionTopics());

        expectedSubs.remove("B");
        client.unsubscribe("B").get();
        assertEquals(expectedSubs, client.getSubscriptionTopics());
    }

    @Test
    void GIVEN_individual_client_THEN_it_can_publish_to_topics() throws ExecutionException, InterruptedException {
        when(connection.connect()).thenReturn(CompletableFuture.completedFuture(false));
        when(connection.disconnect()).thenReturn(CompletableFuture.completedFuture(null));
        when(connection.publish(any(), any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(0));
        when(builder.withConnectionEventCallbacks(events.capture())).thenReturn(builder);

        AwsIotMqttClient client = new AwsIotMqttClient(() -> builder, (x) -> null, "A", mockTopic,
                callbackEventManager, executorService, ses);
        when(builder.build()).thenReturn(connection);
        client.publish(new MqttMessage("A", new byte[0]), QualityOfService.AT_MOST_ONCE, false).get();
        verify(connection, times(1)).publish(any(), any(), anyBoolean());
    }

    @Test
    void GIVEN_individual_client_THEN_client_connects_and_disconnects_only_for_initial_connect()
            throws InterruptedException, ExecutionException, TimeoutException {
        when(connection.connect()).thenReturn(CompletableFuture.completedFuture(false));
        when(connection.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(connection.disconnect()).thenReturn(CompletableFuture.completedFuture(null));
        when(builder.withConnectionEventCallbacks(events.capture())).thenReturn(builder);
        when(builder.build()).thenReturn(connection);
        AwsIotMqttClient client = new AwsIotMqttClient(() -> builder, (x) -> null, "A", mockTopic,
                callbackEventManager, executorService, ses);

        //initial connect, client connects, disconnects and then connects
        client.subscribe("A", QualityOfService.AT_LEAST_ONCE);
        verify(connection, times(2)).connect();
        verify(connection, times(1)).disconnect();

        //client connected, no change in connect/disconnect calls
        client.subscribe("B", QualityOfService.AT_LEAST_ONCE);
        verify(connection, times(2)).connect();
        verify(connection, times(1)).disconnect();
        //client calls disconnect
        client.disconnect().get(client.getTimeout(), TimeUnit.MILLISECONDS);
        verify(connection, times(2)).disconnect();

        //client calls connect
        client.subscribe("C", QualityOfService.AT_LEAST_ONCE);
        verify(connection, times(3)).connect();

    }

    @Test
    void GIVEN_no_connection_WHEN_call_any_operation_THEN_attempts_connection(ExtensionContext context)
            throws ExecutionException, InterruptedException {
        ignoreExceptionUltimateCauseWithMessage(context, "ex");
        CompletableFuture<Boolean> fut = new CompletableFuture<>();
        fut.completeExceptionally(new Exception("ex"));
        when(connection.connect()).thenReturn(fut);
        when(connection.disconnect()).thenReturn(CompletableFuture.completedFuture(null));
        when(connection.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(builder.withConnectionEventCallbacks(events.capture())).thenReturn(builder);

        AwsIotMqttClient client = new AwsIotMqttClient(() -> builder, (x) -> null, "A", mockTopic,
                callbackEventManager, executorService, ses);
        assertFalse(client.connected());

        when(builder.build()).thenReturn(connection);
        // Call subscribe which will cause the client to connect
        assertThrows(ExecutionException.class, () ->
        client.subscribe("A", QualityOfService.AT_LEAST_ONCE).get());

        assertFalse(client.connected());

        when(connection.connect()).thenReturn(CompletableFuture.completedFuture(false));
        client.subscribe("A", QualityOfService.AT_LEAST_ONCE).get();
        assertTrue(client.connected());
    }

    @Test
    void GIVEN_multiple_callbacks_in_callbackEventManager_WHEN_connections_are_resumed_THEN_oneTimeCallbacks_would_be_executed_once() {

        AwsIotMqttClient client1 = new AwsIotMqttClient(() -> builder, (x) -> null, "A", mockTopic,
                callbackEventManager, executorService, ses);
        AwsIotMqttClient client2 = new AwsIotMqttClient(() -> builder, (x) -> null, "B", mockTopic,
                callbackEventManager, executorService, ses);
        boolean sessionPresent = false;
        // callbackEventManager.hasCallBacked is originally set as False
        assertFalse(callbackEventManager.hasCallbacked());

        client1.getConnectionEventCallback().onConnectionResumed(sessionPresent);
        // the callbackEvent has been finished when it was called by the first AwsIotMqttClient
        assertTrue(callbackEventManager.hasCallbacked());
        verify(callbackEventManager, times(1)).runOnConnectionResumed(sessionPresent);
        verify(mockCallback1, times(1)).onConnectionResumed(sessionPresent);
        verify(mockCallback2, times(1)).onConnectionResumed(sessionPresent);

        client2.getConnectionEventCallback().onConnectionResumed(sessionPresent);
        // if a mqttClient has [n]*AwsIotMqttClient, the callbackEventManager.runOnConnectionResumed would
        // be called [n] times but the callbacks in the callbackEventManager.oneTimeCallbackEvents would be
        // executed once.
        verify(callbackEventManager, times(2)).runOnConnectionResumed(sessionPresent);
        verify(mockCallback1, times(1)).onConnectionResumed(sessionPresent);
        verify(mockCallback2, times(1)).onConnectionResumed(sessionPresent);
        assertTrue(callbackEventManager.hasCallbacked());
    }

    @Test
    void GIVEN_multiple_callbacks_in_callbackEventManager_WHEN_connections_are_interrupted_THEN_oneTimeCallbacks_would_be_executed_once() {

        AwsIotMqttClient client1 = new AwsIotMqttClient(() -> builder, (x) -> null, "A", mockTopic,
                callbackEventManager, executorService, ses);
        AwsIotMqttClient client2 = new AwsIotMqttClient(() -> builder, (x) -> null, "B", mockTopic,
                callbackEventManager, executorService, ses);
        callbackEventManager.runOnConnectionResumed(false);
        assertTrue(callbackEventManager.hasCallbacked());
        int errorCode = 0;

        client1.getConnectionEventCallback().onConnectionInterrupted(errorCode);
        verify(callbackEventManager, times(1)).runOnConnectionInterrupted(errorCode);
        verify(mockCallback1, times(1)).onConnectionInterrupted(errorCode);
        verify(mockCallback2, times(1)).onConnectionInterrupted(errorCode);

        client2.getConnectionEventCallback().onConnectionInterrupted(errorCode);
        // if a mqttClient has [n]*AwsIotMqttClient, the callbackEventManager.runOnConnectionInterrupted would
        // be called [n] times but the callbacks in the callbackEventManager.oneTimeCallbackEvents would be
        // executed once.
        verify(callbackEventManager, times(2)).runOnConnectionInterrupted(errorCode);
        verify(mockCallback1, times(1)).onConnectionInterrupted(errorCode);
        verify(mockCallback2, times(1)).onConnectionInterrupted(errorCode);

        // When the connections are interrupted, callbackEventManager.hasCallBacked was set back to False,
        // meaning the oneTimeCallbackEvent is needed to executed when the connection back online.
        assertFalse(callbackEventManager.hasCallbacked());
    }

    @Test
    void GIVEN_no_topic_subscribed_WHEN_connection_interrupt_and_resume_THEN_no_resub_task_submitted()
            throws ExecutionException, InterruptedException {
        ExecutorService mockExecutor = mock(ExecutorService.class);
        AwsIotMqttClient client = new AwsIotMqttClient(() -> builder, (x) -> null, "testClient", mockTopic,
                callbackEventManager, mockExecutor, ses);
        when(connection.connect()).thenReturn(CompletableFuture.completedFuture(false));
        when(connection.disconnect()).thenReturn(CompletableFuture.completedFuture(null));
        when(builder.withConnectionEventCallbacks(events.capture())).thenReturn(builder);
        when(builder.build()).thenReturn(connection);

        client.connect().get();
        events.getValue().onConnectionInterrupted(0);
        events.getValue().onConnectionResumed(false);
        verify(mockExecutor, never()).submit(any(Runnable.class));
    }

    @Test
    void GIVEN_multiple_topics_subscribed_WHEN_reconnect_THEN_resubscribe_to_topics()
            throws InterruptedException, ExecutionException, TimeoutException {
        // setup mocks
        AwsIotMqttClient.setSubscriptionRetryMillis(500);
        AwsIotMqttClient.setWaitTimeJitterMaxMillis(10);
        AwsIotMqttClient client = new AwsIotMqttClient(() -> builder, (x) -> null, "testClient", mockTopic,
                callbackEventManager, executorService, ses);

        when(connection.connect()).thenReturn(CompletableFuture.completedFuture(false));
        when(connection.disconnect()).thenReturn(CompletableFuture.completedFuture(null));
        when(connection.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(builder.withConnectionEventCallbacks(events.capture())).thenReturn(builder);
        when(builder.build()).thenReturn(connection);

        // subscribe to topics A, B, C
        client.subscribe("A", QualityOfService.AT_LEAST_ONCE).get();
        client.subscribe("B", QualityOfService.AT_LEAST_ONCE).get();
        client.subscribe("C", QualityOfService.AT_LEAST_ONCE).get();
        assertTrue(client.connected());
        assertEquals(3, client.subscriptionCount());

        client.reconnect();

        // verify with some timeout to allow thread to spin up etc.
        verify(connection, timeout(VERIFY_TIMEOUT_MILLIS).times(2)).subscribe(eq("A"), any());
        verify(connection, timeout(VERIFY_TIMEOUT_MILLIS).times(2)).subscribe(eq("B"), any());
        verify(connection, timeout(VERIFY_TIMEOUT_MILLIS).times(2)).subscribe(eq("C"), any());
    }

    @Test
    void GIVEN_multiple_topics_subscribed_WHEN_connection_interrupted_and_resumed_THEN_resubscribe_to_topics(
            ExtensionContext context) throws ExecutionException, InterruptedException {
        // setup mocks
        ignoreExceptionUltimateCauseOfType(context, Exception.class);
        AwsIotMqttClient.setSubscriptionRetryMillis(500);
        AwsIotMqttClient.setWaitTimeJitterMaxMillis(1);
        AwsIotMqttClient client = new AwsIotMqttClient(() -> builder, (x) -> null, "testClient", mockTopic,
                callbackEventManager, executorService, ses);

        when(connection.connect()).thenReturn(CompletableFuture.completedFuture(false));
        when(connection.disconnect()).thenReturn(CompletableFuture.completedFuture(null));
        when(connection.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(builder.withConnectionEventCallbacks(events.capture())).thenReturn(builder);
        when(builder.build()).thenReturn(connection);

        // subscribe to topics A, B, C
        client.subscribe("A", QualityOfService.AT_LEAST_ONCE).get();
        client.subscribe("B", QualityOfService.AT_LEAST_ONCE).get();
        client.subscribe("C", QualityOfService.AT_LEAST_ONCE).get();
        assertTrue(client.connected());
        assertEquals(3, client.subscriptionCount());

        // interrupt network and recover without session
        // should resubscribe to all 3 topics
        CompletableFuture<Integer> subFailFuture = new CompletableFuture<>();
        subFailFuture.completeExceptionally(new Exception());
        when(connection.subscribe(eq("B"), any())).thenReturn(subFailFuture);
        when(connection.subscribe(eq("C"), any())).thenReturn(subFailFuture);

        events.getValue().onConnectionInterrupted(0);
        events.getValue().onConnectionResumed(false);

        // verify with some timeout to allow thread to spin up etc.
        verify(connection, timeout(VERIFY_TIMEOUT_MILLIS).times(2)).subscribe(eq("A"), any());
        verify(connection, timeout(VERIFY_TIMEOUT_MILLIS).times(2)).subscribe(eq("B"), any());
        verify(connection, timeout(VERIFY_TIMEOUT_MILLIS).times(2)).subscribe(eq("C"), any());

        // resub A succeeded, should keep retrying failed ones
        verify(connection, timeout(VERIFY_TIMEOUT_MILLIS).times(3)).subscribe(eq("B"), any());
        verify(connection, timeout(VERIFY_TIMEOUT_MILLIS).times(3)).subscribe(eq("C"), any());
        verify(connection, timeout(VERIFY_TIMEOUT_MILLIS).times(2)).subscribe(eq("A"), any());

        // interrupt network and recover with session
        events.getValue().onConnectionInterrupted(0);
        events.getValue().onConnectionResumed(true);

        // should not resub A because session persisted, but retry the others
        verify(connection, timeout(VERIFY_TIMEOUT_MILLIS).times(4)).subscribe(eq("B"), any());
        verify(connection, timeout(VERIFY_TIMEOUT_MILLIS).times(4)).subscribe(eq("C"), any());
        verify(connection, timeout(VERIFY_TIMEOUT_MILLIS).times(2)).subscribe(eq("A"), any());

        // interrupt network and recover without session
        events.getValue().onConnectionInterrupted(0);
        events.getValue().onConnectionResumed(false);

        // should resubscribe to all 3 topics
        verify(connection, timeout(VERIFY_TIMEOUT_MILLIS).times(5)).subscribe(eq("B"), any());
        verify(connection, timeout(VERIFY_TIMEOUT_MILLIS).times(5)).subscribe(eq("C"), any());
        verify(connection, timeout(VERIFY_TIMEOUT_MILLIS).times(3)).subscribe(eq("A"), any());
    }
}
