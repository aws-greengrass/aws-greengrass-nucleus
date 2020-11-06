/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.pubsub;

import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.Permission;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.OrderedExecutorService;
import com.aws.greengrass.util.Utils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.Getter;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractPublishToTopicOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractSubscribeToTopicOperationHandler;
import software.amazon.awssdk.aws.greengrass.model.BinaryMessage;
import software.amazon.awssdk.aws.greengrass.model.JsonMessage;
import software.amazon.awssdk.aws.greengrass.model.PublishToTopicRequest;
import software.amazon.awssdk.aws.greengrass.model.PublishToTopicResponse;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToTopicRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToTopicResponse;
import software.amazon.awssdk.aws.greengrass.model.SubscriptionResponseMessage;
import software.amazon.awssdk.aws.greengrass.model.UnauthorizedError;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.StreamEventPublisher;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.inject.Inject;

import static com.aws.greengrass.ipc.modules.PubSubIPCService.PUB_SUB_SERVICE_NAME;

public class PubSubIPCEventStreamAgent {
    private static final Logger log = LogManager.getLogger(PubSubIPCEventStreamAgent.class);
    private static final String COMPONENT_NAME = "componentName";
    private static final ObjectMapper SERIALIZER = new ObjectMapper();
    @Getter(AccessLevel.PACKAGE)
    private final Map<String, Set<Object>> listeners = new ConcurrentHashMap<>();

    private final OrderedExecutorService orderedExecutorService;
    private final AuthorizationHandler authorizationHandler;

    @Inject
    PubSubIPCEventStreamAgent(AuthorizationHandler authorizationHandler,
                              OrderedExecutorService orderedExecutorService) {
        this.authorizationHandler = authorizationHandler;
        this.orderedExecutorService = orderedExecutorService;
    }

    public SubscribeToTopicOperationHandler getSubscribeToTopicHandler(OperationContinuationHandlerContext context) {
        return new SubscribeToTopicOperationHandler(context);
    }

    public PublishToTopicOperationHandler getPublishToTopicHandler(OperationContinuationHandlerContext context) {
        return new PublishToTopicOperationHandler(context);
    }

    /**
     * Handle the subscription request from internal plugin services.
     *
     * @param topic       topic name.
     * @param cb          callback to be called for each published message
     * @param serviceName name of the service subscribing.
     */
    public void subscribe(String topic, Consumer<PublishEvent> cb, String serviceName) {
        handleSubscribeToTopicRequest(topic, serviceName, cb);
    }

    /**
     * Unsubscribe from a topic for internal plugin services.
     *
     * @param topic       topic name.
     * @param cb          callback to remove from subscription
     * @param serviceName name of the service unsubscribing.
     */
    public void unsubscribe(String topic, Consumer<PublishEvent> cb, String serviceName) {
        log.atDebug().kv(COMPONENT_NAME, serviceName).log("Unsubscribing from topic {}", topic);
        listeners.computeIfPresent(topic, (s, objects) -> objects.remove(cb) && objects.isEmpty() ? null : objects);
    }

    /**
     * Publish a message to all subscribers.
     *
     * @param topic         publish topic.
     * @param binaryMessage Binary message to publish.
     * @param serviceName   name of the service publishing the message.
     * @return response
     */
    public PublishToTopicResponse publish(String topic, byte[] binaryMessage, String serviceName) {
        return handlePublishToTopicRequest(topic, serviceName, Optional.empty(), Optional.of(binaryMessage));
    }

    private PublishToTopicResponse handlePublishToTopicRequest(String topic, String serviceName,
                                                               Optional<Map<String, Object>> jsonMessage,
                                                               Optional<byte[]> binaryMessage) {
        Set<Object> contexts = listeners.get(topic);
        if (contexts == null || contexts.isEmpty()) {
            log.atDebug().kv(COMPONENT_NAME, serviceName).log("No one subscribed to topic {}. Returning.", topic);
            // Still technically successful, just no one was subscribed
            return new PublishToTopicResponse();
        }
        SubscriptionResponseMessage message = new SubscriptionResponseMessage();
        PublishEvent publishedEvent = PublishEvent.builder().topic(topic).build();
        if (jsonMessage.isPresent()) {
            JsonMessage message1 = new JsonMessage();
            message1.setMessage(jsonMessage.get());
            message.setJsonMessage(message1);
            try {
                publishedEvent.setPayload(SERIALIZER.writeValueAsBytes(jsonMessage.get()));
            } catch (JsonProcessingException e) {
                log.atError().cause(e).kv(COMPONENT_NAME, serviceName).log("Unable to serialize JSON message.");
            }
        }
        if (binaryMessage.isPresent()) {
            BinaryMessage binaryMessage1 = new BinaryMessage();
            binaryMessage1.setMessage(binaryMessage.get());
            message.setBinaryMessage(binaryMessage1);
            publishedEvent.setPayload(binaryMessage.get());
        }

        contexts.forEach(context -> {
            log.atDebug().kv(COMPONENT_NAME, serviceName).log("Sending publish event for topic {}", topic);
            if (context instanceof StreamEventPublisher) {
                StreamEventPublisher<SubscriptionResponseMessage> publisher =
                        (StreamEventPublisher<SubscriptionResponseMessage>) context;
                orderedExecutorService.execute(() -> publisher.sendStreamEvent(message), publisher);
            } else if (context instanceof Consumer) {
                Consumer<PublishEvent> consumer = (Consumer<PublishEvent>) context;
                orderedExecutorService.execute(() -> consumer.accept(publishedEvent), consumer);
            }
        });
        return new PublishToTopicResponse();
    }

    private void handleSubscribeToTopicRequest(String topic, String serviceName, Object handler) {
        // TODO: [P32540011]: All IPC service requests need input validation
        log.atInfo().kv(COMPONENT_NAME, serviceName).log("Subscribing to topic {}", topic);
        listeners.computeIfAbsent(topic, k -> ConcurrentHashMap.newKeySet()).add(handler);
    }


    class PublishToTopicOperationHandler extends GeneratedAbstractPublishToTopicOperationHandler {
        private final String serviceName;

        protected PublishToTopicOperationHandler(OperationContinuationHandlerContext context) {
            super(context);
            serviceName = context.getAuthenticationData().getIdentityLabel();
        }

        @Override
        protected void onStreamClosed() {
            // NA
        }

        @SuppressWarnings("PMD.PreserveStackTrace")
        @Override
        public PublishToTopicResponse handleRequest(PublishToTopicRequest publishRequest) {
            try {
                doAuthorization(this.getOperationModelContext().getOperationName(), serviceName,
                        publishRequest.getTopic());
            } catch (AuthorizationException e) {
                throw new UnauthorizedError(e.getMessage());
            }
            publishRequest.getPublishMessage().selfDesignateSetUnionMember();
            Optional<Map<String, Object>> jsonMessage = Optional.empty();
            if (publishRequest.getPublishMessage().getJsonMessage() != null) {
                jsonMessage = Optional.of(publishRequest.getPublishMessage().getJsonMessage().getMessage());
            }
            Optional<byte[]> binaryMessage = Optional.empty();
            if (publishRequest.getPublishMessage().getBinaryMessage() != null) {
                binaryMessage = Optional.of(publishRequest.getPublishMessage().getBinaryMessage().getMessage());
            }
            return handlePublishToTopicRequest(publishRequest.getTopic(), serviceName, jsonMessage, binaryMessage);
        }


        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {
            // NA
        }
    }

    class SubscribeToTopicOperationHandler extends GeneratedAbstractSubscribeToTopicOperationHandler {
        @Getter
        private final String serviceName;
        private String subscribeTopic;

        protected SubscribeToTopicOperationHandler(OperationContinuationHandlerContext context) {
            super(context);
            serviceName = context.getAuthenticationData().getIdentityLabel();
        }

        @Override
        protected void onStreamClosed() {
            if (Utils.isNotEmpty(subscribeTopic) && listeners.containsKey(subscribeTopic)) {
                listeners.computeIfPresent(subscribeTopic,
                        (s, objects) -> objects.remove(this) && objects.isEmpty() ? null : objects);
            }
        }

        @SuppressWarnings("PMD.PreserveStackTrace")
        @Override
        public SubscribeToTopicResponse handleRequest(SubscribeToTopicRequest subscribeRequest) {
            try {
                doAuthorization(this.getOperationModelContext().getOperationName(), serviceName,
                        subscribeRequest.getTopic());
            } catch (AuthorizationException e) {
                throw new UnauthorizedError(e.getMessage());
            }
            handleSubscribeToTopicRequest(subscribeRequest.getTopic(), serviceName, this);
            subscribeTopic = subscribeRequest.getTopic();
            return new SubscribeToTopicResponse();
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {
            // NA
        }
    }

    private void doAuthorization(String opName, String serviceName, String topic) throws AuthorizationException {
        authorizationHandler.isAuthorized(PUB_SUB_SERVICE_NAME,
                Permission.builder().principal(serviceName).operation(opName).resource(topic).build());
    }
}
