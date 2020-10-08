package com.aws.greengrass.builtin.services.pubsub;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class PubSubIPCEventStreamAgentTest {
    private static final String TEST_SERVICE = "TestService";
    private static final String TEST_TOPIC = "TestTopic";
    private static final String TEST_SOURCE = "TestSource";

    @Mock
    OperationContinuationHandlerContext mockContext;
    @Mock
    AuthenticationData mockAuthenticationData;
    @Captor
    ArgumentCaptor<SubscriptionResponseMessage> subscriptionResponseMessageCaptor;

    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private PubSubIPCEventStreamAgent pubSubIPCEventStreamAgent;

    @BeforeEach
    public void setup() {
        when(mockContext.getContinuation()).thenReturn(mock(ServerConnectionContinuation.class));
        when(mockContext.getAuthenticationData()).thenReturn(mockAuthenticationData);
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_SERVICE);
        pubSubIPCEventStreamAgent = new PubSubIPCEventStreamAgent();
        pubSubIPCEventStreamAgent.setExecutor(executorService);
    }

    @Test
    void GIVEN_subscribe_topic_from_one_source_WHEN_subscribe_THEN_added_to_only_one_service_listeners() {
        SubscribeToTopicRequest subscribeToTopicRequest = new SubscribeToTopicRequest();
        subscribeToTopicRequest.setTopic(TEST_TOPIC);
        subscribeToTopicRequest.setSource(TEST_SOURCE);
        try (PubSubIPCEventStreamAgent.SubscribeToTopicOperationHandler subscribeToTopicHandler =
                     pubSubIPCEventStreamAgent.getSubscribeToTopicHandler(mockContext)) {
            SubscribeToTopicResponse subscribeToTopicResponse =
                    subscribeToTopicHandler.handleRequest(subscribeToTopicRequest);
            assertNotNull(subscribeToTopicResponse);

            assertTrue(pubSubIPCEventStreamAgent.getParticularSourcesListeners().containsKey(TEST_TOPIC));
            assertTrue(pubSubIPCEventStreamAgent.getAllSourcesListeners().isEmpty());
            assertEquals(1, pubSubIPCEventStreamAgent.getParticularSourcesListeners().get(TEST_TOPIC).size());
            assertTrue(pubSubIPCEventStreamAgent.getParticularSourcesListeners().get(TEST_TOPIC).containsKey(TEST_SOURCE));
            assertEquals(1,
                    pubSubIPCEventStreamAgent.getParticularSourcesListeners().get(TEST_TOPIC).get(TEST_SOURCE).size());
        }
    }

    @Test
    void GIVEN_subscribe_topic_to_all_sources_WHEN_subscribe_THEN_added_all_services_listeners() {
        SubscribeToTopicRequest subscribeToTopicRequest = new SubscribeToTopicRequest();
        subscribeToTopicRequest.setTopic(TEST_TOPIC);
        try (PubSubIPCEventStreamAgent.SubscribeToTopicOperationHandler subscribeToTopicHandler =
                     pubSubIPCEventStreamAgent.getSubscribeToTopicHandler(mockContext)) {
            SubscribeToTopicResponse subscribeToTopicResponse =
                    subscribeToTopicHandler.handleRequest(subscribeToTopicRequest);
            assertNotNull(subscribeToTopicResponse);

            assertTrue(pubSubIPCEventStreamAgent.getAllSourcesListeners().containsKey(TEST_TOPIC));
            assertTrue(pubSubIPCEventStreamAgent.getParticularSourcesListeners().isEmpty());
            assertEquals(1, pubSubIPCEventStreamAgent.getAllSourcesListeners().get(TEST_TOPIC).size());
        }
    }

    @Test
    void GIVEN_subscribed_to_topic_from_all_sources_WHEN_publish_THEN_publishes_message() throws InterruptedException {
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

            TimeUnit.SECONDS.sleep(2);

            assertNotNull(subscriptionResponseMessageCaptor.getValue());

            SubscriptionResponseMessage message = subscriptionResponseMessageCaptor.getValue();
            assertNull(message.getJsonMessage());
            assertNotNull(message.getBinaryMessage());
            assertEquals("ABCD", new String(message.getBinaryMessage().getMessage()));
        }
    }

    @Test
    void GIVEN_subscribed_to_topic_from_one_sources_WHEN_publish_from_that_source_THEN_publishes_message()
            throws InterruptedException {
        StreamEventPublisher publisher = mock(StreamEventPublisher.class);
        Set<Object> set = new HashSet<>();
        set.add(publisher);
        Map<String, Set<Object>> map = new ConcurrentHashMap<>();
        map.put(TEST_SERVICE, set);
        pubSubIPCEventStreamAgent.getParticularSourcesListeners().put(TEST_TOPIC, map);
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

            TimeUnit.SECONDS.sleep(2);

            assertNotNull(subscriptionResponseMessageCaptor.getValue());

            SubscriptionResponseMessage message = subscriptionResponseMessageCaptor.getValue();
            assertNull(message.getJsonMessage());
            assertNotNull(message.getBinaryMessage());
            assertEquals("ABCD", new String(message.getBinaryMessage().getMessage()));
        }
    }
}
