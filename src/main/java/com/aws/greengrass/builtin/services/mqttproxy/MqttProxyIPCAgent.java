/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.mqttproxy;

import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.Permission;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.PublishRequest;
import com.aws.greengrass.mqttclient.SubscribeRequest;
import com.aws.greengrass.mqttclient.UnsubscribeRequest;
import com.aws.greengrass.util.Pair;
import com.aws.greengrass.util.Utils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractPublishToIoTCoreOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractSubscribeToIoTCoreOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractUnsubscribeFromIoTCoreOperationHandler;
import software.amazon.awssdk.aws.greengrass.model.IoTCoreMessage;
import software.amazon.awssdk.aws.greengrass.model.MQTTMessage;
import software.amazon.awssdk.aws.greengrass.model.PublishToIoTCoreRequest;
import software.amazon.awssdk.aws.greengrass.model.PublishToIoTCoreResponse;
import software.amazon.awssdk.aws.greengrass.model.QOS;
import software.amazon.awssdk.aws.greengrass.model.ServiceError;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToIoTCoreRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToIoTCoreResponse;
import software.amazon.awssdk.aws.greengrass.model.UnauthorizedError;
import software.amazon.awssdk.aws.greengrass.model.UnsubscribeFromIoTCoreRequest;
import software.amazon.awssdk.aws.greengrass.model.UnsubscribeFromIoTCoreResponse;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.StreamEventPublisher;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import javax.inject.Inject;

import static com.aws.greengrass.ipc.modules.MqttProxyIPCService.MQTT_PROXY_SERVICE_NAME;

public class MqttProxyIPCAgent {
    private static final Logger LOGGER = LogManager.getLogger(MqttProxyIPCAgent.class);
    private static final String SERVICE_KEY = "service";
    private static final String TOPIC_KEY = "topic";

    @Getter(AccessLevel.PACKAGE)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Pair<StreamEventPublisher<IoTCoreMessage>,
            Consumer<MqttMessage>>>> subscribeListeners = new ConcurrentHashMap<>();

    @Inject
    @Setter(AccessLevel.PACKAGE)
    private MqttClient mqttClient;

    @Inject
    @Setter(AccessLevel.PACKAGE)
    private AuthorizationHandler authorizationHandler;

    public PublishToIoTCoreOperationHandler getPublishToIoTCoreOperationHandler(
            OperationContinuationHandlerContext context) {
        return new PublishToIoTCoreOperationHandler(context);
    }

    public SubscribeToIoTCoreOperationHandler getSubscribeToIoTCoreOperationHandler(
            OperationContinuationHandlerContext context) {
        return new SubscribeToIoTCoreOperationHandler(context);
    }

    public UnsubscribeFromIoTCoreOperationHandler getUnsubscribeFromIoTCoreOperationHandler(
            OperationContinuationHandlerContext context) {
        return new UnsubscribeFromIoTCoreOperationHandler(context);
    }

    class PublishToIoTCoreOperationHandler extends GeneratedAbstractPublishToIoTCoreOperationHandler {

        private final String serviceName;

        protected PublishToIoTCoreOperationHandler(OperationContinuationHandlerContext context) {
            super(context);
            serviceName = context.getAuthenticationData().getIdentityLabel();
        }

        @Override
        protected void onStreamClosed() {

        }

        @SuppressWarnings("PMD.PreserveStackTrace")
        @Override
        public PublishToIoTCoreResponse handleRequest(PublishToIoTCoreRequest request) {
            String topic = request.getTopicName();

            try {
                doAuthorization(this.getOperationModelContext().getOperationName(), serviceName, topic);
            } catch (AuthorizationException e) {
                LOGGER.atError().cause(e).log(e.getMessage());
                throw new UnauthorizedError(String.format("Authorization failed with error %s:%s", e, e.getMessage()));
            }

            PublishRequest publishRequest = PublishRequest.builder().payload(request.getPayload()).topic(topic)
                    .retain(request.isRetain()).qos(getQualityOfServiceFromQOS(request.getQos())).build();
            CompletableFuture<Integer> future = mqttClient.publish(publishRequest);

            //TODO: replace this with a check that message is inserted in spooler queue
            try {
                future.get(mqttClient.getTimeout(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                LOGGER.atError().cause(e).kv(TOPIC_KEY, topic).kv(SERVICE_KEY, serviceName)
                        .log("Unable to publish to topic");
                throw new ServiceError(String.format("Publish to topic %s failed with error %s:%s", topic, e,
                        e.getMessage()));
            }

            return new PublishToIoTCoreResponse();
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {

        }
    }

    class SubscribeToIoTCoreOperationHandler extends GeneratedAbstractSubscribeToIoTCoreOperationHandler {

        private final String serviceName;

        private final ConcurrentHashMap<String, Pair<StreamEventPublisher<IoTCoreMessage>, Consumer<MqttMessage>>>
                serviceSubscribeListeners;

        private String subscribedTopic;

        protected SubscribeToIoTCoreOperationHandler(OperationContinuationHandlerContext context) {
            super(context);
            serviceName = context.getAuthenticationData().getIdentityLabel();
            serviceSubscribeListeners = subscribeListeners.computeIfAbsent(serviceName, k -> new ConcurrentHashMap<>());
        }

        @Override
        protected void onStreamClosed() {
            if (!Utils.isEmpty(subscribedTopic)) {
                serviceSubscribeListeners.computeIfPresent(subscribedTopic, (t, l) -> {
                    UnsubscribeRequest unsubscribeRequest = UnsubscribeRequest.builder().callback(l.getRight()).topic(t)
                            .build();
                    try {
                        mqttClient.unsubscribe(unsubscribeRequest);
                    } catch (ExecutionException | InterruptedException | TimeoutException e) {
                        LOGGER.atError().cause(e).kv(TOPIC_KEY, t).kv(SERVICE_KEY, serviceName)
                                .log("Stream closed but unable to unsubscribe from topic");
                        return l;
                    }

                    return null;
                });
            }

            subscribeListeners.computeIfPresent(serviceName, (s, listeners) -> {
                if (listeners.isEmpty()) {
                    return null;
                } else {
                    return listeners;
                }
            });
        }

        @SuppressWarnings("PMD.PreserveStackTrace")
        @Override
        public SubscribeToIoTCoreResponse handleRequest(SubscribeToIoTCoreRequest request) {
            String topic = request.getTopicName();

            try {
                doAuthorization(this.getOperationModelContext().getOperationName(), serviceName, topic);
            } catch (AuthorizationException e) {
                LOGGER.atError().cause(e).log(e.getMessage());
                throw new UnauthorizedError(String.format("Authorization failed with error %s:%s", e, e.getMessage()));
            }

            final ServiceError[] serviceError = new ServiceError[1];
            Pair<StreamEventPublisher<IoTCoreMessage>, Consumer<MqttMessage>> listener = serviceSubscribeListeners
                    .computeIfAbsent(topic, t -> {
                        Consumer<MqttMessage> cb = this::forwardToSubscriber;
                        SubscribeRequest subscribeRequest = SubscribeRequest.builder().callback(cb).topic(t)
                                .qos(getQualityOfServiceFromQOS(request.getQos())).build();

                        try {
                            mqttClient.subscribe(subscribeRequest);
                        } catch (ExecutionException | InterruptedException | TimeoutException e) {
                            LOGGER.atError().cause(e).kv(TOPIC_KEY, t).kv(SERVICE_KEY, serviceName)
                                    .log("Unable to subscribe to topic");
                            serviceError[0] = new ServiceError(String.format(
                                    "Subscribe to topic %s failed with error %s:%s", t, e, e.getMessage()));
                            return null;
                        }

                        subscribedTopic = t;
                        return new Pair<>(this, cb);
                    });

            if (listener == null) {
                throw serviceError[0];
            }

            return new SubscribeToIoTCoreResponse();
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {

        }

        private void forwardToSubscriber(MqttMessage message) {
            MQTTMessage mqttMessage = new MQTTMessage();
            mqttMessage.setTopicName(message.getTopic());
            mqttMessage.setPayload(message.getPayload());

            IoTCoreMessage iotCoreMessage = new IoTCoreMessage();
            iotCoreMessage.setMessage(mqttMessage);
            this.sendStreamEvent(iotCoreMessage);
        }
    }

    @SuppressFBWarnings(value = "SIC_INNER_SHOULD_BE_STATIC_NEEDS_THIS", justification = "Should not be static")
    class UnsubscribeFromIoTCoreOperationHandler extends GeneratedAbstractUnsubscribeFromIoTCoreOperationHandler {

        private final String serviceName;

        protected UnsubscribeFromIoTCoreOperationHandler(OperationContinuationHandlerContext context) {
            super(context);
            serviceName = context.getAuthenticationData().getIdentityLabel();
        }

        @Override
        protected void onStreamClosed() {

        }

        @SuppressWarnings("PMD.PreserveStackTrace")
        @Override
        public UnsubscribeFromIoTCoreResponse handleRequest(UnsubscribeFromIoTCoreRequest request) {
            String topic = request.getTopicName();

            ConcurrentHashMap<String, Pair<StreamEventPublisher<IoTCoreMessage>, Consumer<MqttMessage>>>
                    serviceSubscribeListeners = subscribeListeners.get(serviceName);

            if (serviceSubscribeListeners != null) {
                final ServiceError[] serviceError = new ServiceError[1];
                Pair<StreamEventPublisher<IoTCoreMessage>, Consumer<MqttMessage>> listener = serviceSubscribeListeners
                        .computeIfPresent(topic, (t, l) -> {
                    UnsubscribeRequest unsubscribeRequest = UnsubscribeRequest.builder().callback(l.getRight()).topic(t)
                            .build();
                    try {
                        mqttClient.unsubscribe(unsubscribeRequest);
                    } catch (ExecutionException | InterruptedException | TimeoutException e) {
                        LOGGER.atError().cause(e).kv(TOPIC_KEY, t).kv(SERVICE_KEY, serviceName)
                                .log("Unable to unsubscribe from topic");
                        serviceError[0] = new ServiceError(String.format(
                                "Unsubscribe from topic %s failed with error %s:%s", t, e, e.getMessage()));
                        return l;
                    }

                    l.getLeft().closeStream();
                    return null;
                });

                if (listener != null) {
                    throw serviceError[0];
                }

                subscribeListeners.computeIfPresent(serviceName, (s, listeners) -> {
                    if (listeners.isEmpty()) {
                        return null;
                    } else {
                        return listeners;
                    }
                });
            }

            return new UnsubscribeFromIoTCoreResponse();
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {

        }
    }

    private QualityOfService getQualityOfServiceFromQOS(QOS qos) {
        if (qos == QOS.AT_LEAST_ONCE) {
            return QualityOfService.AT_LEAST_ONCE;
        } else if (qos == QOS.AT_MOST_ONCE) {
            return QualityOfService.AT_MOST_ONCE;
        }
        return QualityOfService.AT_LEAST_ONCE; //default value
    }

    private void doAuthorization(String opName, String serviceName, String topic) throws AuthorizationException {
        authorizationHandler.isAuthorized(
                MQTT_PROXY_SERVICE_NAME,
                Permission.builder()
                        .principal(serviceName)
                        .operation(opName)
                        .resource(topic)
                        .build());
    }
}
