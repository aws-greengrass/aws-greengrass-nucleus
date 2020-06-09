/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.mqtt;

import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.LockScope;
import com.aws.iot.evergreen.util.WriteLockScope;
import lombok.Getter;
import software.amazon.awssdk.crt.CRT;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;

import java.io.Closeable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.inject.Provider;

import static com.aws.iot.evergreen.mqtt.MqttClient.CLIENT_ID_KEY;
import static com.aws.iot.evergreen.mqtt.MqttClient.MAX_SUBSCRIPTIONS_PER_CONNECTION;

public class IndividualMqttClient implements Closeable {
    private final Logger logger = LogManager.getLogger(IndividualMqttClient.class).createChild()
            .dfltKv(CLIENT_ID_KEY, (Supplier<String>) this::getClientId);

    // Use read lock for MQTT operations and write lock when changing the MQTT connection
    private final ReadWriteLock connectionLock = new ReentrantReadWriteLock(true);
    private final Provider<AwsIotMqttConnectionBuilder> builderProvider;
    @Getter
    private final String clientId;
    private MqttClientConnection connection;
    private final AtomicBoolean currentlyConnected = new AtomicBoolean();

    private final MqttClientConnectionEvents connectionEventCallback = new MqttClientConnectionEvents() {
        @Override
        public void onConnectionInterrupted(int errorCode) {
            try (LockScope scope = LockScope.lock(connectionLock.writeLock())) {
                currentlyConnected.set(false);
            }
            // Error code 0 means that the disconnection was intentional, so we don't need to log it
            if (errorCode != 0) {
                logger.atWarn().kv("error", CRT.awsErrorString(errorCode)).log("Connection interrupted");
                // Copy-paste from Amit's original work, ask him about this if needed
                //TODO: Detect this using secondary mechanisms like checking if internet is available
                // instead of using ping to Mqtt server. Mqtt ping is expensive and should be used as the last resort.
            }
        }

        @Override
        public void onConnectionResumed(boolean sessionPresent) {
            try (LockScope scope = LockScope.lock(connectionLock.writeLock())) {
                currentlyConnected.set(true);
            }
            logger.atInfo().kv("sessionPresent", sessionPresent).log("Connection resumed");
        }
    };

    private final Consumer<MqttMessage> messageHandler;
    private final AtomicInteger subscriptionCount = new AtomicInteger(0);

    IndividualMqttClient(Provider<AwsIotMqttConnectionBuilder> builderProvider, Consumer<MqttMessage> messageHandler,
                         String clientId) {
        this.builderProvider = builderProvider;
        this.messageHandler = messageHandler;
        this.clientId = clientId;
    }

    void subscribe(String topic, QualityOfService qos) throws ExecutionException, InterruptedException {
        try (LockScope scope = LockScope.lock(connectionLock.readLock())) {
            connect();
            logger.atTrace().kv("topic", topic).kv("qos", qos.name()).log("Subscribing to topic");
            connection.subscribe(topic, qos).get();
            subscriptionCount.incrementAndGet();
        }
    }

    void unsubscribe(String topic) throws ExecutionException, InterruptedException {
        try (LockScope scope = LockScope.lock(connectionLock.readLock())) {
            connect();
            logger.atTrace().kv("topic", topic).log("Unsubscribing from topic");
            connection.unsubscribe(topic).get();
            subscriptionCount.decrementAndGet();
        }
    }

    void publish(MqttMessage message, QualityOfService qos, boolean retain)
            throws ExecutionException, InterruptedException {
        try (LockScope scope = LockScope.lock(connectionLock.readLock())) {
            connect();
            logger.atTrace().kv("topic", message.getTopic()).kv("qos", qos.name()).kv("retain", retain)
                    .log("Publishing message");
            connection.publish(message, qos, retain).get();
        }
    }

    void reconnect() throws ExecutionException, InterruptedException {
        try (WriteLockScope scope = WriteLockScope.lock(connectionLock)) {
            logger.atInfo().log("Reconnecting MQTT client most likely due to device configuration change");
            disconnect();
            connect();
        }
    }

    private void connect() throws ExecutionException, InterruptedException {
        try (WriteLockScope scope = WriteLockScope.lock(connectionLock)) {
            if (connected()) {
                return;
            }

            // Always use the builder provider here so that the builder is updated with whatever
            // the latest device config is
            try (AwsIotMqttConnectionBuilder builder = builderProvider.get()) {
                builder.withConnectionEventCallbacks(connectionEventCallback);
                builder.withClientId(clientId);

                connection = builder.build();
                connection.onMessage(messageHandler);
                logger.atInfo().log("Connecting to AWS IoT Core");
                connection.connect().get();
                currentlyConnected.set(true);
                subscriptionCount.set(0);
                logger.atInfo().log("Successfully connected to AWS IoT Core");
            }
        }
    }

    boolean canAddNewSubscription() {
        return subscriptionCount.get() < MAX_SUBSCRIPTIONS_PER_CONNECTION;
    }

    int subscriptionCount() {
        return subscriptionCount.get();
    }

    boolean connected() {
        try (LockScope scope = LockScope.lock(connectionLock.readLock())) {
            return connection != null && currentlyConnected.get();
        }
    }

    private void disconnect() {
        try (WriteLockScope scope = WriteLockScope.lock(connectionLock)) {
            currentlyConnected.set(false);
            if (connection != null) {
                logger.atDebug().log("Disconnecting from AWS IoT Core");
                connection.disconnect().get();
                connection.close();
                logger.atDebug().log("Successfully disconnected from AWS IoT Core");
            }
        } catch (InterruptedException | ExecutionException e) {
            logger.atError().log("Error while disconnecting the MQTT client", e);
        }
    }

    @Override
    public void close() {
        disconnect();
    }
}
