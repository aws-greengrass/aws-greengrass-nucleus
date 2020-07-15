/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.mqtt;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Coerce;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.AccessLevel;
import lombok.Getter;
import software.amazon.awssdk.crt.CRT;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;

import java.io.Closeable;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.inject.Provider;

import static com.aws.iot.evergreen.mqtt.MqttClient.CLIENT_ID_KEY;
import static com.aws.iot.evergreen.mqtt.MqttClient.DEFAULT_MQTT_OPERATION_TIMEOUT;
import static com.aws.iot.evergreen.mqtt.MqttClient.MAX_SUBSCRIPTIONS_PER_CONNECTION;
import static com.aws.iot.evergreen.mqtt.MqttClient.MQTT_OPERATION_TIMEOUT_KEY;

/**
 * Wrapper for a single AWS IoT MQTT client connection.
 * Do not use except through {@link MqttClient}.
 */
class AwsIotMqttClient implements Closeable {
    private static final String TOPIC_KEY = "topic";
    private static final String QOS_KEY = "qos";
    private final Logger logger = LogManager.getLogger(AwsIotMqttClient.class).createChild()
            .dfltKv(CLIENT_ID_KEY, (Supplier<String>) this::getClientId);

    private final Provider<AwsIotMqttConnectionBuilder> builderProvider;
    @Getter
    private final String clientId;
    @SuppressFBWarnings("IS2_INCONSISTENT_SYNC")
    private MqttClientConnection connection;
    private final AtomicBoolean currentlyConnected = new AtomicBoolean();
    private final CallbackEventManager callbackEventManager;

    @Getter(AccessLevel.PACKAGE)
    private final MqttClientConnectionEvents connectionEventCallback = new MqttClientConnectionEvents() {
        @Override
        public void onConnectionInterrupted(int errorCode) {
            currentlyConnected.set(false);
            // Error code 0 means that the disconnection was intentional, so we don't need to log it
            if (errorCode != 0) {
                logger.atWarn().kv("error", CRT.awsErrorString(errorCode)).log("Connection interrupted");
                // Copy-paste from Amit's original work, ask him about this if needed
                //TODO: Detect this using secondary mechanisms like checking if internet is available
                // instead of using ping to Mqtt server. Mqtt ping is expensive and should be used as the last resort.
            }
            // To run the callbacks shared by the different AwsIotMqttClient.
            callbackEventManager.runOnConnectionInterrupted(errorCode);
        }

        @Override
        public void onConnectionResumed(boolean sessionPresent) {
            currentlyConnected.set(true);
            logger.atInfo().kv("sessionPresent", sessionPresent).log("Connection resumed");
            // If we didn't reconnect using the same session, then resubscribe to all the topics
            if (!sessionPresent) {
                resubscribe();
            }
            // To run the callbacks shared by the different AwsIotMqttClient.
            callbackEventManager.runOnConnectionResumed(sessionPresent);
        }
    };

    private final Consumer<MqttMessage> messageHandler;
    private final Topics mqttTopics;
    @Getter(AccessLevel.PACKAGE)
    private final Map<String, QualityOfService> subscriptionTopics = new ConcurrentHashMap<>();

    AwsIotMqttClient(Provider<AwsIotMqttConnectionBuilder> builderProvider,
                     Function<AwsIotMqttClient, Consumer<MqttMessage>> messageHandler,
                     String clientId, Topics mqttTopics,
                     CallbackEventManager callbackEventManager) {
        this.builderProvider = builderProvider;
        this.clientId = clientId;
        this.mqttTopics = mqttTopics;
        this.messageHandler = messageHandler.apply(this);
        this.callbackEventManager = callbackEventManager;
    }

    void subscribe(String topic, QualityOfService qos)
            throws ExecutionException, InterruptedException, TimeoutException {
        connect().get(getTimeout(), TimeUnit.MILLISECONDS);
        logger.atDebug().kv(TOPIC_KEY, topic).kv(QOS_KEY, qos.name()).log("Subscribing to topic");
        connection.subscribe(topic, qos).get(getTimeout(), TimeUnit.MILLISECONDS);
        subscriptionTopics.put(topic, qos);
    }

    void unsubscribe(String topic) throws ExecutionException, InterruptedException, TimeoutException {
        connect().get(getTimeout(), TimeUnit.MILLISECONDS);
        logger.atDebug().kv(TOPIC_KEY, topic).log("Unsubscribing from topic");
        connection.unsubscribe(topic).get(getTimeout(), TimeUnit.MILLISECONDS);
        subscriptionTopics.remove(topic);
    }

    CompletableFuture<Integer> publish(MqttMessage message, QualityOfService qos, boolean retain) {
        return connect().thenCompose((b) -> {
            logger.atTrace().kv(TOPIC_KEY, message.getTopic()).kv(QOS_KEY, qos.name()).kv("retain", retain)
                    .log("Publishing message");
            return connection.publish(message, qos, retain);
        });
    }

    void reconnect() throws TimeoutException, ExecutionException, InterruptedException {
        // Synchronize here instead of method signature to make mockito work without deadlocking
        synchronized (this) {
            logger.atInfo().log("Reconnecting MQTT client most likely due to device configuration change");
            disconnect();
            connect().get(getTimeout(), TimeUnit.MILLISECONDS);
        }
    }

    private synchronized CompletableFuture<Boolean> connect() {
        if (connection != null) {
            return CompletableFuture.completedFuture(true);
        }

        // Always use the builder provider here so that the builder is updated with whatever
        // the latest device config is
        try (AwsIotMqttConnectionBuilder builder = builderProvider.get()) {
            builder.withConnectionEventCallbacks(connectionEventCallback);
            builder.withClientId(clientId);

            connection = builder.build();
            // Set message handler for this connection to be our global message handler in MqttClient.
            // The handler will then send out the message to all subscribers after appropriate filtering.
            connection.onMessage(messageHandler);
            logger.atDebug().log("Connecting to AWS IoT Core");
            return connection.connect().thenApply((sessionPresent) -> {
                currentlyConnected.set(true);
                logger.atDebug().kv("sessionPresent", sessionPresent).log("Successfully connected to AWS IoT Core");

                if (!sessionPresent) {
                    resubscribe();
                }
                return sessionPresent;
            });
        }
    }

    private int getTimeout() {
        return Coerce.toInt(mqttTopics.findOrDefault(DEFAULT_MQTT_OPERATION_TIMEOUT, MQTT_OPERATION_TIMEOUT_KEY));
    }

    private void resubscribe() {
        subscriptionTopics.forEach((key, value) -> {
            try {
                subscribe(key, value);
            } catch (ExecutionException | InterruptedException | TimeoutException e) {
                logger.atError().kv(TOPIC_KEY, key).kv(QOS_KEY, value.name()).log("Unable to resubscribe to topic");
            }
        });
    }

    boolean canAddNewSubscription() {
        return subscriptionTopics.size() < MAX_SUBSCRIPTIONS_PER_CONNECTION;
    }

    int subscriptionCount() {
        return subscriptionTopics.size();
    }

    boolean connected() {
        return connection != null && currentlyConnected.get();
    }

    private synchronized void disconnect() {
        try {
            currentlyConnected.set(false);
            if (connection != null) {
                logger.atDebug().log("Disconnecting from AWS IoT Core");
                try {
                    connection.disconnect().get(getTimeout(), TimeUnit.MILLISECONDS);
                } finally {
                    connection.close();
                    connection = null;
                }
                logger.atDebug().log("Successfully disconnected from AWS IoT Core");
            }
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            logger.atError().log("Error while disconnecting the MQTT client", e);
        }
    }

    @Override
    public void close() {
        disconnect();
    }
}
