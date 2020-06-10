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
import com.aws.iot.evergreen.util.Pair;
import com.aws.iot.evergreen.util.WriteLockScope;
import software.amazon.awssdk.crt.io.ClientBootstrap;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.HostResolver;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;

import java.io.Closeable;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
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
    static final String MQTT_CONNECT_TIMEOUT_KEY = "connectTimeoutMs";
    static final int DEFAULT_MQTT_CONNECT_TIMEOUT = (int) Duration.ofSeconds(10).toMillis();
    public static final int MAX_SUBSCRIPTIONS_PER_CONNECTION = 50;
    public static final String CLIENT_ID_KEY = "clientId";

    // Use read lock for MQTT operations and write lock when changing the MQTT connection
    private final ReadWriteLock connectionLock = new ReentrantReadWriteLock(true);
    private final DeviceConfiguration deviceConfiguration;
    private final Topics mqttTopics;
    @SuppressWarnings("PMD.ImmutableField")
    private Function<ClientBootstrap, AwsIotMqttConnectionBuilder> builderProvider;
    private final List<IndividualMqttClient> connections = new CopyOnWriteArrayList<>();
    private final Set<SubscribeRequest> subscriptions = new CopyOnWriteArraySet<>();
    private final Set<Pair<String, IndividualMqttClient>> subscriptionTopics = new CopyOnWriteArraySet<>();
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
     */
    @Inject
    public MqttClient(DeviceConfiguration deviceConfiguration) {
        this(deviceConfiguration, null);
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
                         Function<ClientBootstrap, AwsIotMqttConnectionBuilder> builderProvider) {
        this.deviceConfiguration = deviceConfiguration;

        // If anything in the device configuration changes, then we wil need to reconnect to the cloud
        // using the new settings. We do this by calling reconnect() on all of our connections
        this.deviceConfiguration.onAnyChange((what, node) -> {
            if (WhatHappened.childChanged.equals(what) && node != null) {
                // List of configuration nodes that we need to reconfigure for if they change
                if (!(node.childOf(DEVICE_MQTT_NAMESPACE) || node.childOf(DEVICE_PARAM_THING_NAME) || node
                        .childOf(DEVICE_PARAM_IOT_DATA_ENDPOINT) || node.childOf(DEVICE_PARAM_PRIVATE_KEY_PATH) || node
                        .childOf(DEVICE_PARAM_CERTIFICATE_FILE_PATH) || node.childOf(DEVICE_PARAM_ROOT_CA_PATH) || node
                        .childOf(DEVICE_MQTT_NAMESPACE))) {
                    return;
                }
                for (IndividualMqttClient connection : connections) {
                    try {
                        connection.reconnect();
                    } catch (InterruptedException | ExecutionException | TimeoutException e) {
                        logger.atError().setCause(e).kv(CLIENT_ID_KEY, connection.getClientId())
                                .log("Error while reconnecting MQTT client");
                    }
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
    public void subscribe(SubscribeRequest request) throws ExecutionException, InterruptedException, TimeoutException {
        IndividualMqttClient connection = null;
        try (LockScope scope = LockScope.lock(connectionLock.readLock())) {
            // Use the write scope when identifying the subscriptionTopics that exist
            try (WriteLockScope scope2 = WriteLockScope.lock(connectionLock)) {
                subscriptions.add(request);

                // TODO: Handle subscriptions with differing QoS (Upgrade 0->1->2)

                // If none of our existing subscriptions include (through wildcards) the new topic, then
                // go ahead and subscribe to it
                if (subscriptionTopics.stream()
                        .noneMatch(s -> MqttTopic.topicIncludes(s.getLeft(), request.getTopic()))) {
                    connection = getConnection(true);
                }
            }

            // Connection isn't null, so we should subscribe to the topic
            if (connection != null) {
                connection.subscribe(request.getTopic(), request.getQos());
                subscriptionTopics.add(new Pair<>(request.getTopic(), connection));
            }
        }
    }

    /**
     * Unsubscribe from a MQTT topic.
     *
     * @param request unsubscribe request
     * @throws ExecutionException   if an error occurs
     * @throws InterruptedException if the thread is interrupted while unsubscribing
     * @throws TimeoutException     if the request times out
     */
    public void unsubscribe(UnsubscribeRequest request)
            throws ExecutionException, InterruptedException, TimeoutException {
        try (LockScope scope = LockScope.lock(connectionLock.readLock())) {
            Set<Pair<String, IndividualMqttClient>> deadSubscriptionTopics;
            // Use the write lock because we're modifying the subscriptions and trying to consolidate them
            try (WriteLockScope scope2 = WriteLockScope.lock(connectionLock)) {
                subscriptions.removeIf(
                        r -> r.getCallback() == request.getCallback() && r.getTopic().equals(request.getTopic()));
                // If we have no remaining subscriptions for a topic, then unsubscribe from it in the cloud
                deadSubscriptionTopics = subscriptionTopics.stream().filter(s -> subscriptions.stream()
                        .noneMatch(sub -> MqttTopic.topicIncludes(s.getLeft(), sub.getTopic())))
                        .collect(Collectors.toSet());

            }
            if (!deadSubscriptionTopics.isEmpty()) {
                for (Pair<String, IndividualMqttClient> sub : deadSubscriptionTopics) {
                    sub.getRight().unsubscribe(sub.getLeft());
                    subscriptionTopics.remove(sub);
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
    private synchronized IndividualMqttClient getConnection(boolean forSubscription) {
        // If we have no connections, or our connections are over-subscribed, create a new connection
        if (connections.isEmpty() || forSubscription && connections.stream()
                .noneMatch(IndividualMqttClient::canAddNewSubscription)) {
            connections.add(getNewMqttClient());
        } else {
            // Check for, and then close and remove any connection that has no subscriptions
            Set<IndividualMqttClient> closableConnections =
                    connections.stream().filter((c) -> c.subscriptionCount() == 0).collect(Collectors.toSet());
            for (IndividualMqttClient closableConnection : closableConnections) {
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
            return connections.stream().filter(IndividualMqttClient::canAddNewSubscription).findAny().get();
        }

        // Get a somewhat random, somewhat round robin connection
        return connections.get(connectionRoundRobin.getAndIncrement() % connections.size());
    }

    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    Consumer<MqttMessage> getMessageHandlerForClient(String clientId) {
        return (message) -> {
            logger.atTrace().kv(CLIENT_ID_KEY, clientId).kv("topic", message.getTopic()).log("Received MQTT message");
            Set<SubscribeRequest> subs =
                    subscriptions.stream().filter(s -> MqttTopic.topicIncludes(s.getTopic(), message.getTopic()))
                            .collect(Collectors.toSet());
            if (subs.isEmpty()) {
                logger.atError().kv("topic", message.getTopic()).kv(CLIENT_ID_KEY, clientId)
                        .log("Somehow got message from topic that no one subscribed to");
                return;
            }
            subs.forEach((h) -> {
                try {
                    h.getCallback().accept(message);
                } catch (Throwable t) {
                    logger.atError().kv("message", message).kv(CLIENT_ID_KEY, clientId)
                            .log("Unhandled error in MQTT message callback", t);
                }
            });
        };
    }

    protected IndividualMqttClient getNewMqttClient() {
        String clientId = UUID.randomUUID().toString();
        return new IndividualMqttClient(() -> builderProvider.apply(clientBootstrap),
                getMessageHandlerForClient(clientId), clientId, mqttTopics);
    }

    public boolean connected() {
        return !connections.isEmpty() && connections.stream().anyMatch(IndividualMqttClient::connected);
    }

    @Override
    public void close() {
        connections.forEach(IndividualMqttClient::close);
        clientBootstrap.close();
        hostResolver.close();
        eventLoopGroup.close();
    }
}
