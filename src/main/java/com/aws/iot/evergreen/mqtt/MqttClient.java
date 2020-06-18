/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.mqtt;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.config.WhatHappened;
import com.aws.iot.evergreen.deployment.DeviceConfiguration;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.LockScope;
import software.amazon.awssdk.crt.io.ClientBootstrap;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.HostResolver;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;

import java.io.Closeable;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.inject.Inject;

import static com.aws.iot.evergreen.deployment.DeviceConfiguration.DEVICE_MQTT_NAMESPACE;
import static com.aws.iot.evergreen.deployment.DeviceConfiguration.DEVICE_PARAM_CERTIFICATE_FILE_PATH;
import static com.aws.iot.evergreen.deployment.DeviceConfiguration.DEVICE_PARAM_IOT_DATA_ENDPOINT;
import static com.aws.iot.evergreen.deployment.DeviceConfiguration.DEVICE_PARAM_PRIVATE_KEY_PATH;
import static com.aws.iot.evergreen.deployment.DeviceConfiguration.DEVICE_PARAM_ROOT_CA_PATH;
import static com.aws.iot.evergreen.deployment.DeviceConfiguration.DEVICE_PARAM_THING_NAME;

public class MqttClient implements Closeable {
    private static final Logger logger = LogManager.getLogger(MqttClient.class);
    private static final String MQTT_KEEP_ALIVE_TIMEOUT_KEY = "keepAliveTimeoutMs";
    private static final int DEFAULT_MQTT_KEEP_ALIVE_TIMEOUT = (int) Duration.ofSeconds(60).toMillis();
    private static final String MQTT_PING_TIMEOUT_KEY = "pingTimeoutMs";
    private static final int DEFAULT_MQTT_PING_TIMEOUT = (int) Duration.ofSeconds(30).toMillis();
    private static final String MQTT_THREAD_POOL_SIZE_KEY = "threadPoolSize";
    public static final int DEFAULT_MQTT_PORT = 8883;
    public static final String MQTT_PORT_KEY = "port";
    private static final String MQTT_SOCKET_TIMEOUT_KEY = "socketTimeoutMs";
    // Default taken from AWS SDK
    private static final int DEFAULT_MQTT_SOCKET_TIMEOUT = (int) Duration.ofSeconds(3).toMillis();
    static final String MQTT_OPERATION_TIMEOUT_KEY = "operationTimeoutMs";
    static final int DEFAULT_MQTT_OPERATION_TIMEOUT = (int) Duration.ofSeconds(30).toMillis();
    public static final int MAX_SUBSCRIPTIONS_PER_CONNECTION = 50;
    public static final String CLIENT_ID_KEY = "clientId";

    // Use read lock for MQTT operations and write lock when changing the MQTT connection
    private final ReadWriteLock connectionLock = new ReentrantReadWriteLock(true);
    private final DeviceConfiguration deviceConfiguration;
    private final Topics mqttTopics;
    private final AtomicReference<Future<?>> reconfigureFuture = new AtomicReference<>();
    @SuppressWarnings("PMD.ImmutableField")
    private Function<ClientBootstrap, AwsIotMqttConnectionBuilder> builderProvider;
    private final List<AwsIotMqttClient> connections = new CopyOnWriteArrayList<>();
    private final Map<SubscribeRequest, AwsIotMqttClient> subscriptions = new ConcurrentHashMap<>();
    private final Map<MqttTopic, AwsIotMqttClient> subscriptionTopics = new ConcurrentHashMap<>();
    private final AtomicInteger connectionRoundRobin = new AtomicInteger(0);

    private final EventLoopGroup eventLoopGroup;
    private final HostResolver hostResolver;
    private final ClientBootstrap clientBootstrap;

    //
    // TODO: Handle timeouts and retries
    //

    /**
     * Constructor for injection.
     *
     * @param deviceConfiguration device configuration
     * @param executorService     executor service
     */
    @Inject
    public MqttClient(DeviceConfiguration deviceConfiguration, ExecutorService executorService) {
        this(deviceConfiguration, null, executorService);
        this.builderProvider = (clientBootstrap) -> AwsIotMqttConnectionBuilder
                .newMtlsBuilderFromPath(Coerce.toString(deviceConfiguration.getCertificateFilePath()),
                        Coerce.toString(deviceConfiguration.getPrivateKeyFilePath()))
                .withCertificateAuthorityFromPath(null, Coerce.toString(deviceConfiguration.getRootCAFilePath()))
                .withEndpoint(Coerce.toString(deviceConfiguration.getIotDataEndpoint()))
                .withPort((short) Coerce.toInt(mqttTopics.findOrDefault(DEFAULT_MQTT_PORT, MQTT_PORT_KEY)))
                .withCleanSession(false).withBootstrap(clientBootstrap).withKeepAliveMs(Coerce.toInt(
                        mqttTopics.findOrDefault(DEFAULT_MQTT_KEEP_ALIVE_TIMEOUT, MQTT_KEEP_ALIVE_TIMEOUT_KEY)))
                .withPingTimeoutMs(
                        Coerce.toInt(mqttTopics.findOrDefault(DEFAULT_MQTT_PING_TIMEOUT, MQTT_PING_TIMEOUT_KEY)))
                .withSocketOptions(new SocketOptions()).withTimeoutMs(
                        Coerce.toInt(mqttTopics.findOrDefault(DEFAULT_MQTT_SOCKET_TIMEOUT, MQTT_SOCKET_TIMEOUT_KEY)));
    }

    protected MqttClient(DeviceConfiguration deviceConfiguration,
                         Function<ClientBootstrap, AwsIotMqttConnectionBuilder> builderProvider,
                         ExecutorService executorService) {
        this.deviceConfiguration = deviceConfiguration;

        // If anything in the device configuration changes, then we wil need to reconnect to the cloud
        // using the new settings. We do this by calling reconnect() on all of our connections
        this.deviceConfiguration.onAnyChange((what, node) -> {
            if (WhatHappened.childChanged.equals(what) && node != null) {
                // List of configuration nodes that we need to reconfigure for if they change
                if (!(node.childOf(DEVICE_MQTT_NAMESPACE) || node.childOf(DEVICE_PARAM_THING_NAME) || node
                        .childOf(DEVICE_PARAM_IOT_DATA_ENDPOINT) || node.childOf(DEVICE_PARAM_PRIVATE_KEY_PATH) || node
                        .childOf(DEVICE_PARAM_CERTIFICATE_FILE_PATH) || node.childOf(DEVICE_PARAM_ROOT_CA_PATH))) {
                    return;
                }

                // Reconnect in separate thread to not block publish thread
                Future<?> oldFuture = reconfigureFuture.getAndSet(executorService.submit(() -> {
                    // Continually try to reconnect until all the connections are reconnected
                    Set<AwsIotMqttClient> brokenConnections = new CopyOnWriteArraySet<>(connections);
                    do {
                        for (AwsIotMqttClient connection : brokenConnections) {
                            if (Thread.currentThread().isInterrupted()) {
                                return;
                            }

                            try {
                                connection.reconnect();
                                brokenConnections.remove(connection);
                            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                                logger.atError().setCause(e).kv(CLIENT_ID_KEY, connection.getClientId())
                                        .log("Error while reconnecting MQTT client");
                            }
                        }
                    } while (!brokenConnections.isEmpty());
                }));

                // If a reconfiguration task already existed, then kill it and create a new one
                if (oldFuture != null) {
                    oldFuture.cancel(true);
                }
            }
        });
        mqttTopics = this.deviceConfiguration.getMQTTNamespace();
        this.builderProvider = builderProvider;

        eventLoopGroup = new EventLoopGroup(Coerce.toInt(mqttTopics.findOrDefault(1, MQTT_THREAD_POOL_SIZE_KEY)));
        hostResolver = new HostResolver(eventLoopGroup);
        clientBootstrap = new ClientBootstrap(eventLoopGroup, hostResolver);
    }

    /**
     * Subscribe to a MQTT topic.
     *
     * @param request subscribe request
     * @throws ExecutionException   if an error occurs
     * @throws InterruptedException if the thread is interrupted while subscribing
     * @throws TimeoutException     if the request times out
     */
    @SuppressWarnings("PMD.CloseResource")
    public synchronized void subscribe(SubscribeRequest request)
            throws ExecutionException, InterruptedException, TimeoutException {
        try {
            AwsIotMqttClient connection = null;
            // Use the write scope when identifying the subscriptionTopics that exist
            try (LockScope scope = LockScope.lock(connectionLock.writeLock())) {
                // TODO: Handle subscriptions with differing QoS (Upgrade 0->1->2)

                // If none of our existing subscriptions include (through wildcards) the new topic, then
                // go ahead and subscribe to it
                Optional<Map.Entry<MqttTopic, AwsIotMqttClient>> existingConnection =
                        findExistingSubscriberForTopic(request.getTopic());
                if (existingConnection.isPresent()) {
                    subscriptions.put(request, existingConnection.get().getValue());
                } else {
                    connection = getConnection(true);
                    subscriptions.put(request, connection);
                }
            }

            try (LockScope scope = LockScope.lock(connectionLock.readLock())) {
                // Connection isn't null, so we should subscribe to the topic
                if (connection != null) {
                    connection.subscribe(request.getTopic(), request.getQos());
                    subscriptionTopics.put(new MqttTopic(request.getTopic()), connection);
                }
            }
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            // If subscribing failed, then clean up the failed subscription callback
            subscriptions.remove(request);
            throw e;
        }
    }

    private Optional<Map.Entry<MqttTopic, AwsIotMqttClient>> findExistingSubscriberForTopic(String topic) {
        return subscriptionTopics.entrySet().stream().filter(s -> s.getKey().isSupersetOf(new MqttTopic(topic)))
                .findAny();
    }

    /**
     * Unsubscribe from a MQTT topic.
     *
     * @param request unsubscribe request
     * @throws ExecutionException   if an error occurs
     * @throws InterruptedException if the thread is interrupted while unsubscribing
     * @throws TimeoutException     if the request times out
     */
    public synchronized void unsubscribe(UnsubscribeRequest request)
            throws ExecutionException, InterruptedException, TimeoutException {
        // Use the write lock because we're modifying the subscriptions and trying to consolidate them
        try (LockScope scope = LockScope.lock(connectionLock.writeLock())) {
            Set<Map.Entry<MqttTopic, AwsIotMqttClient>> deadSubscriptionTopics;
            for (Map.Entry<SubscribeRequest, AwsIotMqttClient> sub : subscriptions.entrySet()) {
                if (sub.getKey().getCallback() == request.getCallback() && sub.getKey().getTopic()
                        .equals(request.getTopic())) {
                    subscriptions.remove(sub.getKey());
                }

            }
            // If we have no remaining subscriptions for a topic, then unsubscribe from it in the cloud
            deadSubscriptionTopics = subscriptionTopics.entrySet().stream().filter(s -> subscriptions.keySet().stream()
                    .noneMatch(sub -> s.getKey().isSupersetOf(new MqttTopic(sub.getTopic()))))
                    .collect(Collectors.toSet());
            if (!deadSubscriptionTopics.isEmpty()) {
                for (Map.Entry<MqttTopic, AwsIotMqttClient> sub : deadSubscriptionTopics) {
                    sub.getValue().unsubscribe(sub.getKey().getTopic());
                    subscriptionTopics.remove(sub.getKey());

                    // Since we changed the cloud subscriptions, we need to recalculate the client to use for each
                    // subscription, since it may have changed
                    subscriptions.entrySet().stream()
                            // if the cloud clients are the same, and the removed topic covered the topic
                            // that we're looking at, then recalculate that topic's client
                            .filter(s -> s.getValue() == sub.getValue() && sub.getKey()
                                    .isSupersetOf(new MqttTopic(s.getKey().getTopic()))).forEach(e -> {
                        // recalculate and replace the client
                        Optional<Map.Entry<MqttTopic, AwsIotMqttClient>> subscriberForTopic =
                                findExistingSubscriberForTopic(e.getKey().getTopic());
                        if (subscriberForTopic.isPresent()) {
                            subscriptions.put(e.getKey(), subscriberForTopic.get().getValue());
                        }
                    });
                }
            }
        }
    }

    /**
     * Publish to a MQTT topic.
     *
     * @param request publish request
     * @throws ExecutionException   if an error occurs
     * @throws InterruptedException if the thread is interrupted while publishing
     * @throws TimeoutException     if the request times out
     */
    public void publish(PublishRequest request) throws ExecutionException, InterruptedException, TimeoutException {
        getConnection(false).publish(new MqttMessage(request.getTopic(), request.getPayload()), request.getQos(),
                request.isRetain());
    }

    @SuppressWarnings("PMD.CloseResource")
    private synchronized AwsIotMqttClient getConnection(boolean forSubscription) {
        // If we have no connections, or our connections are over-subscribed, create a new connection
        if (connections.isEmpty() || forSubscription && connections.stream()
                .noneMatch(AwsIotMqttClient::canAddNewSubscription)) {
            AwsIotMqttClient conn = getNewMqttClient();
            connections.add(conn);
            return conn;
        } else {
            // Check for, and then close and remove any connection that has no subscriptions
            Set<AwsIotMqttClient> closableConnections =
                    connections.stream().filter((c) -> c.subscriptionCount() == 0).collect(Collectors.toSet());
            for (AwsIotMqttClient closableConnection : closableConnections) {
                // Leave the last connection alive to use for publishing
                if (connections.size() == 1) {
                    break;
                }
                closableConnection.close();
                connections.remove(closableConnection);
            }
        }

        // If this connection is to add a new subscription, then don't provide a connection
        // which is already maxed out on subscriptions
        if (forSubscription) {
            return connections.stream().filter(AwsIotMqttClient::canAddNewSubscription).findAny().get();
        }

        // Get a somewhat random, somewhat round robin connection
        return connections.get(connectionRoundRobin.getAndIncrement() % connections.size());
    }

    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    Consumer<MqttMessage> getMessageHandlerForClient(AwsIotMqttClient client) {
        return (message) -> {
            logger.atTrace().kv(CLIENT_ID_KEY, client.getClientId()).kv("topic", message.getTopic())
                    .log("Received MQTT message");

            // Each subscription is associated with a single AWSIotMqttClient even if this
            // on-device subscription did not cause the cloud connection to be made.
            // By checking that the client matches the client for the subscription, we will
            // prevent duplicate messages occurring due to overlapping subscriptions between
            // multiple clients such as A/B and A/#. Without this, an update to A/B would
            // trigger twice if those 2 subscriptions were in different clients because
            // both will receive the message from the cloud and call this handler.
            Set<SubscribeRequest> subs = subscriptions.entrySet().stream()
                    .filter(s -> s.getValue() == client && MqttTopic
                            .topicIsSupersetOf(s.getKey().getTopic(), message.getTopic())).map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            if (subs.isEmpty()) {
                logger.atError().kv("topic", message.getTopic()).kv(CLIENT_ID_KEY, client.getClientId())
                        .log("Somehow got message from topic that no one subscribed to");
                return;
            }
            subs.forEach((h) -> {
                try {
                    h.getCallback().accept(message);
                } catch (Throwable t) {
                    logger.atError().kv("message", message).kv(CLIENT_ID_KEY, client.getClientId())
                            .log("Unhandled error in MQTT message callback", t);
                }
            });
        };
    }

    protected AwsIotMqttClient getNewMqttClient() {
        // Name client by thingName-<number> except for the first connection which will just be thingName
        String clientId = Coerce.toString(deviceConfiguration.getThingName()) + (connections.isEmpty() ? ""
                : "-" + connections.size() + 1);
        return new AwsIotMqttClient(() -> builderProvider.apply(clientBootstrap), this::getMessageHandlerForClient,
                clientId, mqttTopics);
    }

    public boolean connected() {
        return !connections.isEmpty() && connections.stream().anyMatch(AwsIotMqttClient::connected);
    }

    @Override
    public synchronized void close() {
        connections.forEach(AwsIotMqttClient::close);
        clientBootstrap.close();
        hostResolver.close();
        eventLoopGroup.close();
    }
}
