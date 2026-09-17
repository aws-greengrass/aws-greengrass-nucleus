/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.mqttclient.v5.PubAck;
import com.aws.greengrass.mqttclient.v5.Publish;
import com.aws.greengrass.mqttclient.v5.QOS;
import com.aws.greengrass.mqttclient.v5.Subscribe;
import com.aws.greengrass.mqttclient.v5.SubscribeResponse;
import com.aws.greengrass.mqttclient.v5.UnsubscribeResponse;
import com.aws.greengrass.util.Coerce;
import lombok.Getter;
import vendored.com.google.common.util.concurrent.RateLimiter;

import java.time.Duration;
import java.util.Collections;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.aws.greengrass.mqttclient.MqttClient.DEFAULT_MQTT_KEEP_ALIVE_TIMEOUT;
import static com.aws.greengrass.mqttclient.MqttClient.MQTT_KEEP_ALIVE_TIMEOUT_KEY;

/**
 * IndividualMqttClient implementation backed by coreMQTT via JNI.
 * Replaces AwsIotMqtt5Client for the "coremqtt" version config.
 */
@SuppressWarnings({"PMD.UnusedPrivateField", "PMD.ExcessiveParameterList", "PMD.ConfusingTernary",
        "PMD.AvoidCatchingGenericException"})
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings({"RV_RETURN_VALUE_IGNORED_BAD_PRACTICE",
        "UMAC_UNCALLABLE_METHOD_OF_ANONYMOUS_CLASS"})
class CoreMqttJniClient implements IndividualMqttClient {

    private static final Logger logger = LogManager.getLogger(CoreMqttJniClient.class);
    private static final Random RANDOM = new Random();

    private long nativeHandle;
    @Getter
    private final String clientId;
    @Getter
    private final int clientIdNum;
    private final Topics mqttTopics;
    private final CallbackEventManager callbackEventManager;
    private final ExecutorService executorService;
    private final ScheduledExecutorService ses;
    private final Consumer<Publish> messageHandler;
    private final Supplier<String> certPathSupplier;
    private final Supplier<String> keyPathSupplier;
    private final Supplier<String> caPathSupplier;
    private final Supplier<String> endpointSupplier;
    private final Supplier<Integer> portSupplier;

    private final Set<Subscribe> subscriptionTopics = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Subscribe> droppedSubscriptionTopics = Collections.newSetFromMap(new ConcurrentHashMap<>());
    @Getter
    private final AtomicInteger inprogressSubscriptions = new AtomicInteger();
    private final AtomicBoolean hasConnectedOnce = new AtomicBoolean(false);
    private CompletableFuture<Object> connectFuture;

    // Rate limiters (same as AwsIotMqtt5Client)
    private final RateLimiter transactionLimiter = RateLimiter.create(100.0);
    private final RateLimiter bandwidthLimiter = RateLimiter.create(512.0 * 1024);

    /**
     * Callback handler invoked from native code via JNI.
     */
    @SuppressWarnings("unused") // Called from JNI
    private final Object callbackHandler = new Object() {
        public void onPublishReceived(String topic, byte[] payload, int qos, boolean retain) {
            Publish pub = Publish.builder()
                    .topic(topic)
                    .payload(payload)
                    .qos(QOS.fromInt(qos))
                    .retain(retain)
                    .build();
            messageHandler.accept(pub);
        }

        public void onConnectionSuccess(boolean sessionPresent) {
            logger.atInfo().kv("sessionPresent", sessionPresent).log("coreMQTT connected to AWS IoT Core");
            if (hasConnectedOnce.compareAndSet(false, true)) {
                callbackEventManager.runOnInitialConnect(sessionPresent);
            } else {
                callbackEventManager.runOnConnectionResumed(sessionPresent);
            }
            if (connectFuture != null && !connectFuture.isDone()) {
                connectFuture.complete(null);
            }
            resubscribe(sessionPresent);
        }

        public void onConnectionFailure(int errorCode) {
            logger.atError().kv("errorCode", errorCode).log("coreMQTT connection failed");
            if (connectFuture != null && !connectFuture.isDone()) {
                connectFuture.completeExceptionally(
                        new RuntimeException("Connection failed with error: " + errorCode));
            }
        }

        public void onDisconnection(int errorCode) {
            logger.atWarn().kv("errorCode", errorCode).log("coreMQTT connection interrupted");
            callbackEventManager.runOnConnectionInterrupted(errorCode);
        }

        public void onAckReceived(int packetId, int reasonCode) {
            // ACKs are handled via CompletableFuture in the native layer
        }
    };

    CoreMqttJniClient(
            Function<CoreMqttJniClient, Consumer<Publish>> messageHandlerFactory,
            String clientId,
            int clientIdNum,
            Topics mqttTopics,
            CallbackEventManager callbackEventManager,
            ExecutorService executorService,
            ScheduledExecutorService ses,
            Supplier<String> certPathSupplier,
            Supplier<String> keyPathSupplier,
            Supplier<String> caPathSupplier,
            Supplier<String> endpointSupplier,
            Supplier<Integer> portSupplier) {

        this.clientId = clientId;
        this.clientIdNum = clientIdNum;
        this.mqttTopics = mqttTopics;
        this.callbackEventManager = callbackEventManager;
        this.executorService = executorService;
        this.ses = ses;
        this.messageHandler = messageHandlerFactory.apply(this);
        this.certPathSupplier = certPathSupplier;
        this.keyPathSupplier = keyPathSupplier;
        this.caPathSupplier = caPathSupplier;
        this.endpointSupplier = endpointSupplier;
        this.portSupplier = portSupplier;

        int keepAliveMs = Coerce.toInt(
                mqttTopics.findOrDefault(DEFAULT_MQTT_KEEP_ALIVE_TIMEOUT, MQTT_KEEP_ALIVE_TIMEOUT_KEY));
        int keepAliveSec = keepAliveMs / 1000;

        this.nativeHandle = CoreMqttNative.create(keepAliveSec, callbackHandler);
    }

    @Override
    public CompletableFuture<?> connect() {
        if (connected()) {
            return CompletableFuture.completedFuture(null);
        }
        if (connectFuture != null && !connectFuture.isDone()) {
            return connectFuture;
        }
        connectFuture = new CompletableFuture<>();

        String endpoint = endpointSupplier.get();
        int port = portSupplier.get();
        String certPath = certPathSupplier.get();
        String keyPath = keyPathSupplier.get();
        String caPath = caPathSupplier.get();

        CoreMqttNative.connect(nativeHandle, endpoint, port,
                certPath, keyPath, caPath, clientId);
        return connectFuture;
    }

    @Override
    public CompletableFuture<SubscribeResponse> subscribe(Subscribe subscribe) {
        CompletableFuture<SubscribeResponse> future = new CompletableFuture<>();
        inprogressSubscriptions.incrementAndGet();

        connect().whenComplete((connResult, connError) -> {
            if (connError != null) {
                inprogressSubscriptions.decrementAndGet();
                future.completeExceptionally(connError);
                return;
            }
            CompletableFuture<Integer> nativeFuture = new CompletableFuture<>();
            CoreMqttNative.subscribe(nativeHandle, subscribe.getTopic(), subscribe.getQos().getValue(), nativeFuture);

            nativeFuture.whenComplete((rc, error) -> {
                inprogressSubscriptions.decrementAndGet();
                if (error == null) {
                    subscriptionTopics.add(subscribe);
                    int reasonCode = (rc != null) ? rc : 0;
                    future.complete(new SubscribeResponse(null, reasonCode, null));
                } else {
                    future.completeExceptionally(error);
                }
            });
        });

        return future;
    }

    @Override
    public CompletableFuture<UnsubscribeResponse> unsubscribe(String topic) {
        CompletableFuture<UnsubscribeResponse> future = new CompletableFuture<>();

        connect().whenComplete((connResult, connError) -> {
            if (connError != null) {
                future.completeExceptionally(connError);
                return;
            }
            CompletableFuture<Integer> nativeFuture = new CompletableFuture<>();
            CoreMqttNative.unsubscribe(nativeHandle, topic, nativeFuture);

            nativeFuture.whenComplete((rc, error) -> {
                if (error == null) {
                    subscriptionTopics.removeIf(s -> s.getTopic().equals(topic));
                    future.complete(new UnsubscribeResponse(null, null, null));
                } else {
                    future.completeExceptionally(error);
                }
            });
        });

        return future;
    }

    @Override
    public CompletableFuture<PubAck> publish(Publish publish) {
        transactionLimiter.acquire();
        bandwidthLimiter.acquire(publish.getPayload() != null ? publish.getPayload().length : 0);

        CompletableFuture<PubAck> future = new CompletableFuture<>();

        connect().whenComplete((connResult, connError) -> {
            if (connError != null) {
                future.completeExceptionally(connError);
                return;
            }
            CompletableFuture<Integer> nativeFuture = new CompletableFuture<>();
            CoreMqttNative.publish(nativeHandle, publish.getTopic(),
                    publish.getPayload() != null ? publish.getPayload() : new byte[0],
                    publish.getQos().getValue(), publish.isRetain(), nativeFuture);

            nativeFuture.whenComplete((rc, error) -> {
                if (error == null) {
                    int reasonCode = (rc != null) ? rc : 0;
                    future.complete(new PubAck(reasonCode, null, null));
                } else {
                    future.completeExceptionally(error);
                }
            });
        });

        return future;
    }

    @Override
    public long getThrottlingWaitTimeMicros() {
        return Math.max(bandwidthLimiter.microTimeToNextPermit(), transactionLimiter.microTimeToNextPermit());
    }

    @Override
    public boolean canAddNewSubscription() {
        return (subscriptionTopics.size() + inprogressSubscriptions.get())
                < MqttClient.MAX_SUBSCRIPTIONS_PER_CONNECTION;
    }

    @Override
    public int subscriptionCount() {
        return subscriptionTopics.size();
    }

    @Override
    public boolean isConnectionClosable() {
        return subscriptionTopics.size() + inprogressSubscriptions.get() == 0;
    }

    @Override
    public boolean connected() {
        return nativeHandle != 0 && CoreMqttNative.isConnected(nativeHandle);
    }

    @Override
    public void close() {
        closeOnShutdown();
    }

    @Override
    public void closeOnShutdown() {
        if (nativeHandle != 0) {
            CoreMqttNative.disconnect(nativeHandle);
            CoreMqttNative.destroy(nativeHandle);
            nativeHandle = 0;
        }
    }

    @Override
    public void reconnect(long operationTimeoutMs) throws TimeoutException, ExecutionException, InterruptedException {
        logger.atInfo().log("Reconnecting coreMQTT client");
        CoreMqttNative.disconnect(nativeHandle);
        connect().get(operationTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private void resubscribe(boolean sessionPresent) {
        if (!subscriptionTopics.isEmpty() && !sessionPresent) {
            droppedSubscriptionTopics.addAll(subscriptionTopics);
        }
        if (!droppedSubscriptionTopics.isEmpty()) {
            executorService.submit(this::resubscribeDroppedTopics);
        }
    }

    private void resubscribeDroppedTopics() {
        long delayMs = 0;
        while (connected() && !droppedSubscriptionTopics.isEmpty()) {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            for (Subscribe sub : droppedSubscriptionTopics) {
                try {
                    subscribe(sub).get(30, TimeUnit.SECONDS);
                    droppedSubscriptionTopics.remove(sub);
                } catch (Exception e) {
                    logger.atError().kv("topic", sub.getTopic()).log("Failed to resubscribe, will retry", e);
                }
            }
            delayMs = Duration.ofMinutes(2).toMillis() + RANDOM.nextInt(10_000);
        }
    }
}
