/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.mqttclient.spool.Spool;
import com.aws.greengrass.mqttclient.spool.SpoolerLoadException;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.LockScope;
import com.aws.greengrass.util.ProxyUtils;
import software.amazon.awssdk.crt.auth.credentials.X509CredentialsProvider;
import software.amazon.awssdk.crt.http.HttpProxyOptions;
import software.amazon.awssdk.crt.io.ClientBootstrap;
import software.amazon.awssdk.crt.io.ClientTlsContext;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.HostResolver;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.crt.io.TlsContextOptions;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;

import java.io.Closeable;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.inject.Inject;

import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_MQTT_NAMESPACE;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_CERTIFICATE_FILE_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_IOT_DATA_ENDPOINT;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_PRIVATE_KEY_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_ROOT_CA_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_THING_NAME;
import static com.aws.greengrass.mqttclient.AwsIotMqttClient.TOPIC_KEY;

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
    public static final int EVENTLOOP_SHUTDOWN_TIMEOUT_SECONDS = 2;

    // Use read lock for MQTT operations and write lock when changing the MQTT connection
    private final ReadWriteLock connectionLock = new ReentrantReadWriteLock(true);
    private final DeviceConfiguration deviceConfiguration;
    private final Topics mqttTopics;
    private final AtomicReference<Future<?>> reconfigureFuture = new AtomicReference<>();
    private X509CredentialsProvider credentialsProvider;
    @SuppressWarnings("PMD.ImmutableField")
    private Function<ClientBootstrap, AwsIotMqttConnectionBuilder> builderProvider;
    private final List<AwsIotMqttClient> connections = new CopyOnWriteArrayList<>();
    private final Map<SubscribeRequest, AwsIotMqttClient> subscriptions = new ConcurrentHashMap<>();
    private final Map<MqttTopic, AwsIotMqttClient> subscriptionTopics = new ConcurrentHashMap<>();
    private final AtomicInteger connectionRoundRobin = new AtomicInteger(0);
    private final AtomicBoolean mqttOnline = new AtomicBoolean(false);

    private final EventLoopGroup eventLoopGroup;
    private final HostResolver hostResolver;
    private final ClientBootstrap clientBootstrap;
    private final CallbackEventManager callbackEventManager = new CallbackEventManager();
    private final Spool spool;
    private final ScheduledExecutorService ses;
    private final AtomicReference<Future<?>> spoolingFuture = new AtomicReference<>();

    private final MqttClientConnectionEvents callbacks = new MqttClientConnectionEvents() {
        @Override
        public void onConnectionInterrupted(int errorCode) {
            setMqttOnline(false);
            if (!spool.getSpoolConfig().isKeepQos0WhenOffline()) {
                spool.popOutMessagesWithQosZero();
            }
        }

        @Override
        public void onConnectionResumed(boolean sessionPresent) {
            setMqttOnline(true);
        }
    };

    private final CallbackEventManager.OnConnectCallback onConnect = new CallbackEventManager.OnConnectCallback() {
        @Override
        public void onConnect(boolean curSessionPresent) {
            callbacks.onConnectionResumed(curSessionPresent);
        }
    };

    //
    // TODO: [P41214930] Handle timeouts and retries
    //

    /**
     * Constructor for injection.
     * @param deviceConfiguration device configuration
     * @param executorService     executor service
     * @param ses                 scheduled executor service
     */
    @Inject
    public MqttClient(DeviceConfiguration deviceConfiguration, ExecutorService executorService,
                      ScheduledExecutorService ses) {
        this(deviceConfiguration, null, executorService, ses);

        HttpProxyOptions httpProxyOptions = ProxyUtils.getHttpProxyOptions(deviceConfiguration);

        if (httpProxyOptions == null) {
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
                            Coerce.toInt(mqttTopics.findOrDefault(DEFAULT_MQTT_SOCKET_TIMEOUT,
                                    MQTT_SOCKET_TIMEOUT_KEY)));
        } else {
            String tesRoleAlias = Coerce.toString(deviceConfiguration.getIotRoleAlias());

            try (TlsContextOptions x509TlsOptions = TlsContextOptions.createWithMtlsFromPath(
                    Coerce.toString(deviceConfiguration.getCertificateFilePath()),
                    Coerce.toString(deviceConfiguration.getPrivateKeyFilePath()))) {

                x509TlsOptions.withCertificateAuthorityFromPath(null,
                        Coerce.toString(deviceConfiguration.getRootCAFilePath()));

                try (ClientTlsContext x509TlsContext = new ClientTlsContext(x509TlsOptions)) {
                    this.credentialsProvider =
                            new X509CredentialsProvider.X509CredentialsProviderBuilder()
                                    .withClientBootstrap(clientBootstrap).withTlsContext(x509TlsContext)
                                    .withEndpoint(Coerce.toString(deviceConfiguration.getIotCredentialEndpoint()))
                                    .withRoleAlias(tesRoleAlias)
                                    .withThingName(Coerce.toString(deviceConfiguration.getThingName()))
                                    .withProxyOptions(httpProxyOptions).build();

                    this.builderProvider =
                            (clientBootstrap) -> AwsIotMqttConnectionBuilder.newMtlsBuilderFromPath(null, null)
                                    .withEndpoint(Coerce.toString(deviceConfiguration.getIotDataEndpoint()))
                                    .withCleanSession(false)
                                    .withBootstrap(clientBootstrap)
                                    .withKeepAliveMs(Coerce.toInt(mqttTopics
                                            .findOrDefault(DEFAULT_MQTT_KEEP_ALIVE_TIMEOUT,
                                                    MQTT_KEEP_ALIVE_TIMEOUT_KEY)))
                                    .withPingTimeoutMs(Coerce.toInt(
                                            mqttTopics.findOrDefault(DEFAULT_MQTT_PING_TIMEOUT, MQTT_PING_TIMEOUT_KEY)))
                                    .withSocketOptions(new SocketOptions()).withTimeoutMs(Coerce.toInt(mqttTopics
                                    .findOrDefault(DEFAULT_MQTT_SOCKET_TIMEOUT, MQTT_SOCKET_TIMEOUT_KEY)))
                                    .withWebsockets(true)
                                    .withWebsocketCredentialsProvider(credentialsProvider)
                                    .withWebsocketSigningRegion(Coerce.toString(deviceConfiguration.getAWSRegion()))
                                    .withWebsocketProxyOptions(httpProxyOptions);
                }
            }
        }
    }

    protected MqttClient(DeviceConfiguration deviceConfiguration,
                         Function<ClientBootstrap, AwsIotMqttConnectionBuilder> builderProvider,
                         ExecutorService executorService,
                         ScheduledExecutorService ses) {
        this.deviceConfiguration = deviceConfiguration;
        this.ses = ses;

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
        spool = new Spool(deviceConfiguration);
        callbackEventManager.addToCallbackEvents(onConnect, callbacks);
    }

    // constructor specific for unit test with spooler
    protected MqttClient(DeviceConfiguration deviceConfiguration, Spool spool, ScheduledExecutorService ses,
                         boolean mqttOnline) {
        this.deviceConfiguration = deviceConfiguration;
        mqttTopics = this.deviceConfiguration.getMQTTNamespace();
        eventLoopGroup = new EventLoopGroup(Coerce.toInt(mqttTopics.findOrDefault(1, MQTT_THREAD_POOL_SIZE_KEY)));
        hostResolver = new HostResolver(eventLoopGroup);
        clientBootstrap = new ClientBootstrap(eventLoopGroup, hostResolver);
        this.spool = spool;
        this.ses = ses;
        this.mqttOnline.set(mqttOnline);
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
                // TODO: [P41214973] Handle subscriptions with differing QoS (Upgrade 0->1->2)

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
                    AwsIotMqttClient finalConnection = connection;
                    connection.subscribe(request.getTopic(), request.getQos()).whenComplete((i, t) -> {
                        if (t == null) {
                            subscriptionTopics.put(new MqttTopic(request.getTopic()), finalConnection);
                        } else {
                            subscriptions.remove(request);
                            logger.atError().kv(TOPIC_KEY, request.getTopic()).log("Error subscribing", t);
                        }
                    }).get(connection.getTimeout(), TimeUnit.MILLISECONDS);
                }
            }
        } catch (ExecutionException e) {
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
                    sub.getValue().unsubscribe(sub.getKey().getTopic()).whenComplete((i, t) -> {
                        if (t == null) {
                            subscriptionTopics.remove(sub.getKey());

                            // Since we changed the cloud subscriptions, we need to recalculate the client to use
                            // for each subscription, since it may have changed
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
                        } else {
                            logger.atError().kv(TOPIC_KEY, sub.getKey().getTopic()).log("Error unsubscribing", t);
                        }
                    }).get(sub.getValue().getTimeout(), TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    /**
     * Publish to a MQTT topic.
     *
     * @param request publish request
     */
    public CompletableFuture<Integer> publish(PublishRequest request) {

        boolean willDropTheRequest = !mqttOnline.get() && request.getQos().getValue() == 0
                && !spool.getSpoolConfig().isKeepQos0WhenOffline();

        CompletableFuture<Integer> future = new CompletableFuture<>();
        if (willDropTheRequest) {
            SpoolerLoadException e = new SpoolerLoadException("Will not store the publish request"
                    + " with Qos 0 when MqttClient is offline");
            future.completeExceptionally(e);
            return future;
        }

        try {
            spool.addMessage(request);
            spoolMessage();
        } catch (InterruptedException | SpoolerLoadException e) {
            logger.atError().log("Fail to add publish request to spooler queue", e);
            future.completeExceptionally(e);
            return future;
        }
        return CompletableFuture.completedFuture(0);
    }

    private synchronized void spoolMessage()  {
        if (spoolingFuture.get() == null || spoolingFuture.get().isCancelled()) {
            spoolingFuture.set(ses.scheduleWithFixedDelay(() -> {
                spoolTask();
            }, 0, 5, TimeUnit.SECONDS));
        }
    }

    /**
     * Iterate the spooler queue to publish all the spooled message.
     */
    protected void spoolTask() {
        try {
            // TODO: Revisit this loop later. It is currently expensive.
            getConnection(false).connect().get();
            while (!Thread.currentThread().isInterrupted() && mqttOnline.get() && spool.getCurrentMessageCount() > 0) {
                long id = spool.popId();
                PublishRequest request = spool.getMessageById(id);
                if (request == null) {
                    continue;
                }

                long finalId = id;

                // TODO: Revisit later: currently only 1 message got sent each time.
                // Should make the sending in more efficient way.
                getConnection(false).publish(new MqttMessage(request.getTopic(),request.getPayload()),
                        request.getQos(), request.isRetain()).whenComplete((packetId, throwable) -> {
                    if (throwable == null) {
                        spool.removeMessageById(finalId);
                    } else {
                        spool.addId(finalId);
                        logger.atError().log("Failed to publish the message via Spooler", throwable);
                    }
                }).get();
            }
        } catch (InterruptedException e) {
            logger.atDebug().log("Shutting down spooler task");
        } catch (ExecutionException e) {
            logger.atError().log("Error when publishing from spooler", e);
        }
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
            logger.atTrace().kv(CLIENT_ID_KEY, client.getClientId()).kv(TOPIC_KEY, message.getTopic())
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
                logger.atError().kv(TOPIC_KEY, message.getTopic()).kv(CLIENT_ID_KEY, client.getClientId())
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
                clientId, mqttTopics, callbackEventManager);
    }

    public boolean connected() {
        return !connections.isEmpty() && connections.stream().anyMatch(AwsIotMqttClient::connected);
    }

    @Override
    public synchronized void close() {
        // Shut down spooler and then no more message will be published
        if (spoolingFuture.get() != null) {
            spoolingFuture.get().cancel(true);
        }

        connections.forEach(AwsIotMqttClient::close);
        if (credentialsProvider != null) {
            credentialsProvider.close();
        }
        clientBootstrap.close();
        hostResolver.close();
        eventLoopGroup.close();
        try {
            eventLoopGroup.getShutdownCompleteFuture().get(EVENTLOOP_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            logger.atError().log("Error shutting down event loop", e);
        } catch (TimeoutException e) {
            logger.atError().log("Timed out shutting down event loop", e);
        }
    }

    public void addToCallbackEvents(MqttClientConnectionEvents callbacks) {
        callbackEventManager.addToCallbackEvents(callbacks);
    }

    protected void setMqttOnline(boolean networkStatus) {
        mqttOnline.set(networkStatus);
    }
}
