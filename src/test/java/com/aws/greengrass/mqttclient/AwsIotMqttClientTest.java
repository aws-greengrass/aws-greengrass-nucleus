/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.mqttclient.v5.Publish;
import com.aws.greengrass.mqttclient.v5.QOS;
import com.aws.greengrass.mqttclient.v5.Subscribe;
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
import software.amazon.awssdk.crt.mqtt.MqttException;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
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
@ExtendWith({
        GGExtension.class, MockitoExtension.class
})
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
        AwsIotMqttClient client = new AwsIotMqttClient(() -> builder, (x) -> null, "A", 0, mockTopic,
                callbackEventManager, executorService, ses);
        client.disableRateLimiting();
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

        AwsIotMqttClient client = new AwsIotMqttClient(() -> builder, (x) -> null, "A", 0, mockTopic,
                callbackEventManager, executorService, ses);
        client.disableRateLimiting();
        assertThrows(ExecutionException.class, () -> {
            client.subscribe(Subscribe.builder().topic("test").build()).get();
        });
    }

    @Test
    void GIVEN_client_connection_throws_mqttexception_WHEN_subscribe_THEN_throws_exception() {
        when(connection.connect()).thenThrow(MqttException.class);
        doNothing().when(connection).onMessage(any());
        when(builder.build()).thenReturn(connection);

        AwsIotMqttClient client = new AwsIotMqttClient(() -> builder, (x) -> null, "A", 0, mockTopic,
                callbackEventManager, executorService, ses);
        client.disableRateLimiting();
        assertThrows(ExecutionException.class, () -> {
            client.subscribe(Subscribe.builder().topic("test").build()).get();
        });
    }

    @Test
    void GIVEN_individual_client_THEN_it_tracks_connection_state_correctly(ExtensionContext ec)
            throws ExecutionException, InterruptedException, TimeoutException {
        ignoreExceptionUltimateCauseOfType(ec, RejectedExecutionException.class);
        when(mockTopic.findOrDefault(any(), any())).thenReturn(1000);
        when(connection.connect()).thenReturn(CompletableFuture.completedFuture(false));
        when(connection.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(connection.disconnect()).thenReturn(CompletableFuture.completedFuture(null));
        when(builder.withConnectionEventCallbacks(events.capture())).thenReturn(builder);

        AwsIotMqttClient client = new AwsIotMqttClient(() -> builder, (x) -> null, "A", 0, mockTopic,
                callbackEventManager, executorService, ses);
        client.disableRateLimiting();
        assertFalse(client.connected());

        when(builder.build()).thenReturn(connection);
        // Call subscribe which will cause the client to connect
        client.subscribe(Subscribe.builder().topic("A").build());

        assertTrue(client.connected());
        client.reconnect(100);
        verify(connection, times(2)).close();
        verify(connection, times(2)).disconnect();
        assertTrue(client.connected());
        verify(connection, timeout(1_000).times(2)).subscribe(any(), any());

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
    void GIVEN_individual_client_THEN_it_tracks_subscriptions_correctly(ExtensionContext context)
            throws ExecutionException, InterruptedException, TimeoutException {
        ignoreExceptionOfType(context, CompletionException.class);
        when(connection.connect()).thenReturn(CompletableFuture.completedFuture(false));
        when(connection.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(connection.unsubscribe(any())).thenReturn(CompletableFuture.completedFuture(0));
        when(connection.disconnect()).thenReturn(CompletableFuture.completedFuture(null));
        when(builder.withConnectionEventCallbacks(events.capture())).thenReturn(builder);

        AwsIotMqttClient client = new AwsIotMqttClient(() -> builder, (x) -> null, "A", 0, mockTopic,
                callbackEventManager, executorService, ses);
        client.disableRateLimiting();
        when(builder.build()).thenReturn(connection);

        Map<String, QualityOfService> expectedSubs = new HashMap<>();
        expectedSubs.put("A", QualityOfService.AT_LEAST_ONCE);
        client.subscribe(Subscribe.builder().topic("A").qos(QOS.AT_LEAST_ONCE).build()).get();
        assertEquals(expectedSubs, client.getSubscriptionTopics());

        client.reconnect(100);
        assertEquals(expectedSubs, client.getSubscriptionTopics());

        expectedSubs.put("B", QualityOfService.AT_MOST_ONCE);
        client.subscribe(Subscribe.builder().topic("B").qos(QOS.AT_MOST_ONCE).build()).get();

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

        AwsIotMqttClient client = new AwsIotMqttClient(() -> builder, (x) -> null, "A", 0, mockTopic,
                callbackEventManager, executorService, ses);
        client.disableRateLimiting();
        when(builder.build()).thenReturn(connection);
        client.publish(Publish.builder().topic("A").payload(new byte[0]).build()).get();
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
        AwsIotMqttClient client = new AwsIotMqttClient(() -> builder, (x) -> null, "A", 0, mockTopic,
                callbackEventManager, executorService, ses);
        client.disableRateLimiting();

        //initial connect, client connects, disconnects and then connects
        client.subscribe(Subscribe.builder().topic("A").build());
        verify(connection, times(2)).connect();
        verify(connection, times(1)).disconnect();

        //client connected, no change in connect/disconnect calls
        client.subscribe(Subscribe.builder().topic("B").build());
        verify(connection, times(2)).connect();
        verify(connection, times(1)).disconnect();
        //client calls disconnect
        client.disconnect().get(client.getTimeout(), TimeUnit.MILLISECONDS);
        verify(connection, times(2)).disconnect();

        //client calls connect
        client.subscribe(Subscribe.builder().topic("C").build());
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

        AwsIotMqttClient client = new AwsIotMqttClient(() -> builder, (x) -> null, "A", 0, mockTopic,
                callbackEventManager, executorService, ses);
        client.disableRateLimiting();
        assertFalse(client.connected());

        when(builder.build()).thenReturn(connection);
        // Call subscribe which will cause the client to connect
        assertThrows(ExecutionException.class, () -> client.subscribe(Subscribe.builder().topic("A").build()).get());

        assertFalse(client.connected());

        when(connection.connect()).thenReturn(CompletableFuture.completedFuture(false));
        client.subscribe(Subscribe.builder().topic("A").build()).get();
        assertTrue(client.connected());
    }

    @Test
    void GIVEN_multiple_callbacks_in_callbackEventManager_WHEN_connections_are_resumed_THEN_oneTimeCallbacks_would_be_executed_once() {

        AwsIotMqttClient client1 = new AwsIotMqttClient(() -> builder, (x) -> null, "A", 0, mockTopic,
                callbackEventManager, executorService, ses);
        client1.disableRateLimiting();
        AwsIotMqttClient client2 = new AwsIotMqttClient(() -> builder, (x) -> null, "B", 0, mockTopic,
                callbackEventManager, executorService, ses);
        client2.disableRateLimiting();
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
    void GIVEN_multiple_callbacks_in_callbackEventManager_WHEN_connections_are_interrupted_purposely_THEN_no_callbacks_are_called() {
        AwsIotMqttClient client1 = new AwsIotMqttClient(() -> builder, (x) -> null, "A", 0, mockTopic,
                callbackEventManager, executorService, ses);
        client1.disableRateLimiting();
        AwsIotMqttClient client2 = new AwsIotMqttClient(() -> builder, (x) -> null, "B", 0, mockTopic,
                callbackEventManager, executorService, ses);
        client2.disableRateLimiting();
        callbackEventManager.runOnConnectionResumed(false);
        assertTrue(callbackEventManager.hasCallbacked());
        int errorCode = 0;

        client1.getConnectionEventCallback().onConnectionInterrupted(errorCode);
        verify(callbackEventManager, never()).runOnConnectionInterrupted(errorCode);
        verify(mockCallback1, never()).onConnectionInterrupted(errorCode);
        verify(mockCallback2, never()).onConnectionInterrupted(errorCode);

        client2.getConnectionEventCallback().onConnectionInterrupted(errorCode);
        verify(callbackEventManager, never()).runOnConnectionInterrupted(errorCode);
        verify(mockCallback1, never()).onConnectionInterrupted(errorCode);
        verify(mockCallback2, never()).onConnectionInterrupted(errorCode);

        assertTrue(callbackEventManager.hasCallbacked());
    }

    @Test
    void GIVEN_multiple_callbacks_in_callbackEventManager_WHEN_connections_are_interrupted_THEN_oneTimeCallbacks_would_be_executed_once() {

        AwsIotMqttClient client1 = new AwsIotMqttClient(() -> builder, (x) -> null, "A", 0, mockTopic,
                callbackEventManager, executorService, ses);
        client1.disableRateLimiting();
        AwsIotMqttClient client2 = new AwsIotMqttClient(() -> builder, (x) -> null, "B", 0, mockTopic,
                callbackEventManager, executorService, ses);
        client2.disableRateLimiting();
        callbackEventManager.runOnConnectionResumed(false);
        assertTrue(callbackEventManager.hasCallbacked());
        int errorCode = 1;

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
        AwsIotMqttClient client = new AwsIotMqttClient(() -> builder, (x) -> null, "testClient", 0, mockTopic,
                callbackEventManager, mockExecutor, ses);
        client.disableRateLimiting();
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
        AwsIotMqttClient client = new AwsIotMqttClient(() -> builder, (x) -> null, "testClient", 0, mockTopic,
                callbackEventManager, executorService, ses);
        client.disableRateLimiting();

        when(connection.connect()).thenReturn(CompletableFuture.completedFuture(false));
        when(connection.disconnect()).thenAnswer((a) -> {
            CompletableFuture<Void> cf = new CompletableFuture<>();
            // Complete the future in a different thread, just like the SDK would do
            ses.schedule(() -> cf.complete(null), 50, TimeUnit.MILLISECONDS);
            return cf;
        });
        when(connection.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(builder.withConnectionEventCallbacks(events.capture())).thenReturn(builder);
        when(builder.build()).thenReturn(connection);

        // subscribe to topics A, B, C
        client.subscribe(Subscribe.builder().topic("A").build()).get();
        client.subscribe(Subscribe.builder().topic("B").build()).get();
        client.subscribe(Subscribe.builder().topic("C").build()).get();
        assertTrue(client.connected());
        assertEquals(3, client.subscriptionCount());

        client.reconnect(100);

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
        AwsIotMqttClient client = new AwsIotMqttClient(() -> builder, (x) -> null, "testClient", 0, mockTopic,
                callbackEventManager, executorService, ses);
        client.disableRateLimiting();

        when(connection.connect()).thenReturn(CompletableFuture.completedFuture(false));
        when(connection.disconnect()).thenReturn(CompletableFuture.completedFuture(null));
        when(connection.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(0));
        when(builder.withConnectionEventCallbacks(events.capture())).thenReturn(builder);
        when(builder.build()).thenReturn(connection);

        // subscribe to topics A, B, C
        client.subscribe(Subscribe.builder().topic("A").build()).get();
        client.subscribe(Subscribe.builder().topic("B").build()).get();
        client.subscribe(Subscribe.builder().topic("C").build()).get();
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

    @Test
    void GIVEN_mqttClient_has_subscribed_to_any_topic_or_has_inprgoress_subscritpion_WHEN_isConnectionClosable_THEN_returns_false() {
        AwsIotMqttClient.setSubscriptionRetryMillis(500);
        AwsIotMqttClient.setWaitTimeJitterMaxMillis(1);
        AwsIotMqttClient client = new AwsIotMqttClient(() -> builder, (x) -> null, "testClient", 0, mockTopic,
                callbackEventManager, executorService, ses);
        client.disableRateLimiting();

        client.getSubscriptionTopics().put("A/B", QualityOfService.AT_LEAST_ONCE);
        assertFalse(client.isConnectionClosable());

        client.getSubscriptionTopics().clear();
        client.getInprogressSubscriptions().incrementAndGet();

        assertFalse(client.isConnectionClosable());

        client.getInprogressSubscriptions().decrementAndGet();
        assertTrue(client.isConnectionClosable());
    }

    @Test
    void GIVEN_mqttClient_has_no_subscribed_topic_or_any_inprgoress_subscritpion_WHEN_isConnectionClosable_THEN_returns_true() {
        AwsIotMqttClient.setSubscriptionRetryMillis(500);
        AwsIotMqttClient.setWaitTimeJitterMaxMillis(1);
        AwsIotMqttClient client = new AwsIotMqttClient(() -> builder, (x) -> null, "testClient", 0, mockTopic,
                callbackEventManager, executorService, ses);
        client.disableRateLimiting();
        assertTrue(client.isConnectionClosable());
    }
}
