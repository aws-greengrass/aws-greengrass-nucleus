/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.greengrass.builtin.services.pubsub;

import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.Permission;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Utils;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
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

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import javax.inject.Inject;

import static com.aws.greengrass.ipc.modules.PubSubIPCService.PUB_SUB_SERVICE_NAME;

public class PubSubIPCEventStreamAgent {
    private static final Logger log = LogManager.getLogger(PubSubIPCEventStreamAgent.class);
    private static final String SERVICE_NAME = "service-name";
    @Getter(AccessLevel.PACKAGE)
    private final Map<String, Set<Object>> allSourcesListeners = new ConcurrentHashMap<>();

    @Inject
    @Setter(AccessLevel.PACKAGE)
    private ExecutorService executor;

    @Inject
    @Setter(AccessLevel.PACKAGE)
    private AuthorizationHandler authorizationHandler;

    public SubscribeToTopicOperationHandler getSubscribeToTopicHandler(
            OperationContinuationHandlerContext context) {
        return new SubscribeToTopicOperationHandler(context);
    }

    public PublishToTopicOperationHandler getPublishToTopicHandler(OperationContinuationHandlerContext context) {
        return new PublishToTopicOperationHandler(context);
    }

    /**
     * Handle the subscription request from the user.
     *
     * @param topic topic name.
     * @param cb    callback to be called for each published message
     */
    public void subscribe(String topic, Consumer<SubscriptionResponseMessage> cb) {
        handleSubscribeToTopicRequest(topic, "", cb);
    }

    /**
     * Unsubscribe from a topic.
     *
     * @param topic topic name.
     * @param cb    callback to remove from subscription
     */
    public void unsubscribe(String topic, Consumer<SubscriptionResponseMessage> cb) {
        log.debug("Unsubscribing from topic {}", topic);
        if (allSourcesListeners.containsKey(topic)) {
            allSourcesListeners.get(topic).remove(cb);
        }
        if (allSourcesListeners.get(topic).isEmpty()) {
            allSourcesListeners.remove(topic);
        }
    }

    /**
     * Publish a message to all subscribers.
     *
     * @param topic         publish topic.
     * @param jsonMessage   JSON message to publish.
     * @param binaryMessage Binary message to publish.
     * @return response
     */
    public PublishToTopicResponse publish(String topic,
                                          Optional<Map<String, Object>> jsonMessage,
                                          Optional<byte[]> binaryMessage) {
        return handlePublishToTopicRequest(topic, "", jsonMessage, binaryMessage);
    }

    private PublishToTopicResponse handlePublishToTopicRequest(String topic,
                                                               String serviceName,
                                                               Optional<Map<String, Object>> jsonMessage,
                                                               Optional<byte[]> binaryMessage) {
        if (!allSourcesListeners.containsKey(topic)) {
            log.atDebug().log("No one subscribed to topic {}. Returning.", topic);
            // Still technically successful, just no one was subscribed
            return new PublishToTopicResponse();
        }

        executor.execute(() -> {
            Set<Object> contexts = new HashSet<>();
            if (allSourcesListeners.containsKey(topic)) {
                contexts.addAll(allSourcesListeners.get(topic));
            }

            SubscriptionResponseMessage message = new SubscriptionResponseMessage();
            if (jsonMessage.isPresent()) {
                JsonMessage message1 = new JsonMessage();
                message1.setMessage(jsonMessage.get());
                message.setJsonMessage(message1);
            }
            if (binaryMessage.isPresent()) {
                BinaryMessage binaryMessage1 = new BinaryMessage();
                binaryMessage1.setMessage(binaryMessage.get());
                message.setBinaryMessage(binaryMessage1);
            }

            contexts.forEach(context -> {
                log.atDebug().kv(SERVICE_NAME, serviceName)
                        .log("Sending publish event for topic {}", topic);
                if (context instanceof StreamEventPublisher) {
                    ((StreamEventPublisher<SubscriptionResponseMessage>) context).sendStreamEvent(message);
                } else if (context instanceof Consumer) {
                    ((Consumer<SubscriptionResponseMessage>) context).accept(message);
                }
            });
        });
        return new PublishToTopicResponse();
    }

    private void handleSubscribeToTopicRequest(String topic, String serviceName,
                                               Object handler) {
        // TODO: Input validation. P32540011
        log.atInfo().kv(SERVICE_NAME, serviceName)
                .log("Subscribing to topic {}, {}", topic, serviceName);
        allSourcesListeners.computeIfAbsent(topic, k -> new HashSet<>()).add(handler);
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
            if (Utils.isNotEmpty(subscribeTopic) && allSourcesListeners.containsKey(subscribeTopic)) {
                if (allSourcesListeners.get(subscribeTopic).remove(this)) {
                    log.atDebug().kv(SERVICE_NAME, serviceName)
                            .log("Client disconnected, removing subscription {}", subscribeTopic);
                }
                if (allSourcesListeners.get(subscribeTopic).isEmpty()) {
                    allSourcesListeners.remove(subscribeTopic);
                }
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
        authorizationHandler.isAuthorized(
                PUB_SUB_SERVICE_NAME,
                Permission.builder()
                        .principal(serviceName)
                        .operation(opName)
                        .resource(topic)
                        .build());
    }
}
