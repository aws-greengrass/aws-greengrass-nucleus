package com.aws.iot.evergreen.mqtt;

import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import software.amazon.awssdk.crt.io.ClientBootstrap;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.HostResolver;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttConnectionConfig;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import javax.inject.Inject;


public class WrapperMqttClientConnection extends MqttClientConnection {

     private final MqttClient mqttClient;
     private static final Logger logger = LogManager.getLogger(WrapperMqttClientConnection.class);
     private final Map<String, UnsubscribeRequest> unsubscriptions = new ConcurrentHashMap<>();

    /**
     * Constructor.
     *
     * @param mqttClient is from package of com.aws.iot.evergreen.mqtt to replace
     *                   the old MqttClient from software.amazon.awssdk.crt.mqtt.MqttClient
     */
    @Inject
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
            software.amazon.awssdk.crt.mqtt.MqttClient oldMqttClient =
                     new software.amazon.awssdk.crt.mqtt.MqttClient(clientBootstrap);) {
            String clientId = "fakeClientId";
            String endpoint = "fakeEndpoint";
            int portNumber = 1;
            MqttConnectionConfig fakeConfig = new MqttConnectionConfig();
            fakeConfig.setMqttClient(oldMqttClient);
            fakeConfig.setPort(portNumber);
            fakeConfig.setClientId(clientId);
            fakeConfig.setEndpoint(endpoint);
            return fakeConfig;
        }
    }

    @Override
    public CompletableFuture<Integer> subscribe(String topic, QualityOfService qos, Consumer<MqttMessage> handler) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        SubscribeRequest request = SubscribeRequest.builder()
                .topic(topic).qos(qos).callback(handler).build();
        UnsubscribeRequest unsubscribeRequest = UnsubscribeRequest.builder()
                .topic(request.getTopic()).callback(request.getCallback()).build();
        unsubscriptions.put(request.getTopic(), unsubscribeRequest);
        try {
            mqttClient.subscribe(request);
            future.complete(0);
            return future;
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            unsubscriptions.remove(request.getTopic());
            future.completeExceptionally(e);
            return future;
        }
    }

    @Override
    public CompletableFuture<Integer> subscribe(String topic, QualityOfService qos) {
        String errMsg = "The operation of subscribe is not supported because the request's callback should be non-null";
        UnsupportedOperationException e = new UnsupportedOperationException(errMsg);
        logger.atError().setCause(e).log(errMsg);
        throw e;
    }

    @Override
    public CompletableFuture<Integer> publish(MqttMessage message, QualityOfService qos, boolean retain) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        try {
            String topic = message.getTopic();
            byte[] payload = message.getPayload();
            PublishRequest publishRequest = PublishRequest.builder()
                    .topic(topic).retain(retain).payload(payload).build();
            mqttClient.publish(publishRequest);
            future.complete(0);
            return future;
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            future.completeExceptionally(e);
            return future;
        }
    }

    @Override
    public CompletableFuture<Integer> unsubscribe(String topic) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        UnsubscribeRequest request = getUnsubscribeRequest(topic);
        try {
            mqttClient.unsubscribe(request);
            unsubscriptions.remove(topic);
            future.complete(0);
            return future;
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            future.completeExceptionally(e);
            return future;
        }
    }

    @Override
    public CompletableFuture<Boolean> connect() {
        String errMsg = "The operation of connect is not supported by the class of WrapperMqttClientConnection";
        UnsupportedOperationException e = new UnsupportedOperationException(errMsg);
        logger.atError().setCause(e).log(errMsg);
        throw e;
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        String errMsg = "The operation of disconnect is not supported by the class of WrapperMqttClientConnection";
        UnsupportedOperationException e = new UnsupportedOperationException(errMsg);
        logger.atError().setCause(e).log(errMsg);
        throw e;
    }


    @Override
    public void onMessage(Consumer<MqttMessage> handler) {
        String errMsg = "The operation of onMessage is not supported by the class of WrapperMqttClientConnection";
        UnsupportedOperationException e = new UnsupportedOperationException(errMsg);
        logger.atError().setCause(e).log(errMsg);
        throw e;
    }

    private UnsubscribeRequest getUnsubscribeRequest(String subscriptionTopic) {
        return unsubscriptions.get(subscriptionTopic);
    }
}
