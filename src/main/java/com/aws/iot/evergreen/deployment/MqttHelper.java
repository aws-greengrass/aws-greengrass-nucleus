/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.deployment;

import com.amazonaws.services.iot.client.AWSIotException;
import com.amazonaws.services.iot.client.AWSIotMessage;
import com.amazonaws.services.iot.client.AWSIotMqttClient;
import com.amazonaws.services.iot.client.AWSIotQos;
import com.amazonaws.services.iot.client.AWSIotTopic;
import com.aws.iot.evergreen.deployment.utils.SampleUtil;
import com.aws.iot.evergreen.util.Log;

import java.util.function.Consumer;
import javax.inject.Inject;

/**
 * Helper class to publish/subscribe to AWS Iot mqtt topics.
 */
public class MqttHelper {

    @Inject
    private static Log logger;

    AWSIotMqttClient client;

    /**
     * Constructor for MqttHelper.
     * @param clientEndpoint Custom endpoint for the aws account
     * @param clientId ClientId for the connection
     * @param certificateFile File path of the Iot thing certificate
     * @param privateKeyFile File path of the Iot thing private key
     */
    public MqttHelper(String clientEndpoint, String clientId, String certificateFile, String privateKeyFile) {
        SampleUtil.KeyStorePasswordPair pair = SampleUtil.getKeyStorePasswordPair(certificateFile, privateKeyFile);
        this.client = new AWSIotMqttClient(clientEndpoint, clientId, pair.keyStore, pair.keyPassword);

        try {
            this.client.connect();
        } catch (AWSIotException e) {
            logger.log(Log.Level.Error, "Caught AWS Iot Exception");
        }
    }

    public static class EvergreenDeviceAWSIotTopic extends AWSIotTopic {

        Consumer<AWSIotMessage> mqttHandler;

        public EvergreenDeviceAWSIotTopic(String topic, AWSIotQos qos, Consumer<AWSIotMessage> mqttHandler) {
            super(topic, qos);
            this.mqttHandler = mqttHandler;
        }

        @Override
        public void onMessage(AWSIotMessage message) {
            mqttHandler.accept(message);
        }
    }

    public void subscribe(String topic, Consumer<AWSIotMessage> mqttHandler) throws AWSIotException {
        EvergreenDeviceAWSIotTopic awsIotTopic = new EvergreenDeviceAWSIotTopic(topic, AWSIotQos.QOS0, mqttHandler);
        client.subscribe(awsIotTopic);
    }

    public void publish(String topic, String payload) throws AWSIotException {
        client.publish(topic, AWSIotQos.QOS0, payload);
    }
}
