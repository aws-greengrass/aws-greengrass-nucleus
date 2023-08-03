/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.logging.api.LogEventBuilder;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.mqttclient.v5.PubAck;
import com.aws.greengrass.mqttclient.v5.Publish;
import com.aws.greengrass.mqttclient.v5.Subscribe;
import com.aws.greengrass.mqttclient.v5.SubscribeResponse;
import com.aws.greengrass.mqttclient.v5.UnsubscribeResponse;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Utils;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.crt.CRT;
import software.amazon.awssdk.crt.mqtt.MqttException;
import software.amazon.awssdk.crt.mqtt5.Mqtt5Client;
import software.amazon.awssdk.crt.mqtt5.Mqtt5ClientOptions;
import software.amazon.awssdk.crt.mqtt5.OnAttemptingConnectReturn;
import software.amazon.awssdk.crt.mqtt5.OnConnectionFailureReturn;
import software.amazon.awssdk.crt.mqtt5.OnConnectionSuccessReturn;
import software.amazon.awssdk.crt.mqtt5.OnDisconnectionReturn;
import software.amazon.awssdk.crt.mqtt5.OnStoppedReturn;
import software.amazon.awssdk.crt.mqtt5.PublishResult;
import software.amazon.awssdk.crt.mqtt5.packets.ConnAckPacket;
import software.amazon.awssdk.crt.mqtt5.packets.ConnectPacket;
import software.amazon.awssdk.crt.mqtt5.packets.DisconnectPacket;
import software.amazon.awssdk.crt.mqtt5.packets.PubAckPacket;
import software.amazon.awssdk.crt.mqtt5.packets.UnsubscribePacket;
import software.amazon.awssdk.iot.AwsIotMqtt5ClientBuilder;
import vendored.com.google.common.util.concurrent.RateLimiter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.inject.Provider;

import static com.aws.greengrass.mqttclient.AwsIotMqttClient.QOS_KEY;
import static com.aws.greengrass.mqttclient.MqttClient.DEFAULT_MQTT_KEEP_ALIVE_TIMEOUT;
import static com.aws.greengrass.mqttclient.MqttClient.MQTT_KEEP_ALIVE_TIMEOUT_KEY;

class AwsIotMqtt5Client implements IndividualMqttClient {

    static final String TOPIC_KEY = "topic";
    private static final String RESUB_LOG_EVENT = "resubscribe";
    private final Provider<AwsIotMqtt5ClientBuilder> builderProvider;

    private Mqtt5Client client = null;

    private static final Random RANDOM = new Random();
    private final Logger logger = LogManager.getLogger(AwsIotMqtt5Client.class).createChild()
            .dfltKv(MqttClient.CLIENT_ID_KEY, (Supplier<String>) this::getClientId);

    private final ExecutorService executorService;
    private final ScheduledExecutorService ses;
    @Getter
    private final String clientId;
    @Getter
    private final int clientIdNum;
    private final CallbackEventManager callbackEventManager;
    private final Mqtt5ClientOptions.PublishEvents messageHandler;
    @SuppressWarnings("PMD.UnusedPrivateField")
    private final Topics mqttTopics;
    @Getter(AccessLevel.PACKAGE)
    private final Set<Subscribe> subscriptionTopics = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Subscribe> droppedSubscriptionTopics = Collections.newSetFromMap(new ConcurrentHashMap<>());
    @Getter(AccessLevel.PACKAGE)
    private final AtomicInteger inprogressSubscriptions = new AtomicInteger();
    private Future<?> resubscribeFuture;
    private CompletableFuture<Mqtt5Client> connectFuture;
    @Setter
    private static long subscriptionRetryMillis = Duration.ofMinutes(2).toMillis();
    @Setter
    private static int waitTimeJitterMaxMillis = 10_000;

    // Limit TPS to 100 which is IoT Core's limit per connection
    private final RateLimiter transactionLimiter = RateLimiter.create(100.0);
    // Limit bandwidth to 512 KBPS
    private final RateLimiter bandwidthLimiter = RateLimiter.create(512.0 * 1024);
    private final AtomicBoolean hasConnectedOnce = new AtomicBoolean(false);

    private final AtomicReference<CompletableFuture<Void>> stopFuture = new AtomicReference<>(null);
    @Getter(AccessLevel.PACKAGE)
    private final Mqtt5ClientOptions.LifecycleEvents connectionEventCallback =
            new Mqtt5ClientOptions.LifecycleEvents() {
        @Override
        public void onAttemptingConnect(Mqtt5Client client, OnAttemptingConnectReturn onAttemptingConnectReturn) {
            logger.atDebug().log("Attempting to connect to AWS IoT Core");
        }

        @Override
        public void onConnectionSuccess(Mqtt5Client client, OnConnectionSuccessReturn onConnectionSuccessReturn) {
            boolean sessionPresent = onConnectionSuccessReturn.getConnAckPacket().getSessionPresent();

            if (hasConnectedOnce.compareAndSet(false, true)) {
                logger.atInfo().kv("sessionPresent", sessionPresent).log("Successfully connected to AWS IoT Core");
                callbackEventManager.runOnInitialConnect(sessionPresent);
            } else {
                logger.atInfo().kv("sessionPresent", sessionPresent).log("Connection resumed");
                callbackEventManager.runOnConnectionResumed(sessionPresent);
            }
            connectFuture.complete(client);
            resubscribe(sessionPresent);
        }

        @Override
        @SuppressWarnings("PMD.DoNotLogWithoutLogging")
        public void onConnectionFailure(Mqtt5Client client, OnConnectionFailureReturn onConnectionFailureReturn) {
            int errorCode = onConnectionFailureReturn.getErrorCode();
            ConnAckPacket packet = onConnectionFailureReturn.getConnAckPacket();
            LogEventBuilder l = logger.atError().kv("error", CRT.awsErrorString(errorCode));
            if (packet != null) {
                l.kv("reasonCode", packet.getReasonCode().name())
                 .kv("reason", packet.getReasonString());
            }
            l.log("Failed to connect to AWS IoT Core");
        }

        @Override
        @SuppressWarnings("PMD.DoNotLogWithoutLogging")
        public void onDisconnection(Mqtt5Client client, OnDisconnectionReturn onDisconnectionReturn) {
            int errorCode = onDisconnectionReturn.getErrorCode();
            DisconnectPacket packet = onDisconnectionReturn.getDisconnectPacket();
            // Error AWS_ERROR_MQTT5_USER_REQUESTED_STOP means that the disconnection was intentional.
            // We do not need to run callbacks when we purposely interrupt a connection.
            if ("AWS_ERROR_MQTT5_USER_REQUESTED_STOP".equals(CRT.awsErrorName(errorCode))
                    || packet != null && packet.getReasonCode()
                    .equals(DisconnectPacket.DisconnectReasonCode.NORMAL_DISCONNECTION)) {
                logger.atInfo().log("Connection purposefully interrupted");
                return;
            } else {
                LogEventBuilder l = logger.atWarn().kv("error", CRT.awsErrorString(errorCode));
                if (packet != null) {
                    l.kv("reasonCode", packet.getReasonCode().name())
                     .kv("reason", packet.getReasonString());
                }
                l.log("Connection interrupted");
            }
            if (resubscribeFuture != null && !resubscribeFuture.isDone()) {
                resubscribeFuture.cancel(true);
            }
            // To run the callbacks shared by the different IndividualMqttClient.
            callbackEventManager.runOnConnectionInterrupted(errorCode);
        }

        @Override
        public void onStopped(Mqtt5Client client, OnStoppedReturn onStoppedReturn) {
            client.close();
            CompletableFuture<Void> f = stopFuture.get();
            if (f != null) {
                f.complete(null);
            }
        }
    };

    AwsIotMqtt5Client(Provider<AwsIotMqtt5ClientBuilder> builderProvider,
                      Function<AwsIotMqtt5Client, Consumer<Publish>> messageHandler, String clientId, int clientIdNum,
                      Topics mqttTopics, CallbackEventManager callbackEventManager, ExecutorService executorService,
                      ScheduledExecutorService ses) {
        this.clientId = clientId;
        this.clientIdNum = clientIdNum;
        this.mqttTopics = mqttTopics;
        Consumer<Publish> handler = messageHandler.apply(this);
        this.messageHandler =
                (client, publishReturn) -> handler.accept(Publish.fromCrtPublishPacket(
                        publishReturn.getPublishPacket()));
        this.callbackEventManager = callbackEventManager;
        this.executorService = executorService;
        this.ses = ses;
        this.builderProvider = builderProvider;
    }

    void disableRateLimiting() {
        bandwidthLimiter.setRate(Double.MAX_VALUE);
        transactionLimiter.setRate(Double.MAX_VALUE);
    }

    @Override
    public long getThrottlingWaitTimeMicros() {
        // Return the worst possible wait time.
        // Time to wait is independent of how many permits we need because future transactions
        // will pay this current transaction's cost.  See the JavaDocs for RateLimiter for more info.
        return Math.max(bandwidthLimiter.microTimeToNextPermit(), transactionLimiter.microTimeToNextPermit());
    }

    @Override
    public synchronized boolean canAddNewSubscription() {
        return (subscriptionTopics.size() + inprogressSubscriptions.get())
                < MqttClient.MAX_SUBSCRIPTIONS_PER_CONNECTION;
    }

    @Override
    public synchronized int subscriptionCount() {
        return subscriptionTopics.size();
    }

    @Override
    public synchronized boolean isConnectionClosable() {
        return subscriptionTopics.size() + inprogressSubscriptions.get() == 0;
    }

    @Override
    public synchronized boolean connected() {
        return client != null && client.getIsConnected();
    }

    @Override
    public synchronized void closeOnShutdown() {
        if (resubscribeFuture != null && !resubscribeFuture.isDone()) {
            logger.atTrace().log("Canceling resubscribe future");
            resubscribeFuture.cancel(true);
        }

        if (client != null) {
            disconnect();
            connectionCleanup();
        }
    }

    protected synchronized CompletableFuture<Void> disconnect() {
        if (client != null) {
            logger.atDebug().log("Disconnecting from AWS IoT Core");
            CompletableFuture<Void> f = new CompletableFuture<>();
            stopFuture.set(f);
            client.stop(null);
            connectionCleanup();
            return f;
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<SubscribeResponse> subscribe(Subscribe subscribe) {
        return connect().thenCompose((client) -> {
            logger.atDebug().kv(TOPIC_KEY, subscribe.getTopic()).kv(QOS_KEY, subscribe.getQos().name())
                    .log("Subscribing to topic");
            inprogressSubscriptions.incrementAndGet();
            return client.subscribe(subscribe.toCrtSubscribePacket())
                    .thenApply(SubscribeResponse::fromCrtSubAck)
                    .whenComplete((r, error) -> {
                        synchronized (this) {
                            // reason codes less than or equal to 2 are positive responses
                            if (error == null && r != null && r.isSuccessful()) {
                                subscriptionTopics.add(subscribe);
                                logger.atDebug().kv(TOPIC_KEY, subscribe.getTopic())
                                        .kv(QOS_KEY, subscribe.getQos().name())
                                        .log("Successfully subscribed to topic");
                            } else {
                                LogEventBuilder l = logger.atError().kv(TOPIC_KEY, subscribe.getTopic());
                                if (error != null) {
                                    l.cause(error);
                                }
                                if (r != null) {
                                    l.kv("reasonCode", r.getReasonCode());
                                    if (Utils.isNotEmpty(r.getReasonString())) {
                                        l.kv("reason", r.getReasonString());
                                    }
                                }
                                l.log("Error subscribing to topic");
                            }
                            inprogressSubscriptions.decrementAndGet();
                        }
                    });
        });
    }

    private synchronized void internalConnect() {
        if (client != null) {
            return;
        }
        if (connectFuture == null || connectFuture.isDone()) {
            connectFuture = new CompletableFuture<>();
        }
        try (AwsIotMqtt5ClientBuilder builder = this.builderProvider.get()) {
            long minReconnectSeconds = Coerce.toLong(mqttTopics.find("minimumReconnectDelaySeconds"));
            long maxReconnectSeconds = Coerce.toLong(mqttTopics.find("maximumReconnectDelaySeconds"));
            long minConnectTimeSeconds = Coerce.toLong(mqttTopics.find("minimumConnectedTimeBeforeRetryResetSeconds"));

            builder.withLifeCycleEvents(this.connectionEventCallback)
                    .withPublishEvents(this.messageHandler)
                    .withSessionBehavior(Mqtt5ClientOptions.ClientSessionBehavior.REJOIN_POST_SUCCESS)
                    .withOfflineQueueBehavior(
                            Mqtt5ClientOptions.ClientOfflineQueueBehavior.FAIL_ALL_ON_DISCONNECT)
                    .withMinReconnectDelayMs(minReconnectSeconds == 0 ? null : minReconnectSeconds * 1000)
                    .withMaxReconnectDelayMs(maxReconnectSeconds == 0 ? null : maxReconnectSeconds * 1000)
                    .withMinConnectedTimeToResetReconnectDelayMs(
                            minConnectTimeSeconds == 0 ? null : minConnectTimeSeconds * 1000)
                    .withConnectProperties(new ConnectPacket.ConnectPacketBuilder()
                        .withRequestProblemInformation(true)
                        .withClientId(clientId).withKeepAliveIntervalSeconds(Coerce.toLong(
                                    mqttTopics.findOrDefault(DEFAULT_MQTT_KEEP_ALIVE_TIMEOUT,
                                            MQTT_KEEP_ALIVE_TIMEOUT_KEY)) / 1000)
                        .withReceiveMaximum(Coerce.toLong(mqttTopics.findOrDefault(100L, "receiveMaximum")))
                        .withSessionExpiryIntervalSeconds(Coerce.toLong(mqttTopics.findOrDefault(10_080L,
                                "sessionExpirySeconds")))
                    );
            client = builder.build();
        } catch (MqttException e) {
            connectFuture.completeExceptionally(e);
            return;
        }
        client.start();
    }

    @Override
    public synchronized CompletableFuture<Mqtt5Client> connect() {
        internalConnect();
        return connectFuture;
    }

    @Override
    public synchronized CompletableFuture<UnsubscribeResponse> unsubscribe(String topic) {
        return connect().thenCompose((client) -> {
            logger.atDebug().kv(TOPIC_KEY, topic).log("Unsubscribing from topic");
            return client.unsubscribe(new UnsubscribePacket.UnsubscribePacketBuilder().withSubscription(topic).build())
                    .thenApply(r -> {
                        synchronized (this) {
                            subscriptionTopics.removeIf(s -> s.getTopic().equals(topic));
                        }
                        return UnsubscribeResponse.fromCrtUnsubAck(r);
                    });
        });
    }

    @Override
    public synchronized CompletableFuture<PubAck> publish(Publish publish) {
        return connect().thenCompose((client) -> {
            // Take the tokens from the limiters' token buckets.
            // This is guaranteed to not block because we've already slept the required time
            // in the spooler thread before calling this method.
            transactionLimiter.acquire();
            bandwidthLimiter.acquire(publish.getPayload().length);
            logger.atTrace().kv(TOPIC_KEY, publish.getTopic())
                    .kv(QOS_KEY, publish.getQos().name())
                    .log("Publishing message");
            return client.publish(publish.toCrtPublishPacket()).thenApply(r -> {
                if (r.getType().equals(PublishResult.PublishResultType.NONE)) {
                    return new PubAck(0, null, null);
                }
                PubAckPacket p = r.getResultPubAck();
                return PubAck.fromCrtPubAck(p);
            });
        });
    }

    @Override
    public void close() {
        closeOnShutdown();
    }

    @Override
    public void reconnect(long timeoutMs) throws TimeoutException, ExecutionException, InterruptedException {
        logger.atInfo().log("Reconnecting MQTT client most likely due to device configuration change");
        disconnect().get(timeoutMs, TimeUnit.MILLISECONDS);
        connect().get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("PMD.NullAssignment")
    private void connectionCleanup() {
        // Must synchronize since we're messing with the shared connection object and this block
        // is executed in some other thread
        synchronized (this) {
            if (client != null) {
                client = null;
            }
        }
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
                droppedSubscriptionTopics.addAll(subscriptionTopics);
            }
            if (!droppedSubscriptionTopics.isEmpty() && (resubscribeFuture == null || resubscribeFuture.isDone())) {
                resubscribeFuture = executorService.submit(this::resubscribeDroppedTopicsTask);
            }
        }
    }

    private void resubscribeDroppedTopicsTask() {
        long delayMillis = 0;  // don't delay the first run
        while (connected() && !droppedSubscriptionTopics.isEmpty()) {
            logger.atDebug().event(RESUB_LOG_EVENT).kv("droppedTopics",
                            (Supplier<List<String>>) () -> droppedSubscriptionTopics.stream().map(Subscribe::getTopic)
                                    .collect(Collectors.toList())).kv("delayMillis", delayMillis)
                    .log("Subscribing to dropped topics");
            ScheduledFuture<?> scheduledFuture = ses.schedule(() -> {
                List<CompletableFuture<SubscribeResponse>> subFutures = new ArrayList<>();
                for (Subscribe sub : droppedSubscriptionTopics) {
                    subFutures.add(subscribe(sub).whenComplete((result, error) -> {
                        if (error == null) {
                            droppedSubscriptionTopics.remove(sub);
                        } else {
                            logger.atError().event(RESUB_LOG_EVENT).cause(error).kv(TOPIC_KEY, sub.getTopic())
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
}
