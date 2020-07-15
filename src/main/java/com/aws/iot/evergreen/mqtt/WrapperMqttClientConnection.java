/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.mqtt;

import lombok.Lombok;
import software.amazon.awssdk.crt.io.ClientBootstrap;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.HostResolver;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttConnectionConfig;
import software.amazon.awssdk.crt.mqtt.MqttException;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;


public class WrapperMqttClientConnection extends MqttClientConnection {

    private final MqttClient mqttClient;
    private final Map<String, UnsubscribeRequest> unsubscriptions = new ConcurrentHashMap<>();
    private static final String errMsg = "The operation is not supported by the class of WrapperMqttClientConnection";

    /**
     * Constructor.
     * @param mqttClient is from package of com.aws.iot.evergreen.mqtt to replace the old MqttClient from
     *                   software.amazon.awssdk.crt.mqtt.MqttClient
     */
    public WrapperMqttClientConnection(MqttClient mqttClient) {
        super(getMqttConnectionConfig());
        this.mqttClient = mqttClient;
    }

    /*
     * This is to initialize a valid MqttConnectionConfig which could be used
     * in the WrapperMqttClientConnection
     *
     * @return MqttConnectionConfig
     */
    private static MqttConnectionConfig getMqttConnectionConfig() {
        try (EventLoopGroup eventLoopGroup = new EventLoopGroup(0);
             HostResolver resolver = new HostResolver(eventLoopGroup);
             ClientBootstrap clientBootstrap = new ClientBootstrap(eventLoopGroup, resolver);
             software.amazon.awssdk.crt.mqtt.MqttClient oldMqttClient = new software.amazon.awssdk.crt.mqtt.MqttClient(
                     clientBootstrap);) {
            String fakeClientId = "fakeClientId";
            String fakeEndpoint = "fakeEndpoint";
            int fakePortNumber = 1;
            MqttConnectionConfig fakeConfig = new MqttConnectionConfig();
            fakeConfig.setMqttClient(oldMqttClient);
            fakeConfig.setPort(fakePortNumber);
            fakeConfig.setClientId(fakeClientId);
            fakeConfig.setEndpoint(fakeEndpoint);
            return fakeConfig;
        }
    }

    @Override
    public CompletableFuture<Integer> subscribe(String topic, QualityOfService qos, Consumer<MqttMessage> handler) {
        SubscribeRequest request = SubscribeRequest.builder().topic(topic).qos(qos).callback(handler).build();
        UnsubscribeRequest unsubscribeRequest =
                UnsubscribeRequest.builder().topic(request.getTopic()).callback(request.getCallback()).build();
        unsubscriptions.put(request.getTopic(), unsubscribeRequest);
        return mqttClient.subscribe(request).exceptionally((t) -> {
            unsubscriptions.remove(request.getTopic());
            throw Lombok.sneakyThrow(t);
        });
    }

    @Override
    public CompletableFuture<Integer> subscribe(String topic, QualityOfService qos) {
        throw new UnsupportedOperationException(errMsg);
    }

    @Override
    public CompletableFuture<Integer> publish(MqttMessage message, QualityOfService qos, boolean retain) {
        String topic = message.getTopic();
        byte[] payload = message.getPayload();
        PublishRequest publishRequest =
                PublishRequest.builder().topic(topic).retain(retain).payload(payload).qos(qos).build();
        return mqttClient.publish(publishRequest);
    }

    @Override
    public CompletableFuture<Integer> unsubscribe(String topic) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        UnsubscribeRequest request = getUnsubscribeRequest(topic);
        if (request == null) {
            future.completeExceptionally(new MqttException("No subscription to unsubscribe from"));
            return future;
        }
        return mqttClient.unsubscribe(request).thenApply((i) -> {
            unsubscriptions.remove(topic);
            return i;
        });
    }

    @Override
    public CompletableFuture<Boolean> connect() {
        throw new UnsupportedOperationException(errMsg);
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        throw new UnsupportedOperationException(errMsg);
    }

    @Override
    public void onMessage(Consumer<MqttMessage> handler) {
        throw new UnsupportedOperationException(errMsg);
    }

    private UnsubscribeRequest getUnsubscribeRequest(String subscriptionTopic) {
        return unsubscriptions.get(subscriptionTopic);
    }
}
