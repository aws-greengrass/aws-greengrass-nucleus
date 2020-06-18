package com.aws.iot.evergreen.mqtt;

import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttConnectionConfig;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public class WrapperMqttClientConnection extends MqttClientConnection {

    private final MqttClient client;

    public WrapperMqttClientConnection(MqttConnectionConfig config, MqttClient mqttClient) {
        super(config);
        this.client = mqttClient;
    }

    @Override
    public CompletableFuture<Integer> subscribe(String topic, QualityOfService qos, Consumer<MqttMessage> handler) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        try {
            SubscribeRequest request = SubscribeRequest.builder().topic(topic).qos(qos).callback(handler).build();
            client.subscribe(request);
            return future.thenApply(succeed -> 0);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            future.completeExceptionally(e);
            return future;
        }
    }

    @Override
    public CompletableFuture<Integer> publish(MqttMessage message, QualityOfService qos, boolean retain) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        try {
            String topic = message.getTopic();
            byte[] payload = message.getPayload();
            PublishRequest publishRequest = PublishRequest.builder()
                    .topic(topic).retain(retain).payload(payload).build();
            client.publish(publishRequest);
            return future.thenApply(succeed -> 0);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            future.completeExceptionally(e);
            return future;
        }
    }
}
