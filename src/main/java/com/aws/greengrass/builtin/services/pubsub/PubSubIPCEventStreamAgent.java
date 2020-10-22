package com.aws.greengrass.builtin.services.pubsub;

import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.Permission;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.ipc.services.pubsub.PubSubUnsubscribeRequest;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Utils;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractPublishToTopicOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractSubscribeToTopicOperationHandler;
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
    @Getter(AccessLevel.PACKAGE)
    private final Map<String, Map<String, Set<Object>>> particularSourcesListeners = new ConcurrentHashMap<>();

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
     * @param subscribeRequest subscribe request
     * @param cb               callback to be called for each published message
     */
    public void subscribe(SubscribeToTopicRequest subscribeRequest, Consumer<SubscriptionResponseMessage> cb) {
        // TODO: What models should we use?
        handleSubscribeToTopicRequest(subscribeRequest, "", cb);
    }

    /**
     * Unsubscribe from a topic.
     *
     * @param unsubscribeRequest request containing the topic to unsubscribe from
     * @param cb                 callback to remove from subscription
     */
    public void unsubscribe(PubSubUnsubscribeRequest unsubscribeRequest, Consumer<SubscriptionResponseMessage> cb) {
        log.debug("Unsubscribing from topic {}", unsubscribeRequest.getTopic());
        if (allSourcesListeners.containsKey(unsubscribeRequest.getTopic())) {
            allSourcesListeners.get(unsubscribeRequest.getTopic()).remove(cb);
        }
        if (particularSourcesListeners.containsKey(unsubscribeRequest.getTopic())) {
            particularSourcesListeners.get(unsubscribeRequest.getTopic()).values()
                    .forEach(listeners -> listeners.remove(cb));
        }
    }

    /**
     * Publish a message to all subscribers.
     *
     * @param publishRequest publish request
     * @return response
     */
    public PublishToTopicResponse publish(PublishToTopicRequest publishRequest) {
        return handlePublishToTopicRequest(publishRequest, "");
    }

    private PublishToTopicResponse handlePublishToTopicRequest(PublishToTopicRequest publishRequest,
                                                               String serviceName) {
        if (!allSourcesListeners.containsKey(publishRequest.getTopic())
                && !particularSourcesListeners.containsKey(publishRequest.getTopic())) {
            log.atDebug().log("No one subscribed to topic {}. Returning.", publishRequest.getTopic());
            // Still technically successful, just no one was subscribed
            return new PublishToTopicResponse();
        }

        executor.execute(() -> {
            Set<Object> contexts = new HashSet<>();
            if (allSourcesListeners.containsKey(publishRequest.getTopic())) {
                contexts.addAll(allSourcesListeners.get(publishRequest.getTopic()));
            }

            if (particularSourcesListeners.containsKey(publishRequest.getTopic())
                    && particularSourcesListeners.get(publishRequest.getTopic()).containsKey(serviceName)) {
                contexts.addAll(particularSourcesListeners.get(publishRequest.getTopic()).get(serviceName));
            }
            SubscriptionResponseMessage message = new SubscriptionResponseMessage();
            publishRequest.getPublishMessage().selfDesignateSetUnionMember();
            if (publishRequest.getPublishMessage().getJsonMessage() != null) {
                message.setJsonMessage(publishRequest.getPublishMessage().getJsonMessage());
            }
            if (publishRequest.getPublishMessage().getBinaryMessage() != null) {
                message.setBinaryMessage(publishRequest.getPublishMessage().getBinaryMessage());
            }

            contexts.forEach(context -> {
                log.atDebug().kv(SERVICE_NAME, serviceName)
                        .log("Sending publish event {}", publishRequest);
                if (context instanceof StreamEventPublisher) {
                    ((StreamEventPublisher<SubscriptionResponseMessage>) context).sendStreamEvent(message);
                } else if (context instanceof Consumer) {
                    ((Consumer<SubscriptionResponseMessage>) context).accept(message);
                }
            });
        });
        return new PublishToTopicResponse();
    }

    private void handleSubscribeToTopicRequest(SubscribeToTopicRequest subscribeRequest, String serviceName,
                                               Object handler) {
        // TODO: Input validation. https://sim.amazon.com/issues/P32540011
        log.atInfo().kv(SERVICE_NAME, serviceName)
                .log("Subscribing to topic {}, {}", subscribeRequest.getTopic(), serviceName);
        if (Utils.isEmpty(subscribeRequest.getSource())) {
            allSourcesListeners.computeIfAbsent(subscribeRequest.getTopic(), k -> new HashSet<>()).add(handler);
        } else {
            particularSourcesListeners
                    .computeIfAbsent(subscribeRequest.getTopic(), k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(subscribeRequest.getSource(), k -> ConcurrentHashMap.newKeySet())
                    .add(handler);
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
            try {
                doAuthorization(this.getOperationModelContext().getOperationName(), serviceName,
                        publishRequest.getTopic());
            } catch (AuthorizationException e) {
                throw new UnauthorizedError(e.getMessage());
            }

            return handlePublishToTopicRequest(publishRequest, serviceName);
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
        private String subscribeToSource;

        protected SubscribeToTopicOperationHandler(OperationContinuationHandlerContext context) {
            super(context);
            serviceName = context.getAuthenticationData().getIdentityLabel();
        }

        @Override
        protected void onStreamClosed() {
            if (!Utils.isEmpty(subscribeTopic) && allSourcesListeners.containsKey(subscribeTopic)) {
                if (allSourcesListeners.get(subscribeTopic).remove(this)) {
                    log.atDebug().kv(SERVICE_NAME, serviceName)
                            .log("Client disconnected, removing subscription {}", subscribeTopic);
                }
                if (allSourcesListeners.get(subscribeTopic).isEmpty()) {
                    allSourcesListeners.remove(subscribeTopic);
                }
            } else if (!Utils.isEmpty(subscribeTopic) && !Utils.isEmpty(subscribeToSource)
                    && particularSourcesListeners.containsKey(subscribeTopic)) {
                if (particularSourcesListeners.get(subscribeTopic).get(subscribeToSource).remove(this)) {
                    log.atDebug().kv(SERVICE_NAME, serviceName)
                            .log("Client disconnected, removing subscription {} for source",
                                    subscribeTopic, subscribeToSource);
                }
                if (particularSourcesListeners.get(subscribeTopic).get(subscribeToSource).isEmpty()) {
                    particularSourcesListeners.remove(subscribeTopic);
                }
                if (particularSourcesListeners.get(subscribeTopic).isEmpty()) {
                    particularSourcesListeners.remove(subscribeTopic);
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
            handleSubscribeToTopicRequest(subscribeRequest, serviceName, this);
            if (Utils.isNotEmpty(subscribeRequest.getSource())) {
                subscribeToSource = subscribeRequest.getSource();
            }
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
