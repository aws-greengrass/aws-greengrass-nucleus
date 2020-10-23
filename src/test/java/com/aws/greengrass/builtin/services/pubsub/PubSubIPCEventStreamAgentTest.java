/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.greengrass.builtin.services.pubsub;

import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.Permission;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCService;
import software.amazon.awssdk.aws.greengrass.model.BinaryMessage;
import software.amazon.awssdk.aws.greengrass.model.PublishMessage;
import software.amazon.awssdk.aws.greengrass.model.PublishToTopicRequest;
import software.amazon.awssdk.aws.greengrass.model.PublishToTopicResponse;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToTopicRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToTopicResponse;
import software.amazon.awssdk.aws.greengrass.model.SubscriptionResponseMessage;
import software.amazon.awssdk.crt.eventstream.ServerConnectionContinuation;
import software.amazon.awssdk.eventstreamrpc.AuthenticationData;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.StreamEventPublisher;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.aws.greengrass.ipc.modules.PubSubIPCService.PUB_SUB_SERVICE_NAME;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class PubSubIPCEventStreamAgentTest {
    private static final String TEST_SERVICE = "TestService";
    private static final String TEST_TOPIC = "TestTopic";

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

    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private PubSubIPCEventStreamAgent pubSubIPCEventStreamAgent;

    @BeforeEach
    public void setup() {
        lenient().when(mockContext.getContinuation()).thenReturn(mock(ServerConnectionContinuation.class));
        lenient().when(mockContext.getAuthenticationData()).thenReturn(mockAuthenticationData);
        lenient().when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_SERVICE);
        pubSubIPCEventStreamAgent = new PubSubIPCEventStreamAgent();
        pubSubIPCEventStreamAgent.setExecutor(executorService);
        pubSubIPCEventStreamAgent.setAuthorizationHandler(authorizationHandler);
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

            assertTrue(pubSubIPCEventStreamAgent.getAllSourcesListeners().containsKey(TEST_TOPIC));
            assertEquals(1, pubSubIPCEventStreamAgent.getAllSourcesListeners().get(TEST_TOPIC).size());
        }
    }

    @Test
    void GIVEN_subscribed_to_topic_from_all_sources_WHEN_publish_THEN_publishes_message() throws InterruptedException, AuthorizationException {
        StreamEventPublisher publisher = mock(StreamEventPublisher.class);
        Set<Object> set = new HashSet<>();
        set.add(publisher);
        pubSubIPCEventStreamAgent.getAllSourcesListeners().put(TEST_TOPIC, set);
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
        }
    }

    @Test
    void GIVEN_subscribed_consumer_WHEN_publish_THEN_publishes_And_THEN_unsubscribes() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Consumer<SubscriptionResponseMessage> consumer = getConsumer(countDownLatch);
        pubSubIPCEventStreamAgent.subscribe(TEST_TOPIC, consumer);

        assertEquals(1, pubSubIPCEventStreamAgent.getAllSourcesListeners().size());
        assertTrue(pubSubIPCEventStreamAgent.getAllSourcesListeners().containsKey(TEST_TOPIC));
        assertEquals(1, pubSubIPCEventStreamAgent.getAllSourcesListeners().get(TEST_TOPIC).size());

        pubSubIPCEventStreamAgent.publish(TEST_TOPIC, Optional.empty(), Optional.of("ABCDEF".getBytes()));
        countDownLatch.await(10, TimeUnit.SECONDS);

        pubSubIPCEventStreamAgent.unsubscribe(TEST_TOPIC, consumer);
        assertEquals(0, pubSubIPCEventStreamAgent.getAllSourcesListeners().size());
    }

    private static Consumer<SubscriptionResponseMessage> getConsumer(CountDownLatch cdl) {
        return subscriptionResponseMessage -> cdl.countDown();
    }
}
