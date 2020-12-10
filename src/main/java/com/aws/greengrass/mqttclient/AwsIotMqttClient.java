/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.ProxyUtils;
import lombok.AccessLevel;
import lombok.Getter;
import software.amazon.awssdk.crt.CRT;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.crt.mqtt.MqttException;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;

import java.io.Closeable;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.inject.Provider;

/**
 * Wrapper for a single AWS IoT MQTT client connection.
 * Do not use except through {@link MqttClient}.
 */
class AwsIotMqttClient implements Closeable {
    static final String TOPIC_KEY = "topic";
    private static final String QOS_KEY = "qos";
    private final Logger logger = LogManager.getLogger(AwsIotMqttClient.class).createChild()
            .dfltKv(MqttClient.CLIENT_ID_KEY, (Supplier<String>) this::getClientId);

    private final Provider<AwsIotMqttConnectionBuilder> builderProvider;
    @Getter
    private final String clientId;
    private final ExecutorService executorService;
    private MqttClientConnection connection;
    private final AtomicBoolean currentlyConnected = new AtomicBoolean();
    private final CallbackEventManager callbackEventManager;
    private static final long WAIT_TIME_MS_TO_SUBSCRIBE_AGAIN = Duration.ofMinutes(2).toMillis();
    private static final Random RANDOM = new Random();
    private static final AtomicBoolean resubscribing = new AtomicBoolean();

    @Getter(AccessLevel.PACKAGE)
    private final MqttClientConnectionEvents connectionEventCallback = new MqttClientConnectionEvents() {
        @Override
        public void onConnectionInterrupted(int errorCode) {
            currentlyConnected.set(false);
            // Error code 0 means that the disconnection was intentional, so we don't need to log it
            if (errorCode != 0) {
                logger.atWarn().kv("error", CRT.awsErrorString(errorCode)).log("Connection interrupted");
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
                executorService.execute(() -> resubscribe());
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
                     CallbackEventManager callbackEventManager,
                     ExecutorService executorService) {
        this.builderProvider = builderProvider;
        this.clientId = clientId;
        this.mqttTopics = mqttTopics;
        this.messageHandler = messageHandler.apply(this);
        this.callbackEventManager = callbackEventManager;
        this.executorService = executorService;
    }

    // Notes about the CRT MQTT client:
    // client has no timeouts if the connection is dropped, then we do get an exception
    // so we need to retry ourselves. If offline, client waits to be online then tries to subscribe

    CompletableFuture<Integer> subscribe(String topic, QualityOfService qos) {
        return connect().thenCompose((b) -> {
            logger.atDebug().kv(TOPIC_KEY, topic).kv(QOS_KEY, qos.name()).log("Subscribing to topic");
            synchronized (this) {
                throwIfNotConnected();
                return connection.subscribe(topic, qos).thenApply((i) -> {
                    subscriptionTopics.put(topic, qos);
                    return i;
                });
            }
        });
    }

    CompletableFuture<Integer> unsubscribe(String topic) {
        return connect().thenCompose((b) -> {
            logger.atDebug().kv(TOPIC_KEY, topic).log("Unsubscribing from topic");
            synchronized (this) {
                throwIfNotConnected();
                return connection.unsubscribe(topic).thenApply((i) -> {
                    subscriptionTopics.remove(topic);
                    return i;
                });
            }
        });
    }

    CompletableFuture<Integer> publish(MqttMessage message, QualityOfService qos, boolean retain) {
        return connect().thenCompose((b) -> {
            logger.atTrace().kv(TOPIC_KEY, message.getTopic()).kv(QOS_KEY, qos.name()).kv("retain", retain)
                    .log("Publishing message");
            synchronized (this) {
                throwIfNotConnected();
                return connection.publish(message, qos, retain);
            }
        });
    }

    private void throwIfNotConnected() {
        if (!connected()) {
            throw new MqttException("Client is not connected");
        }
    }

    void reconnect() throws TimeoutException, ExecutionException, InterruptedException {
        // Synchronize here instead of method signature to make mockito work without deadlocking
        synchronized (this) {
            logger.atInfo().log("Reconnecting MQTT client most likely due to device configuration change");
            disconnect();
            connect().get(getTimeout(), TimeUnit.MILLISECONDS);
        }
    }

    @SuppressWarnings("PMD.NullAssignment")
    protected synchronized CompletableFuture<Boolean> connect() {
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

            logger.atInfo().log("Connecting to AWS IoT Core");
            return connection.connect().thenApply((sessionPresent) -> {
                currentlyConnected.set(true);
                logger.atInfo().kv("sessionPresent", sessionPresent).log("Successfully connected to AWS IoT Core");
                if (!sessionPresent) {
                    resubscribe();
                }
                callbackEventManager.runOnInitialConnect(sessionPresent);
                return sessionPresent;
            }).whenComplete((session, error) -> {
                if (error != null) {
                    // Must synchronize since we're messing with the shared connection object and this block
                    // is executed in some other thread
                    synchronized (this) {
                        connection.close();
                        connection = null;
                    }
                    logger.atError().log("Unable to connect to AWS IoT Core", error);
                    if (ProxyUtils.getProxyConfiguration() != null) {
                        logger.atInfo().log("You are using a proxy which uses a websocket connection and "
                                + "TokenExchangeService credentials. Verify that the IAM role which the IoT Role "
                                + "Alias is aliasing has a policy which allows for iot:Connect, iot:Subscribe, "
                                + "iot:Publish, and iot:Receive.");
                    }
                }
            });
        }
    }

    int getTimeout() {
        return Coerce.toInt(mqttTopics.findOrDefault(
                MqttClient.DEFAULT_MQTT_OPERATION_TIMEOUT, MqttClient.MQTT_OPERATION_TIMEOUT_KEY));
    }

    private void resubscribe() {
        if (!resubscribing.compareAndSet(false, true)) {
            return;
        }
        while (true) {
            List<CompletableFuture<Integer>> subscriptionFutures = subscriptionTopics.entrySet().stream()
                    .map((k) -> subscribe(k.getKey(), k.getValue())).collect(Collectors.toList());

            try {
                CompletableFuture.allOf(subscriptionFutures.toArray(new CompletableFuture[0]))
                        .get(getTimeout(), TimeUnit.MILLISECONDS);
                resubscribing.set(false);
                logger.atDebug().log("Finished resubscribing to all topics");
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (TimeoutException | ExecutionException e) {
                logger.atInfo().log("Failed to resubscribe to all topics. Will retry later.", e);
                try {
                    Thread.sleep(WAIT_TIME_MS_TO_SUBSCRIBE_AGAIN + RANDOM.nextInt(10_000));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    boolean canAddNewSubscription() {
        return subscriptionCount() < MqttClient.MAX_SUBSCRIPTIONS_PER_CONNECTION;
    }

    int subscriptionCount() {
        return subscriptionTopics.size();
    }

    synchronized boolean connected() {
        return connection != null && currentlyConnected.get();
    }

    @SuppressWarnings("PMD.NullAssignment")
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
