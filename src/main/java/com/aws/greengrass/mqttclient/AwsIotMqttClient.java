/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.crt.CRT;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.crt.mqtt.MqttException;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;
import vendored.com.google.common.util.concurrent.RateLimiter;

import java.io.Closeable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.inject.Provider;

/**
 * Wrapper for a single AWS IoT MQTT client connection.
 * Do not use except through {@link MqttClient}.
 */
class AwsIotMqttClient implements Closeable {
    static final String TOPIC_KEY = "topic";
    private static final String RESUB_LOG_EVENT = "resubscribe";
    private static final String QOS_KEY = "qos";
    private static final Random RANDOM = new Random();
    private final Logger logger = LogManager.getLogger(AwsIotMqttClient.class).createChild()
            .dfltKv(MqttClient.CLIENT_ID_KEY, (Supplier<String>) this::getClientId);

    private final ExecutorService executorService;
    private final ScheduledExecutorService ses;
    private final Provider<AwsIotMqttConnectionBuilder> builderProvider;
    @Getter
    private final String clientId;
    private MqttClientConnection connection;
    private CompletableFuture<Boolean> connectionFuture = null;
    private final AtomicBoolean currentlyConnected = new AtomicBoolean();
    private final CallbackEventManager callbackEventManager;
    private final AtomicBoolean initialConnect = new AtomicBoolean(true);
    private final Consumer<MqttMessage> messageHandler;
    private final Topics mqttTopics;
    @Getter(AccessLevel.PACKAGE)
    private final Map<String, QualityOfService> subscriptionTopics = new ConcurrentHashMap<>();
    private final Map<String, QualityOfService> droppedSubscriptionTopics = new ConcurrentHashMap<>();
    @Getter(AccessLevel.PACKAGE)
    private final AtomicInteger inprogressSubscriptions = new AtomicInteger();
    private Future<?> resubscribeFuture;
    @Setter
    private static long subscriptionRetryMillis = Duration.ofMinutes(2).toMillis();
    @Setter
    private static int waitTimeJitterMaxMillis = 10_000;

    // Limit TPS to 100 which is IoT Core's limit per connection
    private final RateLimiter transactionLimiter = RateLimiter.create(100.0);
    // Limit bandwidth to 512 KBPS
    private final RateLimiter bandwidthLimiter = RateLimiter.create(512.0 * 1024);

    // Limit TPS to 1 which is IoT Core's limit for connect requests per client-id
    // IoT was throttling connect calls even at 1 TPS because the limit is actually 0.1 when
    // the same host is hit with the request.
    private final RateLimiter connectLimiter = RateLimiter.create(0.09);


    @Getter(AccessLevel.PACKAGE)
    private final MqttClientConnectionEvents connectionEventCallback = new MqttClientConnectionEvents() {
        @Override
        public void onConnectionInterrupted(int errorCode) {
            currentlyConnected.set(false);
            // Error code 0 means that the disconnection was intentional. We do not need to run callbacks when we
            // purposely interrupt a connection.
            if (errorCode == 0) {
                logger.atInfo().log("Connection purposefully interrupted");
                return;
            } else {
                logger.atWarn().kv("error", CRT.awsErrorString(errorCode)).log("Connection interrupted");
            }
            if (resubscribeFuture != null && !resubscribeFuture.isDone()) {
                resubscribeFuture.cancel(true);
            }
            // To run the callbacks shared by the different AwsIotMqttClient.
            callbackEventManager.runOnConnectionInterrupted(errorCode);
        }

        @Override
        public void onConnectionResumed(boolean sessionPresent) {
            currentlyConnected.set(true);
            logger.atInfo().kv("sessionPresent", sessionPresent).log("Connection resumed");
            resubscribe(sessionPresent);
            // To run the callbacks shared by the different AwsIotMqttClient.
            callbackEventManager.runOnConnectionResumed(sessionPresent);
        }
    };

    AwsIotMqttClient(Provider<AwsIotMqttConnectionBuilder> builderProvider,
                     Function<AwsIotMqttClient, Consumer<MqttMessage>> messageHandler, String clientId,
                     Topics mqttTopics, CallbackEventManager callbackEventManager, ExecutorService executorService,
                     ScheduledExecutorService ses) {
        this.builderProvider = builderProvider;
        this.clientId = clientId;
        this.mqttTopics = mqttTopics;
        this.messageHandler = messageHandler.apply(this);
        this.callbackEventManager = callbackEventManager;
        this.executorService = executorService;
        this.ses = ses;
    }

    void disableRateLimiting() {
        connectLimiter.setRate(Double.MAX_VALUE);
        bandwidthLimiter.setRate(Double.MAX_VALUE);
        transactionLimiter.setRate(Double.MAX_VALUE);
    }

    long getThrottlingWaitTimeMicros() {
        // Return the worst possible wait time.
        // Time to wait is independent of how many permits we need because future transactions
        // will pay this current transaction's cost.  See the JavaDocs for RateLimiter for more info.
        return Math.max(bandwidthLimiter.microTimeToNextPermit(), transactionLimiter.microTimeToNextPermit());
    }

    // Notes about the CRT MQTT client:
    // client has no timeouts if the connection is dropped, then we do get an exception
    // so we need to retry ourselves. If offline, client waits to be online then tries to subscribe

    CompletableFuture<Integer> subscribe(String topic, QualityOfService qos) {
        return connect().thenCompose((b) -> {
            logger.atDebug().kv(TOPIC_KEY, topic).kv(QOS_KEY, qos.name())
                    .log("Subscribing to topic");
            synchronized (this) {
                throwIfNoConnection();
                inprogressSubscriptions.incrementAndGet();
                return connection.subscribe(topic, qos).whenComplete((i, error) -> {
                    synchronized (this) {
                        if (error == null) {
                            subscriptionTopics.put(topic, qos);
                            logger.atDebug().kv(TOPIC_KEY, topic).kv(QOS_KEY, qos.name())
                                    .log("Successfully subscribed to topic");
                        } else {
                            logger.atError().kv(TOPIC_KEY, topic)
                                    .cause(error).log("Error subscribing to topic");
                        }
                        inprogressSubscriptions.decrementAndGet();
                    }
                });
            }
        });
    }

    CompletableFuture<Integer> unsubscribe(String topic) {
        return connect().thenCompose((b) -> {
            logger.atDebug().kv(TOPIC_KEY, topic).log("Unsubscribing from topic");
            synchronized (this) {
                throwIfNoConnection();
                return connection.unsubscribe(topic).thenApply((i) -> {
                    synchronized (this) {
                        subscriptionTopics.remove(topic);
                    }
                    return i;
                });
            }
        });
    }

    CompletableFuture<Integer> publish(MqttMessage message, QualityOfService qos, boolean retain) {
        return connect().thenCompose((b) -> {
            // Take the tokens from the limiters' token buckets.
            // This is guaranteed to not block because we've already slept the required time
            // in the spooler thread before calling this method.
            transactionLimiter.acquire();
            bandwidthLimiter.acquire(message.getPayload().length);
            synchronized (this) {
                throwIfNoConnection();
                logger.atTrace().kv(TOPIC_KEY, message.getTopic()).kv(QOS_KEY, qos.name()).kv("retain", retain)
                        .log("Publishing message");
                return connection.publish(message, qos, retain);
            }
        });
    }

    private void throwIfNoConnection() {
        if (connection == null) {
            throw new MqttException("No active connection to use");
        }
    }

    void reconnect() throws TimeoutException, ExecutionException, InterruptedException {
        logger.atInfo().log("Reconnecting MQTT client most likely due to device configuration change");
        disconnect().get(getTimeout(), TimeUnit.MILLISECONDS);
        connect().get(getTimeout(), TimeUnit.MILLISECONDS);
    }

    protected synchronized CompletableFuture<Boolean> connect() {
        // future not done indicates an ongoing connect attempt, caller should wait on that future
        // instead of starting another connect attempt.
        if (connectionFuture != null && !connectionFuture.isDone()) {
            return connectionFuture;
        }
        // A client exists, there's nothing to do because the SDK would reconnect for us
        if (connection != null) {
            return CompletableFuture.completedFuture(true);
        }
        // For the initial connect, client connects with cleanSession=true and disconnects.
        // This deletes any previous session information maintained by IoT Core.
        // For subsequent connects, the client connects with cleanSession=false
        CompletableFuture<Void> voidCompletableFuture = CompletableFuture.completedFuture(null);
        if (initialConnect.get()) {
            voidCompletableFuture = establishConnection(true).thenCompose((session) -> {
                initialConnect.set(false);
                return disconnect();
            });
        }

        connectionFuture = voidCompletableFuture.thenCompose((b) -> establishConnection(false))
                .thenApply((sessionPresent) -> {
                    currentlyConnected.set(true);
                    logger.atInfo().kv("sessionPresent", sessionPresent)
                            .log("Successfully connected to AWS IoT Core");
                    resubscribe(sessionPresent);
                    callbackEventManager.runOnInitialConnect(sessionPresent);
                    return sessionPresent;
                });

        return connectionFuture;
    }

    private CompletableFuture<Boolean> establishConnection(boolean overrideCleanSession) {
        // Always use the builder provider here so that the builder is updated with whatever
        // the latest device config is
        try (AwsIotMqttConnectionBuilder builder = builderProvider.get()) {
            builder.withConnectionEventCallbacks(connectionEventCallback);
            builder.withClientId(clientId);
            if (overrideCleanSession) {
                builder.withCleanSession(true);
            }
            connection = builder.build();
            // Set message handler for this connection to be our global message handler in MqttClient.
            // The handler will then send out the message to all subscribers after appropriate filtering.
            connection.onMessage(messageHandler);

            connectLimiter.acquire();
            logger.atInfo().log("Connecting to AWS IoT Core");
            return connection.connect().whenComplete((session, error) -> {
                if (error != null) {
                    connectionCleanup();
                    logger.atError().log("Unable to connect to AWS IoT Core", error);
                }
            });
        } catch (MqttException e) {
            CompletableFuture<Boolean> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(e);
            return failedFuture;
        }
    }

    int getTimeout() {
        return Coerce.toInt(mqttTopics.findOrDefault(
                MqttClient.DEFAULT_MQTT_OPERATION_TIMEOUT, MqttClient.MQTT_OPERATION_TIMEOUT_KEY));
    }

    /**
     * Run re-subscription task in another thread so that the current thread is not blocked by it. The task will keep
     * retrying until all subscription succeeded or it's canceled by network interruption.
     *
     * @param sessionPresent whether the session persisted
     */
    private synchronized void resubscribe(boolean sessionPresent) {
        // No need to resub if we haven't subscribed to anything
        if (!subscriptionTopics.isEmpty()) {
            // If connected without a session, all subscriptions are dropped and need to be resubscribed
            if (!sessionPresent) {
                droppedSubscriptionTopics.putAll(subscriptionTopics);
            }
            if (!droppedSubscriptionTopics.isEmpty() && (resubscribeFuture == null || resubscribeFuture.isDone())) {
                resubscribeFuture = executorService.submit(this::resubscribeDroppedTopicsTask);
            }
        }
    }

    private void resubscribeDroppedTopicsTask() {
        long delayMillis = 0;  // don't delay the first run
        while (currentlyConnected.get() && !droppedSubscriptionTopics.isEmpty()) {
            logger.atDebug().event(RESUB_LOG_EVENT).kv("droppedTopics", droppedSubscriptionTopics.keySet())
                    .kv("delayMillis", delayMillis).log("Subscribing to dropped topics");
            ScheduledFuture<?> scheduledFuture = ses.schedule(() -> {
                List<CompletableFuture<Integer>> subFutures = new ArrayList<>();
                for (Map.Entry<String, QualityOfService> entry : droppedSubscriptionTopics.entrySet()) {
                    subFutures.add(subscribe(entry.getKey(), entry.getValue()).whenComplete((result, error) -> {
                        if (error == null) {
                            droppedSubscriptionTopics.remove(entry.getKey());
                        } else {
                            logger.atError().event(RESUB_LOG_EVENT).cause(error).kv(TOPIC_KEY, entry.getKey())
                                    .log("Failed to subscribe to topic. Will retry later");
                        }
                    }));
                }
                // Block and wait for all subscriptions to finish
                CompletableFuture<?> allSubFutures =
                        CompletableFuture.allOf(subFutures.toArray(new CompletableFuture[0]));
                try {
                    allSubFutures.get();
                } catch (InterruptedException e) {
                    logger.atWarn().event(RESUB_LOG_EVENT).cause(e)
                            .log("Subscription interrupted. Cancelling subscriptions");
                    allSubFutures.cancel(true);
                } catch (ExecutionException e) {
                    // Do nothing. Errors already handled in individual subscription future's whenComplete stage
                }
            }, delayMillis, TimeUnit.MILLISECONDS);

            try {
                scheduledFuture.get();
            } catch (ExecutionException e) {
                logger.atError().event(RESUB_LOG_EVENT).cause(e).log("Scheduled task failed. Will retry later");
            } catch (InterruptedException e) {
                logger.atWarn().event(RESUB_LOG_EVENT).log("Cancelling scheduled task because of interruption");
                scheduledFuture.cancel(true);
                return;
            }
            delayMillis = subscriptionRetryMillis + RANDOM.nextInt(waitTimeJitterMaxMillis);
        }
    }

    synchronized boolean canAddNewSubscription() {
        return (subscriptionTopics.size() + inprogressSubscriptionsCount())
                < MqttClient.MAX_SUBSCRIPTIONS_PER_CONNECTION;
    }

    synchronized int subscriptionCount() {
        return subscriptionTopics.size();
    }

    private int inprogressSubscriptionsCount() {
        return inprogressSubscriptions.get();
    }

    synchronized boolean isConnectionClosable() {
        return subscriptionTopics.size() + inprogressSubscriptionsCount() == 0;
    }

    synchronized boolean connected() {
        return connection != null && currentlyConnected.get();
    }

    protected synchronized CompletableFuture<Void> disconnect() {
        currentlyConnected.set(false);
        if (connection != null) {
            logger.atDebug().log("Disconnecting from AWS IoT Core");
            return connection.disconnect().whenComplete((future, error) -> {
                logger.atDebug().log("Successfully disconnected from AWS IoT Core");
                connectionCleanup();
            });
        }
        return CompletableFuture.completedFuture(null);
    }

    @SuppressWarnings("PMD.NullAssignment")
    private void connectionCleanup() {
        // Must synchronize since we're messing with the shared connection object and this block
        // is executed in some other thread
        synchronized (this) {
            if (connection != null) {
                connection.close();
                connection = null;
            }
        }
    }

    @Override
    public void close() {
        if (resubscribeFuture != null && !resubscribeFuture.isDone()) {
            logger.atTrace().log("Canceling resubscribe future");
            resubscribeFuture.cancel(true);
        }
        try {
            disconnect().get(getTimeout(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            logger.atError().log("Error while disconnecting the MQTT client", e);
        }
    }
}
