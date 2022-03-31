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
import software.amazon.awssdk.aws.greengrass.model.InvalidArgumentsError;
import software.amazon.awssdk.aws.greengrass.model.JsonMessage;
import software.amazon.awssdk.aws.greengrass.model.MessageContext;
import software.amazon.awssdk.aws.greengrass.model.PublishToTopicRequest;
import software.amazon.awssdk.aws.greengrass.model.PublishToTopicResponse;
import software.amazon.awssdk.aws.greengrass.model.ReceiveMode;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToTopicRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToTopicResponse;
import software.amazon.awssdk.aws.greengrass.model.SubscriptionResponseMessage;
import software.amazon.awssdk.aws.greengrass.model.UnauthorizedError;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.StreamEventPublisher;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import javax.inject.Inject;

import static com.aws.greengrass.ipc.common.ExceptionUtil.translateExceptions;
import static com.aws.greengrass.ipc.modules.PubSubIPCService.PUB_SUB_SERVICE_NAME;

public class PubSubIPCEventStreamAgent {
    private static final Logger log = LogManager.getLogger(PubSubIPCEventStreamAgent.class);
    private static final String COMPONENT_NAME = "componentName";
    private static final String MQTT_SINGLELEVEL_WILDCARD = "+";
    private static final String MQTT_MULTILEVEL_WILDCARD = "#";
    private static final String GLOB_WILDCARD = "*";
    private static final ObjectMapper SERIALIZER = new ObjectMapper();
    @Getter(AccessLevel.PACKAGE)
    private final SubscriptionTrie<SubscriptionCallback> listeners = new SubscriptionTrie<>();

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
        SubscribeRequest subscribeRequest =
                SubscribeRequest.builder().topic(topic).callback(cb).serviceName(serviceName).receiveMode(null).build();
        subscribe(subscribeRequest);
    }

    /**
     * Handle the subscription request from internal plugin services given a SubscribeRequest.
     *
     * @param subscribeRequest request
     */
    public void subscribe(SubscribeRequest subscribeRequest) {
        handleSubscribeToTopicRequest(subscribeRequest);
    }

    /**
     * Unsubscribe from a topic for internal plugin services.
     *
     * @param topic       topic name.
     * @param cb          callback to remove from subscription
     * @param serviceName name of the service unsubscribing.
     */
    public void unsubscribe(String topic, Consumer<PublishEvent> cb, String serviceName) {
        SubscribeRequest subscribeRequest =
                SubscribeRequest.builder().topic(topic).callback(cb).serviceName(serviceName).receiveMode(null).build();
        unsubscribe(subscribeRequest);
    }

    /**
     * Unsubscribe from a topic for internal plugin services with given a SubscribeRequest.
     *
     * @param subscribeRequest request
     */
    public void unsubscribe(SubscribeRequest subscribeRequest) {
        handleUnsubscribeToTopicRequest(subscribeRequest);
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

    @SuppressWarnings("PMD.PreserveStackTrace")
    private PublishToTopicResponse handlePublishToTopicRequest(String topic, String serviceName,
                                                               Optional<Map<String, Object>> jsonMessage,
                                                               Optional<byte[]> binaryMessage) {
        if (topic == null) {
            throw new InvalidArgumentsError("Publish topic must not be null");
        }
        if (topic.contains(MQTT_SINGLELEVEL_WILDCARD) || topic.contains(MQTT_MULTILEVEL_WILDCARD)
                || topic.contains(GLOB_WILDCARD)) {
            throw new InvalidArgumentsError("Publish topic must not contain a wildcard.");
        }
        Set<SubscriptionCallback> contexts = listeners.get(topic);
        if (contexts == null || contexts.isEmpty()) {
            log.atDebug().kv(COMPONENT_NAME, serviceName).log("No one subscribed to topic {}. Returning.", topic);
            // Still technically successful, just no one was subscribed
            return new PublishToTopicResponse();
        }
        Set<Object> cbs = new HashSet<>();
        contexts.forEach(context -> {
            // With RECEIVE_MESSAGES_FROM_OTHERS mode, message will not be sent back to its source component.
            if (serviceName.equals(context.getSourceComponent()) && ReceiveMode.RECEIVE_MESSAGES_FROM_OTHERS
                    .equals(context.getReceiveMode())) {
                log.atTrace().kv(COMPONENT_NAME, serviceName)
                        .log("Message will not be sent back on topic {} in {} mode", topic,
                                context.getReceiveMode().getValue());
            } else {
                cbs.add(context.getCallback());
            }
        });
        SubscriptionResponseMessage message = new SubscriptionResponseMessage();
        PublishEvent publishedEvent = PublishEvent.builder().topic(topic).build();
        MessageContext messageContext = new MessageContext().withTopic(topic);
        if (jsonMessage.isPresent()) {
            JsonMessage message1 = new JsonMessage();
            message1.setMessage(jsonMessage.get());
            message1.setContext(messageContext);
            message.setJsonMessage(message1);
            try {
                publishedEvent.setPayload(SERIALIZER.writeValueAsBytes(jsonMessage.get()));
            } catch (JsonProcessingException e) {
                log.atError().cause(e).kv(COMPONENT_NAME, serviceName).log("Unable to serialize JSON message.");
                throw new InvalidArgumentsError("Unable to serialize payload as JSON");
            }
        }
        if (binaryMessage.isPresent()) {
            BinaryMessage binaryMessage1 = new BinaryMessage();
            binaryMessage1.setMessage(binaryMessage.get());
            binaryMessage1.setContext(messageContext);
            message.setBinaryMessage(binaryMessage1);
            publishedEvent.setPayload(binaryMessage.get());
        }

        cbs.forEach(context -> {
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

    private void handleSubscribeToTopicRequest(SubscribeRequest subscribeRequest) {
        // TODO: [P32540011]: All IPC service requests need input validation
        String topic = subscribeRequest.getTopic();
        validateSubTopic(topic);
        SubscriptionCallback subscriptionCallback =
                convertToSubscriptionCallback(topic, subscribeRequest.getServiceName(),
                        subscribeRequest.getReceiveMode(), subscribeRequest.getCallback());
        if (listeners.add(subscribeRequest.getTopic(), subscriptionCallback)) {
            log.atDebug().kv(COMPONENT_NAME, subscribeRequest.getServiceName()).log("Subscribed to topic {}", topic);
        }
    }

    private void handleUnsubscribeToTopicRequest(SubscribeRequest subscribeRequest) {
        String topic = subscribeRequest.getTopic();
        SubscriptionCallback subscriptionCallback =
                convertToSubscriptionCallback(topic, subscribeRequest.getServiceName(),
                        subscribeRequest.getReceiveMode(), subscribeRequest.getCallback());
        if (listeners.remove(topic, subscriptionCallback)) {
            log.atDebug().kv(COMPONENT_NAME, subscribeRequest.getServiceName())
                    .log("Unsubscribed from topic {}", topic);
        }
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
            return translateExceptions(() -> {
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
            });
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
        private SubscribeRequest request;

        protected SubscribeToTopicOperationHandler(OperationContinuationHandlerContext context) {
            super(context);
            serviceName = context.getAuthenticationData().getIdentityLabel();
        }

        @Override
        protected void onStreamClosed() {
            if (Utils.isNotEmpty(subscribeTopic)) {
                handleUnsubscribeToTopicRequest(request);
            }
        }

        @SuppressWarnings("PMD.PreserveStackTrace")
        @Override
        public SubscribeToTopicResponse handleRequest(SubscribeToTopicRequest subscribeRequest) {
            return translateExceptions(() -> {
                try {
                    doAuthorization(this.getOperationModelContext().getOperationName(), serviceName,
                            subscribeRequest.getTopic());
                } catch (AuthorizationException e) {
                    throw new UnauthorizedError(e.getMessage());
                }
                subscribeTopic = subscribeRequest.getTopic();
                request = SubscribeRequest.builder().topic(subscribeTopic).serviceName(serviceName)
                        .receiveMode(subscribeRequest.getReceiveMode()).callback(this).build();
                handleSubscribeToTopicRequest(request);
                return new SubscribeToTopicResponse();
            });
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {
            // NA
        }
    }

    private void validateSubTopic(String topic) {
        if (Utils.isEmpty(topic)) {
            throw new InvalidArgumentsError("Subscribe topic must not be empty");
        }
    }

    private ReceiveMode validateReceiveMode(String topic, ReceiveMode receiveMode) {
        if (receiveMode != null) {
            return receiveMode;
        }
        // If no receiveMode is set and the topic contains wildcard, use RECEIVE_MESSAGES_FROM_OTHERS
        // Otherwise, default is RECEIVE_ALL_MESSAGES
        if (SubscriptionTrie.isWildcard(topic)) {
            return ReceiveMode.RECEIVE_MESSAGES_FROM_OTHERS;
        } else {
            return ReceiveMode.RECEIVE_ALL_MESSAGES;
        }
    }

    private SubscriptionCallback convertToSubscriptionCallback(String topic, String serviceName,
                                                               ReceiveMode receiveMode, Object handler) {
        ReceiveMode validatedReceiveMode = validateReceiveMode(topic, receiveMode);
        return new SubscriptionCallback(serviceName, validatedReceiveMode, handler);
    }

    private void doAuthorization(String opName, String serviceName, String topic) throws AuthorizationException {
        authorizationHandler.isAuthorized(PUB_SUB_SERVICE_NAME,
                Permission.builder().principal(serviceName).operation(opName).resource(topic).build());
    }
}
