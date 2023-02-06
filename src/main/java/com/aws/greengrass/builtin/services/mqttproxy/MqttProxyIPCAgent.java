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
import com.aws.greengrass.mqttclient.MqttRequestException;
import com.aws.greengrass.mqttclient.spool.SpoolerStoreException;
import com.aws.greengrass.mqttclient.v5.Publish;
import com.aws.greengrass.mqttclient.v5.Subscribe;
import com.aws.greengrass.mqttclient.v5.Unsubscribe;
import com.aws.greengrass.mqttclient.v5.UserProperty;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Utils;
import lombok.AccessLevel;
import lombok.Setter;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractPublishToIoTCoreOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractSubscribeToIoTCoreOperationHandler;
import software.amazon.awssdk.aws.greengrass.model.InvalidArgumentsError;
import software.amazon.awssdk.aws.greengrass.model.IoTCoreMessage;
import software.amazon.awssdk.aws.greengrass.model.MQTTMessage;
import software.amazon.awssdk.aws.greengrass.model.PayloadFormat;
import software.amazon.awssdk.aws.greengrass.model.PublishToIoTCoreRequest;
import software.amazon.awssdk.aws.greengrass.model.PublishToIoTCoreResponse;
import software.amazon.awssdk.aws.greengrass.model.QOS;
import software.amazon.awssdk.aws.greengrass.model.ServiceError;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToIoTCoreRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToIoTCoreResponse;
import software.amazon.awssdk.aws.greengrass.model.UnauthorizedError;
import software.amazon.awssdk.crt.mqtt5.packets.SubAckPacket;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.inject.Inject;

import static com.aws.greengrass.ipc.common.ExceptionUtil.translateExceptions;
import static com.aws.greengrass.ipc.modules.MqttProxyIPCService.MQTT_PROXY_SERVICE_NAME;
import static com.aws.greengrass.mqttclient.v5.QOS.AT_LEAST_ONCE;
import static com.aws.greengrass.mqttclient.v5.QOS.AT_MOST_ONCE;

public class MqttProxyIPCAgent {
    private static final Logger LOGGER = LogManager.getLogger(MqttProxyIPCAgent.class);
    private static final String COMPONENT_NAME = "componentName";
    private static final String TOPIC_KEY = "topic";
    private static final String UNAUTHORIZED_ERROR = "Not Authorized";
    private static final String NO_PAYLOAD_ERROR = "Payload is required";
    private static final String NO_TOPIC_ERROR = "Topic is required";
    private static final String NO_QOS_ERROR = "QoS is required";
    private static final String INVALID_QOS_ERROR = "Invalid QoS value";

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

    class PublishToIoTCoreOperationHandler extends GeneratedAbstractPublishToIoTCoreOperationHandler {

        private final String serviceName;

        protected PublishToIoTCoreOperationHandler(OperationContinuationHandlerContext context) {
            super(context);
            serviceName = context.getAuthenticationData().getIdentityLabel();
        }

        @Override
        protected void onStreamClosed() {

        }

        @SuppressWarnings({"PMD.PreserveStackTrace", "PMD.AvoidInstanceofChecksInCatchClause"})
        @Override
        public PublishToIoTCoreResponse handleRequest(PublishToIoTCoreRequest request) {
            return translateExceptions(() -> {
                // Intern the string to deduplicate topic strings in memory
                String topic = validateTopic(request.getTopicName(), serviceName).intern();

                try {
                    doAuthorization(this.getOperationModelContext().getOperationName(), serviceName, topic);
                } catch (AuthorizationException e) {
                    LOGGER.atInfo().kv("error", e.getMessage()).log(UNAUTHORIZED_ERROR);
                    throw new UnauthorizedError(UNAUTHORIZED_ERROR);
                }

                byte[] payload = validatePayload(request.getPayload(), serviceName);
                com.aws.greengrass.mqttclient.v5.QOS qos = validateQoS(request.getQosAsString(), serviceName);
                Publish publishRequest = Publish.builder()
                        .payload(payload)
                        .topic(topic)
                        .qos(qos)
                        .retain(Coerce.toBoolean(request.isRetain()))
                        .correlationData(request.getCorrelationData())
                        .responseTopic(request.getResponseTopic())
                        .messageExpiryIntervalSeconds(request.getMessageExpiryIntervalSeconds())
                        .userProperties(request.getUserProperties() == null ? null :
                                request.getUserProperties().stream()
                                        .map((u) -> new UserProperty(u.getKey(), u.getValue()))
                                        .collect(Collectors.toList()))
                        .payloadFormat(
                                request.getPayloadFormat() == null || request.getPayloadFormat() == PayloadFormat.BYTES
                                        ? Publish.PayloadFormatIndicator.BYTES : Publish.PayloadFormatIndicator.UTF8)
                        .build();

                try {
                    mqttClient.publish(publishRequest);
                } catch (MqttRequestException | SpoolerStoreException | InterruptedException e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    throw new ServiceError(String.format("Publish to topic %s failed: %s", topic,
                            Utils.getUltimateMessage(e)));
                }

                return new PublishToIoTCoreResponse();
            });
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {

        }
    }

    class SubscribeToIoTCoreOperationHandler extends GeneratedAbstractSubscribeToIoTCoreOperationHandler {

        private final String serviceName;

        private String subscribedTopic;

        private Consumer<Publish> subscriptionCallback;

        protected SubscribeToIoTCoreOperationHandler(OperationContinuationHandlerContext context) {
            super(context);
            serviceName = context.getAuthenticationData().getIdentityLabel();
        }

        @Override
        protected void onStreamClosed() {
            if (!Utils.isEmpty(subscribedTopic)) {
                Unsubscribe unsubscribeRequest =
                        Unsubscribe.builder().subscriptionCallback(subscriptionCallback).topic(subscribedTopic).build();

                try {
                    mqttClient.unsubscribe(unsubscribeRequest).exceptionally((t) -> {
                        LOGGER.atError()
                                .kv(COMPONENT_NAME, serviceName)
                                .kv(TOPIC_KEY, subscribedTopic)
                                .cause(t)
                                .log("Stream closed but unable to unsubscribe from topic");
                        return null;
                    });
                } catch (MqttRequestException e) {
                    LOGGER.atError().cause(e).kv(TOPIC_KEY, subscribedTopic).kv(COMPONENT_NAME, serviceName)
                            .log("Stream closed but unable to unsubscribe from topic");
                }
            }
        }

        @Override
        public SubscribeToIoTCoreResponse handleRequest(SubscribeToIoTCoreRequest request) {
            return null;
        }

        @SuppressWarnings({"PMD.PreserveStackTrace", "PMD.AvoidCatchingGenericException"})
        @Override
        public CompletableFuture<SubscribeToIoTCoreResponse> handleRequestAsync(SubscribeToIoTCoreRequest request) {
            return translateExceptions(() -> {
                String topic = validateTopic(request.getTopicName(), serviceName);

                try {
                    doAuthorization(this.getOperationModelContext().getOperationName(), serviceName, topic);
                } catch (AuthorizationException e) {
                    LOGGER.atInfo().kv("error", e.getMessage()).log(UNAUTHORIZED_ERROR);
                    throw new UnauthorizedError(UNAUTHORIZED_ERROR);
                }

                Consumer<Publish> callback = this::forwardToSubscriber;
                com.aws.greengrass.mqttclient.v5.QOS qos = validateQoS(request.getQosAsString(), serviceName);
                Subscribe subscribeRequest = Subscribe.builder().callback(callback).topic(topic)
                        .qos(qos).build();

                try {
                    subscribedTopic = topic;
                    subscriptionCallback = callback;

                    return mqttClient.subscribe(subscribeRequest).exceptionally((t) -> {
                        LOGGER.atError().cause(t).kv(TOPIC_KEY, topic).kv(COMPONENT_NAME, serviceName)
                                .log("Unable to subscribe to topic");
                        throw new ServiceError(String.format("Subscribe to topic %s failed with error %s", topic, t));
                    }).thenApply((i) -> {
                        if (i != null) {
                            int rc = i.getReasonCode();
                            if (rc > 2) {
                                String rcString = SubAckPacket.SubAckReasonCode.UNSPECIFIED_ERROR.name();
                                try {
                                    rcString = SubAckPacket.SubAckReasonCode.getEnumValueFromInteger(rc).name();
                                } catch (RuntimeException ignored) {
                                }

                                throw new ServiceError(
                                        String.format("Subscribe to topic %s failed with error %s", topic,
                                                rcString))
                                        .withContext(Utils.immutableMap("reasonString", i.getReasonString(),
                                                "reasonCode", i.getReasonCode()));
                            }
                        }
                        return new SubscribeToIoTCoreResponse();
                    });
                } catch (MqttRequestException e) {
                    LOGGER.atError().cause(e).kv(TOPIC_KEY, topic).kv(COMPONENT_NAME, serviceName)
                            .log("Unable to subscribe to topic");
                    throw new ServiceError(String.format("Subscribe to topic %s failed with error %s", topic, e));
                }
            });
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {

        }

        private void forwardToSubscriber(Publish m) {

            this.sendStreamEvent(new IoTCoreMessage().withMessage(
                    new MQTTMessage()
                            .withTopicName(m.getTopic())
                            .withPayload(m.getPayload())
                            .withCorrelationData(m.getCorrelationData())
                            .withMessageExpiryIntervalSeconds(m.getMessageExpiryIntervalSeconds())
                            .withResponseTopic(m.getResponseTopic())
                            .withRetain(m.isRetain())
                            .withContentType(m.getContentType()).withPayloadFormat(
                                    m.getPayloadFormat() == null || m.getPayloadFormat()
                                            == Publish.PayloadFormatIndicator.BYTES ? PayloadFormat.BYTES :
                                            PayloadFormat.UTF8)
                            .withUserProperties(m.getUserProperties() == null ? null : m.getUserProperties().stream()
                                    .map((u) -> new software.amazon.awssdk.aws.greengrass.model.UserProperty()
                                            .withKey(u.getKey()).withValue(u.getValue()))
                                    .collect(Collectors.toList()))
            ));
        }
    }

    private String validateTopic(String topic, String serviceName) {
        if (topic == null) {
            LOGGER.atError().kv(COMPONENT_NAME, serviceName).log(NO_TOPIC_ERROR);
            throw new InvalidArgumentsError(NO_TOPIC_ERROR);
        }
        return topic;
    }

    private byte[] validatePayload(byte[] payload, String serviceName) {
        if (payload == null) {
            LOGGER.atError().kv(COMPONENT_NAME, serviceName).log(NO_PAYLOAD_ERROR);
            throw new InvalidArgumentsError(NO_PAYLOAD_ERROR);
        }
        return payload;
    }

    private com.aws.greengrass.mqttclient.v5.QOS validateQoS(String qosAsString, String serviceName) {
        if (qosAsString == null) {
            LOGGER.atError().kv(COMPONENT_NAME, serviceName).log(NO_QOS_ERROR);
            throw new InvalidArgumentsError(NO_QOS_ERROR);
        }

        if (qosAsString.equals(QOS.AT_LEAST_ONCE.getValue())) {
            return AT_LEAST_ONCE;
        } else if (qosAsString.equals(QOS.AT_MOST_ONCE.getValue())) {
            return AT_MOST_ONCE;
        } else {
            LOGGER.atError().kv(COMPONENT_NAME, serviceName).kv("QoS", qosAsString).log(INVALID_QOS_ERROR);
            throw new InvalidArgumentsError(INVALID_QOS_ERROR + ": " + qosAsString);
        }
    }

    void doAuthorization(String opName, String serviceName, String topic) throws AuthorizationException {
        if (authorizationHandler.isAuthorized(MQTT_PROXY_SERVICE_NAME,
                Permission.builder().operation(opName).principal(serviceName).resource(topic).build(),
                AuthorizationHandler.ResourceLookupPolicy.MQTT_STYLE)) {
            return;
        }
        throw new AuthorizationException(
                String.format("Principal %s is not authorized to perform %s:%s on resource %s", serviceName,
                        MQTT_PROXY_SERVICE_NAME, opName, topic));
    }
}
