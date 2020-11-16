/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.mqttproxy;

import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.MqttTopic;
import com.aws.greengrass.mqttclient.PublishRequest;
import com.aws.greengrass.mqttclient.SubscribeRequest;
import com.aws.greengrass.mqttclient.UnsubscribeRequest;
import com.aws.greengrass.util.Utils;
import lombok.AccessLevel;
import lombok.Setter;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractPublishToIoTCoreOperationHandler;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractSubscribeToIoTCoreOperationHandler;
import software.amazon.awssdk.aws.greengrass.model.IoTCoreMessage;
import software.amazon.awssdk.aws.greengrass.model.MQTTMessage;
import software.amazon.awssdk.aws.greengrass.model.PublishToIoTCoreRequest;
import software.amazon.awssdk.aws.greengrass.model.PublishToIoTCoreResponse;
import software.amazon.awssdk.aws.greengrass.model.QOS;
import software.amazon.awssdk.aws.greengrass.model.ServiceError;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToIoTCoreRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToIoTCoreResponse;
import software.amazon.awssdk.aws.greengrass.model.UnauthorizedError;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import javax.inject.Inject;

import static com.aws.greengrass.authorization.AuthorizationHandler.ANY_REGEX;
import static com.aws.greengrass.ipc.common.ExceptionUtil.translateExceptions;
import static com.aws.greengrass.ipc.modules.MqttProxyIPCService.MQTT_PROXY_SERVICE_NAME;

public class MqttProxyIPCAgent {
    private static final Logger LOGGER = LogManager.getLogger(MqttProxyIPCAgent.class);
    private static final String COMPONENT_NAME = "componentName";
    private static final String TOPIC_KEY = "topic";

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

        @SuppressWarnings("PMD.PreserveStackTrace")
        @Override
        public PublishToIoTCoreResponse handleRequest(PublishToIoTCoreRequest request) {
            return translateExceptions(() -> {
                String topic = request.getTopicName();

                try {
                    doAuthorization(this.getOperationModelContext().getOperationName(), serviceName, topic);
                } catch (AuthorizationException e) {
                    LOGGER.atError().cause(e).log();
                    throw new UnauthorizedError(String.format("Authorization failed with error %s", e));
                }

                PublishRequest publishRequest = PublishRequest.builder().payload(request.getPayload()).topic(topic)
                        .qos(getQualityOfServiceFromQOS(request.getQos())).build();
                CompletableFuture<Integer> future = mqttClient.publish(publishRequest);

                try {
                    future.get(2, TimeUnit.SECONDS);
                } catch (TimeoutException | InterruptedException ignored) {
                    // If it times out or we're interrupted, then just return the positive response
                    // it is most likely in the spooler since it didn't fail immediately.
                } catch (ExecutionException e) {
                    LOGGER.atError().cause(e).kv(TOPIC_KEY, topic).kv(COMPONENT_NAME, serviceName)
                            .log("Unable to spool the publish request");
                    throw new ServiceError(String.format("Publish to topic %s failed with error %s", topic, e));
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

        private Consumer<MqttMessage> subscriptionCallback;

        protected SubscribeToIoTCoreOperationHandler(OperationContinuationHandlerContext context) {
            super(context);
            serviceName = context.getAuthenticationData().getIdentityLabel();
        }

        @Override
        protected void onStreamClosed() {
            if (!Utils.isEmpty(subscribedTopic)) {
                UnsubscribeRequest unsubscribeRequest =
                        UnsubscribeRequest.builder().callback(subscriptionCallback).topic(subscribedTopic).build();

                try {
                    mqttClient.unsubscribe(unsubscribeRequest);
                } catch (ExecutionException | InterruptedException | TimeoutException e) {
                    LOGGER.atError().cause(e).kv(TOPIC_KEY, subscribedTopic).kv(COMPONENT_NAME, serviceName)
                            .log("Stream closed but unable to unsubscribe from topic");
                }
            }
        }

        @SuppressWarnings("PMD.PreserveStackTrace")
        @Override
        public SubscribeToIoTCoreResponse handleRequest(SubscribeToIoTCoreRequest request) {
            return translateExceptions(() -> {
                String topic = request.getTopicName();

                try {
                    doAuthorization(this.getOperationModelContext().getOperationName(), serviceName, topic);
                } catch (AuthorizationException e) {
                    LOGGER.atError().cause(e).log();
                    throw new UnauthorizedError(String.format("Authorization failed with error %s", e));
                }

                Consumer<MqttMessage> callback = this::forwardToSubscriber;
                SubscribeRequest subscribeRequest = SubscribeRequest.builder().callback(callback).topic(topic)
                        .qos(getQualityOfServiceFromQOS(request.getQos())).build();

                try {
                    mqttClient.subscribe(subscribeRequest);
                } catch (ExecutionException | InterruptedException | TimeoutException e) {
                    LOGGER.atError().cause(e).kv(TOPIC_KEY, topic).kv(COMPONENT_NAME, serviceName)
                            .log("Unable to subscribe to topic");
                    throw new ServiceError(String.format("Subscribe to topic %s failed with error %s", topic, e));
                }

                subscribedTopic = topic;
                subscriptionCallback = callback;

                return new SubscribeToIoTCoreResponse();
            });
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

    private QualityOfService getQualityOfServiceFromQOS(QOS qos) {
        if (qos == QOS.AT_LEAST_ONCE) {
            return QualityOfService.AT_LEAST_ONCE;
        } else if (qos == QOS.AT_MOST_ONCE) {
            return QualityOfService.AT_MOST_ONCE;
        }
        return QualityOfService.AT_LEAST_ONCE; //default value
    }

    void doAuthorization(String opName, String serviceName, String topic) throws AuthorizationException {
        List<String> authorizedResources =
                authorizationHandler.getAuthorizedResources(MQTT_PROXY_SERVICE_NAME, serviceName, opName);

        for (String topicFilter : authorizedResources) {
            if (topicFilter.equals(ANY_REGEX) || MqttTopic.topicIsSupersetOf(topicFilter, topic)) {
                LOGGER.atDebug().log("Hit policy with principal {}, operation {}, resource {}",
                        MQTT_PROXY_SERVICE_NAME,
                        opName,
                        topicFilter);
                return;
            }
        }

        throw new AuthorizationException(
                String.format("Principal %s is not authorized to perform %s:%s on resource %s", serviceName,
                        MQTT_PROXY_SERVICE_NAME, opName, topic));
    }
}
