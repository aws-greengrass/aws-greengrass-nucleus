/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.pubsub;

import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.Permission;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.OrderedExecutorService;
import org.hamcrest.collection.IsMapContaining;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCService;
import software.amazon.awssdk.aws.greengrass.model.BinaryMessage;
import software.amazon.awssdk.aws.greengrass.model.InvalidArgumentsError;
import software.amazon.awssdk.aws.greengrass.model.JsonMessage;
import software.amazon.awssdk.aws.greengrass.model.PublishMessage;
import software.amazon.awssdk.aws.greengrass.model.PublishToTopicRequest;
import software.amazon.awssdk.aws.greengrass.model.PublishToTopicResponse;
import software.amazon.awssdk.aws.greengrass.model.ReceiveMode;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToTopicRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToTopicResponse;
import software.amazon.awssdk.aws.greengrass.model.SubscriptionResponseMessage;
import software.amazon.awssdk.crt.eventstream.ServerConnectionContinuation;
import software.amazon.awssdk.eventstreamrpc.AuthenticationData;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.StreamEventPublisher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.aws.greengrass.ipc.modules.PubSubIPCService.PUB_SUB_SERVICE_NAME;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class PubSubIPCEventStreamAgentTest {
    private static final String TEST_SERVICE = "TestService";
    private static final String TEST_TOPIC = "TestTopic";
    private static final String TEST_WILDCARD_TOPIC = "Test/+/Topic/#";

    @Mock
    OperationContinuationHandlerContext mockContext;
    @Mock
    AuthenticationData mockAuthenticationData;
    @Mock
    AuthorizationHandler authorizationHandler;
    @Captor
    ArgumentCaptor<SubscriptionResponseMessage> subscriptionResponseMessageCaptor;
    @Captor
    ArgumentCaptor<Permission> permissionArgumentCaptor;

    final ExecutorService pool = Executors.newCachedThreadPool();
    private final OrderedExecutorService orderedExecutorService =
            new OrderedExecutorService(pool);
    private PubSubIPCEventStreamAgent pubSubIPCEventStreamAgent;

    @BeforeEach
    public void setup() {
        lenient().when(mockContext.getContinuation()).thenReturn(mock(ServerConnectionContinuation.class));
        lenient().when(mockContext.getAuthenticationData()).thenReturn(mockAuthenticationData);
        lenient().when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_SERVICE);
        pubSubIPCEventStreamAgent = new PubSubIPCEventStreamAgent(authorizationHandler, orderedExecutorService);
    }

    @AfterEach
    void afterEach() {
        pool.shutdownNow();
    }

    @Test
    void GIVEN_subscribe_topic_to_all_sources_WHEN_subscribe_THEN_added_all_services_listeners() throws AuthorizationException {
        SubscribeToTopicRequest subscribeToTopicRequest = new SubscribeToTopicRequest();
        subscribeToTopicRequest.setTopic(TEST_TOPIC);
        try (PubSubIPCEventStreamAgent.SubscribeToTopicOperationHandler subscribeToTopicHandler =
                     pubSubIPCEventStreamAgent.getSubscribeToTopicHandler(mockContext)) {
            SubscribeToTopicResponse subscribeToTopicResponse =
                    subscribeToTopicHandler.handleRequest(subscribeToTopicRequest);
            assertNotNull(subscribeToTopicResponse);

            verify(authorizationHandler).isAuthorized(eq(PUB_SUB_SERVICE_NAME), permissionArgumentCaptor.capture());
            Permission capturedPermission = permissionArgumentCaptor.getValue();
            assertThat(capturedPermission.getOperation(), is(GreengrassCoreIPCService.SUBSCRIBE_TO_TOPIC));
            assertThat(capturedPermission.getPrincipal(), is(TEST_SERVICE));
            assertThat(capturedPermission.getResource(), is(TEST_TOPIC));

            assertTrue(pubSubIPCEventStreamAgent.getListeners().containsKey(TEST_TOPIC));
            assertEquals(1, pubSubIPCEventStreamAgent.getListeners().get(TEST_TOPIC).size());
        }
    }

    @Test
    void GIVEN_subscribed_to_topic_from_all_sources_WHEN_publish_binary_message_THEN_publishes_message()
            throws InterruptedException, AuthorizationException {
        StreamEventPublisher publisher = mock(StreamEventPublisher.class);
        // Default is RECEIVE_ALL_MESSAGES if not set
        SubscriptionCallback cbs = SubscriptionCallback.builder().sourceComponent(TEST_SERVICE).callback(publisher).build();
        pubSubIPCEventStreamAgent.getListeners().add(TEST_TOPIC, cbs);
        when(publisher.sendStreamEvent(subscriptionResponseMessageCaptor.capture())).thenReturn(new CompletableFuture());

        PublishToTopicRequest publishToTopicRequest = new PublishToTopicRequest();
        publishToTopicRequest.setTopic(TEST_TOPIC);
        PublishMessage publishMessage = new PublishMessage();
        BinaryMessage binaryMessage = new BinaryMessage();
        binaryMessage.setMessage("ABCD".getBytes());
        publishMessage.setBinaryMessage(binaryMessage);
        publishToTopicRequest.setPublishMessage(publishMessage);

        try (PubSubIPCEventStreamAgent.PublishToTopicOperationHandler publishToTopicHandler =
                     pubSubIPCEventStreamAgent.getPublishToTopicHandler(mockContext)) {
            PublishToTopicResponse publishToTopicResponse = publishToTopicHandler.handleRequest(publishToTopicRequest);
            assertNotNull(publishToTopicResponse);

            verify(authorizationHandler).isAuthorized(eq(PUB_SUB_SERVICE_NAME), permissionArgumentCaptor.capture());
            Permission capturedPermission = permissionArgumentCaptor.getValue();
            assertThat(capturedPermission.getOperation(), is(GreengrassCoreIPCService.PUBLISH_TO_TOPIC));
            assertThat(capturedPermission.getPrincipal(), is(TEST_SERVICE));
            assertThat(capturedPermission.getResource(), is(TEST_TOPIC));

            TimeUnit.SECONDS.sleep(2);

            assertNotNull(subscriptionResponseMessageCaptor.getValue());

            SubscriptionResponseMessage message = subscriptionResponseMessageCaptor.getValue();
            assertNull(message.getJsonMessage());
            assertNotNull(message.getBinaryMessage());
            assertEquals("ABCD", new String(message.getBinaryMessage().getMessage()));
            assertEquals(TEST_TOPIC, message.getBinaryMessage().getContext().getTopic());
        }
    }

    @Test
    void GIVEN_subscribed_to_topic_with_receive_others_mode_WHEN_publish_binary_message_from_same_component_THEN_not_publishes_message()
            throws InterruptedException {
        StreamEventPublisher publisher = mock(StreamEventPublisher.class);
        SubscriptionCallback cbs = SubscriptionCallback.builder().callback(publisher).sourceComponent(TEST_SERVICE)
                .receiveMode(ReceiveMode.RECEIVE_MESSAGES_FROM_OTHERS).build();
        pubSubIPCEventStreamAgent.getListeners().add(TEST_TOPIC, cbs);

        PublishToTopicRequest publishToTopicRequest = new PublishToTopicRequest();
        publishToTopicRequest.setTopic(TEST_TOPIC);
        PublishMessage publishMessage = new PublishMessage();
        BinaryMessage binaryMessage = new BinaryMessage();
        binaryMessage.setMessage("ABCD".getBytes());
        publishMessage.setBinaryMessage(binaryMessage);
        publishToTopicRequest.setPublishMessage(publishMessage);

        try (PubSubIPCEventStreamAgent.PublishToTopicOperationHandler publishToTopicHandler =
                     pubSubIPCEventStreamAgent.getPublishToTopicHandler(mockContext)) {
            PublishToTopicResponse publishToTopicResponse = publishToTopicHandler.handleRequest(publishToTopicRequest);
            assertNotNull(publishToTopicResponse);

            TimeUnit.SECONDS.sleep(2);
            verify(publisher, never()).sendStreamEvent(any());
        }
    }

    @Test
    void GIVEN_subscribed_to_topic_from_all_sources_WHEN_publish_json_message_THEN_publishes_message()
            throws InterruptedException, AuthorizationException {
        StreamEventPublisher publisher = mock(StreamEventPublisher.class);
        SubscriptionCallback cbs = SubscriptionCallback.builder().callback(publisher).build();
        pubSubIPCEventStreamAgent.getListeners().add(TEST_TOPIC, cbs);
        when(publisher.sendStreamEvent(subscriptionResponseMessageCaptor.capture())).thenReturn(new CompletableFuture());

        PublishToTopicRequest publishToTopicRequest = new PublishToTopicRequest();
        publishToTopicRequest.setTopic(TEST_TOPIC);
        PublishMessage publishMessage = new PublishMessage();
        JsonMessage jsonMessage = new JsonMessage();
        Map<String, Object> message = new HashMap<>();
        message.putIfAbsent("SomeKey", "SomValue");
        jsonMessage.setMessage(message);
        publishMessage.setJsonMessage(jsonMessage);
        publishToTopicRequest.setPublishMessage(publishMessage);

        try (PubSubIPCEventStreamAgent.PublishToTopicOperationHandler publishToTopicHandler =
                     pubSubIPCEventStreamAgent.getPublishToTopicHandler(mockContext)) {
            PublishToTopicResponse publishToTopicResponse = publishToTopicHandler.handleRequest(publishToTopicRequest);
            assertNotNull(publishToTopicResponse);

            verify(authorizationHandler).isAuthorized(eq(PUB_SUB_SERVICE_NAME), permissionArgumentCaptor.capture());
            Permission capturedPermission = permissionArgumentCaptor.getValue();
            assertThat(capturedPermission.getOperation(), is(GreengrassCoreIPCService.PUBLISH_TO_TOPIC));
            assertThat(capturedPermission.getPrincipal(), is(TEST_SERVICE));
            assertThat(capturedPermission.getResource(), is(TEST_TOPIC));

            TimeUnit.SECONDS.sleep(2);

            assertNotNull(subscriptionResponseMessageCaptor.getValue());

            SubscriptionResponseMessage responseMessage = subscriptionResponseMessageCaptor.getValue();
            assertNotNull(responseMessage.getJsonMessage());
            assertNull(responseMessage.getBinaryMessage());
            assertThat(responseMessage.getJsonMessage().getMessage(), IsMapContaining.hasEntry("SomeKey", "SomValue"));
            assertEquals(TEST_TOPIC, responseMessage.getJsonMessage().getContext().getTopic());
        }
    }

    @Test
    void GIVEN_subscribed_to_wildcard_topic_from_all_sources_WHEN_publish_binary_message_to_subtopic_THEN_publishes_message_and_gets_topic()
            throws InterruptedException, AuthorizationException {
        StreamEventPublisher publisher = mock(StreamEventPublisher.class);
        SubscriptionCallback cbs =
                SubscriptionCallback.builder().callback(publisher).receiveMode(ReceiveMode.RECEIVE_ALL_MESSAGES).sourceComponent(TEST_SERVICE).build();
        pubSubIPCEventStreamAgent.getListeners().add(TEST_WILDCARD_TOPIC, cbs);
        when(publisher.sendStreamEvent(subscriptionResponseMessageCaptor.capture()))
                .thenReturn(new CompletableFuture());

        PublishToTopicRequest publishToTopicRequest = new PublishToTopicRequest();
        publishToTopicRequest.setTopic("Test/A/Topic/B/C");
        PublishMessage publishMessage = new PublishMessage();
        BinaryMessage binaryMessage = new BinaryMessage();
        binaryMessage.setMessage("ABCD".getBytes());
        publishMessage.setBinaryMessage(binaryMessage);
        publishToTopicRequest.setPublishMessage(publishMessage);

        try (PubSubIPCEventStreamAgent.PublishToTopicOperationHandler publishToTopicHandler = pubSubIPCEventStreamAgent
                .getPublishToTopicHandler(mockContext)) {
            PublishToTopicResponse publishToTopicResponse = publishToTopicHandler.handleRequest(publishToTopicRequest);
            assertNotNull(publishToTopicResponse);

            verify(authorizationHandler).isAuthorized(eq(PUB_SUB_SERVICE_NAME), permissionArgumentCaptor.capture());
            Permission capturedPermission = permissionArgumentCaptor.getValue();
            assertThat(capturedPermission.getOperation(), is(GreengrassCoreIPCService.PUBLISH_TO_TOPIC));
            assertThat(capturedPermission.getPrincipal(), is(TEST_SERVICE));
            assertThat(capturedPermission.getResource(), containsString("Test/A/Topic/"));

            TimeUnit.SECONDS.sleep(2);

            assertNotNull(subscriptionResponseMessageCaptor.getValue());

            SubscriptionResponseMessage message = subscriptionResponseMessageCaptor.getValue();
            assertNull(message.getJsonMessage());
            assertNotNull(message.getBinaryMessage());
            assertEquals("ABCD", new String(message.getBinaryMessage().getMessage()));
            assertEquals("Test/A/Topic/B/C", message.getBinaryMessage().getContext().getTopic());
        }
    }

    @Test
    void GIVEN_subscribed_to_wildcard_topic_with_receive_others_mode_WHEN_publish_binary_message_to_subtopic_from_same_component_THEN_not_publishes_message()
            throws InterruptedException {
        StreamEventPublisher publisher = mock(StreamEventPublisher.class);
        SubscriptionCallback cbs =
                SubscriptionCallback.builder().callback(publisher).receiveMode(ReceiveMode.RECEIVE_MESSAGES_FROM_OTHERS).sourceComponent(TEST_SERVICE).build();
        pubSubIPCEventStreamAgent.getListeners().add(TEST_WILDCARD_TOPIC, cbs);

        PublishToTopicRequest publishToTopicRequest = new PublishToTopicRequest();
        publishToTopicRequest.setTopic("Test/A/Topic/B/C");
        PublishMessage publishMessage = new PublishMessage();
        BinaryMessage binaryMessage = new BinaryMessage();
        binaryMessage.setMessage("ABCD".getBytes());
        publishMessage.setBinaryMessage(binaryMessage);
        publishToTopicRequest.setPublishMessage(publishMessage);

        try (PubSubIPCEventStreamAgent.PublishToTopicOperationHandler publishToTopicHandler = pubSubIPCEventStreamAgent
                .getPublishToTopicHandler(mockContext)) {
            PublishToTopicResponse publishToTopicResponse = publishToTopicHandler.handleRequest(publishToTopicRequest);
            assertNotNull(publishToTopicResponse);

            TimeUnit.SECONDS.sleep(2);
            verify(publisher, never()).sendStreamEvent(any());
        }
    }

    @Test
    void GIVEN_subscribed_to_wildcard_topic_with_no_receive_mode_WHEN_publish_binary_message_to_subtopic_from_same_component_THEN_not_publishes_message()
            throws InterruptedException {
        StreamEventPublisher publisher = mock(StreamEventPublisher.class);
        // Default is RECEIVE_MESSAGES_FROM_OTHERS if mode is not set for wildcard topic
        SubscriptionCallback cbs =
                SubscriptionCallback.builder().callback(publisher).receiveMode(ReceiveMode.RECEIVE_MESSAGES_FROM_OTHERS).sourceComponent(TEST_SERVICE).build();
        pubSubIPCEventStreamAgent.getListeners().add(TEST_WILDCARD_TOPIC, cbs);

        PublishToTopicRequest publishToTopicRequest = new PublishToTopicRequest();
        publishToTopicRequest.setTopic("Test/A/Topic/B/C");
        PublishMessage publishMessage = new PublishMessage();
        BinaryMessage binaryMessage = new BinaryMessage();
        binaryMessage.setMessage("ABCD".getBytes());
        publishMessage.setBinaryMessage(binaryMessage);
        publishToTopicRequest.setPublishMessage(publishMessage);

        try (PubSubIPCEventStreamAgent.PublishToTopicOperationHandler publishToTopicHandler = pubSubIPCEventStreamAgent
                .getPublishToTopicHandler(mockContext)) {
            PublishToTopicResponse publishToTopicResponse = publishToTopicHandler.handleRequest(publishToTopicRequest);
            assertNotNull(publishToTopicResponse);

            TimeUnit.SECONDS.sleep(2);
            verify(publisher, never()).sendStreamEvent(any());
        }
    }

    @Test
    void GIVEN_subscribed_to_topic_from_all_sources_WHEN_publish_many_json_message_THEN_publishes_message_inorder()
            throws InterruptedException, AuthorizationException {
        StreamEventPublisher publisher = mock(StreamEventPublisher.class);
        SubscriptionCallback cbs = SubscriptionCallback.builder().callback(publisher).build();
        pubSubIPCEventStreamAgent.getListeners().add(TEST_TOPIC, cbs);
        when(publisher.sendStreamEvent(subscriptionResponseMessageCaptor.capture())).thenReturn(new CompletableFuture());

        List<PublishToTopicRequest> publishToTopicRequests = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            PublishToTopicRequest publishToTopicRequest = new PublishToTopicRequest();
            publishToTopicRequest.setTopic(TEST_TOPIC);
            PublishMessage publishMessage = new PublishMessage();
            JsonMessage jsonMessage = new JsonMessage();
            Map<String, Object> message = new HashMap<>();
            message.putIfAbsent("SomeKey", i);
            jsonMessage.setMessage(message);
            publishMessage.setJsonMessage(jsonMessage);
            publishToTopicRequest.setPublishMessage(publishMessage);
            publishToTopicRequests.add(publishToTopicRequest);
        }

        try (PubSubIPCEventStreamAgent.PublishToTopicOperationHandler publishToTopicHandler =
                     pubSubIPCEventStreamAgent.getPublishToTopicHandler(mockContext)) {
            for (PublishToTopicRequest publishToTopicRequest: publishToTopicRequests) {
                PublishToTopicResponse publishToTopicResponse = publishToTopicHandler.handleRequest(publishToTopicRequest);
                assertNotNull(publishToTopicResponse);
            }

            verify(authorizationHandler, times(10)).isAuthorized(eq(PUB_SUB_SERVICE_NAME), permissionArgumentCaptor.capture());
            Permission capturedPermission = permissionArgumentCaptor.getValue();
            assertThat(capturedPermission.getOperation(), is(GreengrassCoreIPCService.PUBLISH_TO_TOPIC));
            assertThat(capturedPermission.getPrincipal(), is(TEST_SERVICE));
            assertThat(capturedPermission.getResource(), is(TEST_TOPIC));

            TimeUnit.SECONDS.sleep(2);

            assertNotNull(subscriptionResponseMessageCaptor.getAllValues());
            assertEquals(10, subscriptionResponseMessageCaptor.getAllValues().size());
            int i = 0;
            for (SubscriptionResponseMessage responseMessage : subscriptionResponseMessageCaptor.getAllValues()) {
                assertNotNull(responseMessage.getJsonMessage());
                assertNull(responseMessage.getBinaryMessage());
                assertThat(responseMessage.getJsonMessage().getMessage(), IsMapContaining.hasEntry("SomeKey", i));
                assertEquals(TEST_TOPIC, responseMessage.getJsonMessage().getContext().getTopic());
                i++;
            }
        }
    }

    @Test
    void GIVEN_subscribed_to_topic_from_all_sources_WHEN_publish_many_binary_message_THEN_publishes_message_inorder()
            throws InterruptedException, AuthorizationException {
        StreamEventPublisher publisher = mock(StreamEventPublisher.class);
        SubscriptionCallback cbs = SubscriptionCallback.builder().callback(publisher).build();
        pubSubIPCEventStreamAgent.getListeners().add(TEST_TOPIC, cbs);
        when(publisher.sendStreamEvent(subscriptionResponseMessageCaptor.capture())).thenReturn(new CompletableFuture());

        List<PublishToTopicRequest> publishToTopicRequests = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            PublishToTopicRequest publishToTopicRequest = new PublishToTopicRequest();
            publishToTopicRequest.setTopic(TEST_TOPIC);
            PublishMessage publishMessage = new PublishMessage();
            BinaryMessage binaryMessage = new BinaryMessage();
            binaryMessage.setMessage(String.valueOf(i).getBytes());
            publishMessage.setBinaryMessage(binaryMessage);
            publishToTopicRequest.setPublishMessage(publishMessage);
            publishToTopicRequests.add(publishToTopicRequest);
        }

        try (PubSubIPCEventStreamAgent.PublishToTopicOperationHandler publishToTopicHandler =
                     pubSubIPCEventStreamAgent.getPublishToTopicHandler(mockContext)) {
            for (PublishToTopicRequest publishToTopicRequest: publishToTopicRequests) {
                PublishToTopicResponse publishToTopicResponse = publishToTopicHandler.handleRequest(publishToTopicRequest);
                assertNotNull(publishToTopicResponse);
            }

            verify(authorizationHandler, times(10)).isAuthorized(eq(PUB_SUB_SERVICE_NAME), permissionArgumentCaptor.capture());
            Permission capturedPermission = permissionArgumentCaptor.getValue();
            assertThat(capturedPermission.getOperation(), is(GreengrassCoreIPCService.PUBLISH_TO_TOPIC));
            assertThat(capturedPermission.getPrincipal(), is(TEST_SERVICE));
            assertThat(capturedPermission.getResource(), is(TEST_TOPIC));

            TimeUnit.SECONDS.sleep(2);

            assertNotNull(subscriptionResponseMessageCaptor.getAllValues());
            assertEquals(10, subscriptionResponseMessageCaptor.getAllValues().size());
            int i = 0;
            for (SubscriptionResponseMessage responseMessage : subscriptionResponseMessageCaptor.getAllValues()) {
                assertNull(responseMessage.getJsonMessage());
                assertNotNull(responseMessage.getBinaryMessage());
                assertEquals(String.valueOf(i), new String(responseMessage.getBinaryMessage().getMessage()));
                assertEquals(TEST_TOPIC, responseMessage.getBinaryMessage().getContext().getTopic());
                i++;
            }
        }
    }

    @Test
    void GIVEN_subscribed_to_wildcard_topic_from_all_sources_WHEN_publish_many_binary_message_to_subtopics_THEN_publishes_message_and_gets_topics_inorder()
            throws InterruptedException, AuthorizationException {
        StreamEventPublisher publisher = mock(StreamEventPublisher.class);
        SubscriptionCallback cbs =
                SubscriptionCallback.builder().sourceComponent(TEST_SERVICE).callback(publisher).receiveMode(ReceiveMode.RECEIVE_ALL_MESSAGES).build();
        pubSubIPCEventStreamAgent.getListeners().add(TEST_WILDCARD_TOPIC, cbs);
        when(publisher.sendStreamEvent(subscriptionResponseMessageCaptor.capture()))
                .thenReturn(new CompletableFuture());

        List<PublishToTopicRequest> publishToTopicRequests = new ArrayList<>();
        String subTopic = "Test/A/Topic/%d";
        for (int i = 0; i < 10; i++) {
            PublishToTopicRequest publishToTopicRequest = new PublishToTopicRequest();
            String topic1 = String.format(subTopic, i);
            publishToTopicRequest.setTopic(topic1);
            PublishMessage publishMessage = new PublishMessage();
            BinaryMessage binaryMessage = new BinaryMessage();
            binaryMessage.setMessage(String.valueOf(i).getBytes());
            publishMessage.setBinaryMessage(binaryMessage);
            publishToTopicRequest.setPublishMessage(publishMessage);
            publishToTopicRequests.add(publishToTopicRequest);
        }

        try (PubSubIPCEventStreamAgent.PublishToTopicOperationHandler publishToTopicHandler = pubSubIPCEventStreamAgent
                .getPublishToTopicHandler(mockContext)) {
            for (PublishToTopicRequest publishToTopicRequest : publishToTopicRequests) {
                PublishToTopicResponse publishToTopicResponse =
                        publishToTopicHandler.handleRequest(publishToTopicRequest);
                assertNotNull(publishToTopicResponse);
            }

            verify(authorizationHandler, times(10))
                    .isAuthorized(eq(PUB_SUB_SERVICE_NAME), permissionArgumentCaptor.capture());
            Permission capturedPermission = permissionArgumentCaptor.getValue();
            assertThat(capturedPermission.getOperation(), is(GreengrassCoreIPCService.PUBLISH_TO_TOPIC));
            assertThat(capturedPermission.getPrincipal(), is(TEST_SERVICE));
            assertThat(capturedPermission.getResource(), containsString("Test/A/Topic/"));

            TimeUnit.SECONDS.sleep(2);

            assertNotNull(subscriptionResponseMessageCaptor.getAllValues());
            assertEquals(10, subscriptionResponseMessageCaptor.getAllValues().size());
            int i = 0;
            for (SubscriptionResponseMessage responseMessage : subscriptionResponseMessageCaptor.getAllValues()) {
                assertNull(responseMessage.getJsonMessage());
                assertNotNull(responseMessage.getBinaryMessage());
                assertEquals(String.valueOf(i), new String(responseMessage.getBinaryMessage().getMessage()));
                assertEquals(String.format(subTopic, i), responseMessage.getBinaryMessage().getContext().getTopic());
                i++;
            }
        }
    }

    @Test
    void GIVEN_subscribed_consumer_no_receive_mode_WHEN_publish_binary_message_THEN_publishes_And_THEN_unsubscribes()
            throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Consumer<PublishEvent> consumer = getConsumer(countDownLatch);
        // Default is RECEIVE_ALL_MESSAGES if not set
        pubSubIPCEventStreamAgent.subscribe(TEST_TOPIC, consumer, TEST_SERVICE);

        assertEquals(1, pubSubIPCEventStreamAgent.getListeners().size());
        assertTrue(pubSubIPCEventStreamAgent.getListeners().containsKey(TEST_TOPIC));
        assertEquals(1, pubSubIPCEventStreamAgent.getListeners().get(TEST_TOPIC).size());

        pubSubIPCEventStreamAgent.publish(TEST_TOPIC, "ABCDEF".getBytes(), TEST_SERVICE);
        assertTrue(countDownLatch.await(10, TimeUnit.SECONDS));

        pubSubIPCEventStreamAgent.unsubscribe(TEST_TOPIC, consumer, TEST_SERVICE);
        assertEquals(0, pubSubIPCEventStreamAgent.getListeners().size());
    }

    @Test
    void GIVEN_subscribed_consumer_receive_others_mode_WHEN_publish_binary_message_THEN_not_publishes_And_THEN_unsubscribes()
            throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Consumer<PublishEvent> consumer = getConsumer(countDownLatch);
        SubscribeRequest request =
                SubscribeRequest.builder().topic(TEST_TOPIC).callback(consumer).serviceName(TEST_SERVICE)
                        .receiveMode(ReceiveMode.RECEIVE_MESSAGES_FROM_OTHERS).build();
        pubSubIPCEventStreamAgent.subscribe(request);

        assertEquals(1, pubSubIPCEventStreamAgent.getListeners().size());
        assertTrue(pubSubIPCEventStreamAgent.getListeners().containsKey(TEST_TOPIC));
        assertEquals(1, pubSubIPCEventStreamAgent.getListeners().get(TEST_TOPIC).size());

        pubSubIPCEventStreamAgent.publish(TEST_TOPIC, "ABCDEF".getBytes(), TEST_SERVICE);
        assertFalse(countDownLatch.await(10, TimeUnit.SECONDS));

        pubSubIPCEventStreamAgent.unsubscribe(request);
        assertEquals(0, pubSubIPCEventStreamAgent.getListeners().size());
    }

    @Test
    void GIVEN_subscribed_consumer_receive_all_mode_WHEN_publish_binary_message_THEN_publishes_And_THEN_unsubscribes()
            throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Consumer<PublishEvent> consumer = getConsumer(countDownLatch);
        SubscribeRequest request =
                SubscribeRequest.builder().topic(TEST_TOPIC).callback(consumer).serviceName(TEST_SERVICE)
                        .receiveMode(ReceiveMode.RECEIVE_ALL_MESSAGES).build();
        pubSubIPCEventStreamAgent.subscribe(request);
        assertEquals(1, pubSubIPCEventStreamAgent.getListeners().size());
        assertTrue(pubSubIPCEventStreamAgent.getListeners().containsKey(TEST_TOPIC));
        assertEquals(1, pubSubIPCEventStreamAgent.getListeners().get(TEST_TOPIC).size());

        pubSubIPCEventStreamAgent.publish(TEST_TOPIC, "ABCDEF".getBytes(), TEST_SERVICE);
        assertTrue(countDownLatch.await(10, TimeUnit.SECONDS));

        pubSubIPCEventStreamAgent.unsubscribe(request);
        assertEquals(0, pubSubIPCEventStreamAgent.getListeners().size());
    }

    @Test
    void GIVEN_subscribed_consumer_WHEN_invalid_topic_THEN_throws() {
        Consumer<PublishEvent> consumer = mock(Consumer.class);
        assertThrows(InvalidArgumentsError.class, () -> pubSubIPCEventStreamAgent.subscribe("", consumer,
                TEST_SERVICE));
        assertThrows(InvalidArgumentsError.class, () -> pubSubIPCEventStreamAgent.subscribe(null, consumer,
                TEST_SERVICE));
    }

    @Test
    void GIVEN_subscribed_consumer_to_wildcard_and_no_receive_mode_WHEN_publish_to_subtopic_THEN_not_publishes_And_THEN_unsubscribes()
            throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Consumer<PublishEvent> consumer = getConsumer(countDownLatch);
        // Default is RECEIVE_MESSAGES_FROM_OTHERS if mode is not set for wildcard topic
        pubSubIPCEventStreamAgent.subscribe(TEST_WILDCARD_TOPIC, consumer, TEST_SERVICE);

        assertEquals(1, pubSubIPCEventStreamAgent.getListeners().size());
        assertTrue(pubSubIPCEventStreamAgent.getListeners().containsKey(TEST_WILDCARD_TOPIC));
        assertEquals(1, pubSubIPCEventStreamAgent.getListeners().get(TEST_WILDCARD_TOPIC).size());

        pubSubIPCEventStreamAgent.publish("Test/A/Topic/B/C", "ABCDEF".getBytes(), TEST_SERVICE);
        assertFalse(countDownLatch.await(10, TimeUnit.SECONDS));

        pubSubIPCEventStreamAgent.unsubscribe(TEST_WILDCARD_TOPIC, consumer, TEST_SERVICE);
        assertEquals(0, pubSubIPCEventStreamAgent.getListeners().size());
    }

    @Test
    void GIVEN_subscribed_consumer_to_wildcard_and_receive_all_mode_WHEN_publish_to_subtopic_THEN_publishes_And_THEN_unsubscribes()
            throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Consumer<PublishEvent> consumer = getConsumer(countDownLatch);
        SubscribeRequest request =
                SubscribeRequest.builder().topic(TEST_WILDCARD_TOPIC).callback(consumer).serviceName(TEST_SERVICE)
                        .receiveMode(ReceiveMode.RECEIVE_ALL_MESSAGES).build();
        pubSubIPCEventStreamAgent.subscribe(request);
        assertEquals(1, pubSubIPCEventStreamAgent.getListeners().size());
        assertTrue(pubSubIPCEventStreamAgent.getListeners().containsKey(TEST_WILDCARD_TOPIC));
        assertEquals(1, pubSubIPCEventStreamAgent.getListeners().get(TEST_WILDCARD_TOPIC).size());

        pubSubIPCEventStreamAgent.publish("Test/A/Topic/B/C", "ABCDEF".getBytes(), TEST_SERVICE);
        assertTrue(countDownLatch.await(10, TimeUnit.SECONDS));

        pubSubIPCEventStreamAgent.unsubscribe(request);
        assertEquals(0, pubSubIPCEventStreamAgent.getListeners().size());
    }

    @Test
    void GIVEN_subscribed_consumer_to_wildcard_and_receive_others_mode_WHEN_publish_to_subtopic_THEN_not_publishes_And_THEN_unsubscribes()
            throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Consumer<PublishEvent> consumer = getConsumer(countDownLatch);
        SubscribeRequest request =
                SubscribeRequest.builder().topic(TEST_WILDCARD_TOPIC).callback(consumer).serviceName(TEST_SERVICE)
                        .receiveMode(ReceiveMode.RECEIVE_MESSAGES_FROM_OTHERS).build();
        pubSubIPCEventStreamAgent.subscribe(request);

        assertEquals(1, pubSubIPCEventStreamAgent.getListeners().size());
        assertTrue(pubSubIPCEventStreamAgent.getListeners().containsKey(TEST_WILDCARD_TOPIC));
        assertEquals(1, pubSubIPCEventStreamAgent.getListeners().get(TEST_WILDCARD_TOPIC).size());

        pubSubIPCEventStreamAgent.publish("Test/A/Topic/B/C", "ABCDEF".getBytes(), TEST_SERVICE);
        assertFalse(countDownLatch.await(10, TimeUnit.SECONDS));

        pubSubIPCEventStreamAgent.unsubscribe(request);
        assertEquals(0, pubSubIPCEventStreamAgent.getListeners().size());
    }

    private static Consumer<PublishEvent> getConsumer(CountDownLatch cdl) {
        return subscriptionResponseMessage -> cdl.countDown();
    }
}
