package com.aws.iot.evergreen.mqtt;

import com.aws.iot.evergreen.deployment.IotJobsHelper;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import software.amazon.awssdk.crt.io.ClientBootstrap;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.HostResolver;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttConnectionConfig;
//import software.amazon.awssdk.crt.mqtt.MqttException;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public class WrapperMqttClientConnection extends MqttClientConnection {

     private final MqttClient mqttClient;
     private static final Logger logger = LogManager.getLogger(IotJobsHelper.class);

    /**
     * Constructor.
     *
     * @param mqttClient is from package of com.aws.iot.evergreen.mqtt to replace
     *                   the old MqttClient from software.amazon.awssdk.crt.mqtt.MqttClient
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
        try (EventLoopGroup eventLoopGroup = new EventLoopGroup(1);
            HostResolver resolver = new HostResolver(eventLoopGroup);
            ClientBootstrap clientBootstrap = new ClientBootstrap(eventLoopGroup, resolver);
            software.amazon.awssdk.crt.mqtt.MqttClient oldMqttClient =
                     new software.amazon.awssdk.crt.mqtt.MqttClient(clientBootstrap);) {
            String clientId = "clientId";
            String endpoint = "endpoint";
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
        try {
            SubscribeRequest request = SubscribeRequest.builder().topic(topic).qos(qos).callback(handler).build();
            mqttClient.subscribe(request);
            future.complete(0);
            return future;
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            future.completeExceptionally(e);
            return future;
        }
    }

    @Override
    public CompletableFuture<Integer> subscribe(String topic, QualityOfService qos) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        String errMsg = "The operation of subscribe is not supported because the request's callback should be non-null";
        future.completeExceptionally(new UnsupportedOperationException(errMsg));
        return future;
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
    public CompletableFuture<Boolean> connect() {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        String errMsg = "The operation of connect is not supported by the class of WrapperMqttClientConnection";
        future.completeExceptionally(new UnsupportedOperationException(errMsg));
        return future;
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        String errMsg = "The operation of disconnect is not supported by the class of WrapperMqttClientConnection";
        future.completeExceptionally(new UnsupportedOperationException(errMsg));
        return future;
    }

    @Override
    public CompletableFuture<Integer> unsubscribe(String topic) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        String errMsg = "The operation of unsubscribe is not supported by the class of WrapperMqttClientConnection";
        future.completeExceptionally(new UnsupportedOperationException(errMsg));
        return future;
    }

    @Override
    public void onMessage(Consumer<MqttMessage> handler) {
        String errMsg = "The operation of onMessage is not supported by the class of WrapperMqttClientConnection";
        try {
            throw new UnsupportedOperationException(errMsg);
        } catch (UnsupportedOperationException e) {
            logger.atError().setCause(e).log(errMsg);
        }
    }

    @Override
    protected void releaseNativeHandle() {
        String errMsg = "The operation of releaseNativeHandle is not supported "
                + "by the class of WrapperMqttClientConnection";
        try {
            throw new UnsupportedOperationException(errMsg);
        } catch (UnsupportedOperationException e) {
            logger.atError().setCause(e).log(errMsg);
        }
    }

    @Override
    protected boolean canReleaseReferencesImmediately() {
        String errMsg = "The operation of canReleaseReferencesImmediately is not supported "
                + "by the class of WrapperMqttClientConnection";
        try {
            throw new UnsupportedOperationException(errMsg);
        } catch (UnsupportedOperationException e) {
            logger.atError().setCause(e).log(errMsg);
        }
        return true;
    }
}
