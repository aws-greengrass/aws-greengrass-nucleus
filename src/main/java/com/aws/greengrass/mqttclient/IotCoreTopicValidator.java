/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.util.Utils;

import java.util.regex.Pattern;
import javax.inject.Inject;


public class IotCoreTopicValidator {

    public enum Operation {
        PUBLISH,
        SUBSCRIBE
    }

    public static final int TOPIC_MAX_NUMBER_OF_FORWARD_SLASHES = 7;
    public static final int MAX_LENGTH_OF_TOPIC = 256;

    private static final String SHARED_SUBSCRIPTION_TEMPLATE = "^\\$share/\\S+/\\S+";
    private static final String SHARED_SUBSCRIPTION_PREFIX = "^\\$share/\\S+?/";
    private static final String DIRECT_INGEST_TEMPLATE = "^\\$aws/rules/\\S+/\\S+";
    private static final String DIRECT_INGEST_PREFIX = "^\\$aws/rules/\\S+?/";

    private final DeviceConfiguration deviceConfiguration;

    @Inject
    public IotCoreTopicValidator(DeviceConfiguration deviceConfiguration) {
        this.deviceConfiguration = deviceConfiguration;
    }

    /**
     * Check that a given topic adheres to IoT Core limits,
     * such as number of forward slashes and length.
     *
     * @param topic topic
     * @param operation operation
     * @throws MqttRequestException if the topic is deemed to be invalid
     */
    public void validateTopic(String topic, Operation operation) throws MqttRequestException {
        if (Utils.isEmpty(topic)) {
            throw new MqttRequestException("Topic must not be empty");
        }

        if (operation == Operation.PUBLISH && (topic.contains("#") || topic.contains("+"))) {
            throw new MqttRequestException("Publish topics must not contain wildcard "
                    + "characters of '#' or '+'");
        }

        topic = topic.toLowerCase();

        if (topic.charAt(0) != '$') {
            validateEffectiveTopic(topic);
            return;
        }

        boolean isMqtt3 = "mqtt3".equalsIgnoreCase(deviceConfiguration.getMQTTVersion());

        if (!isMqtt3 && Pattern.matches(SHARED_SUBSCRIPTION_TEMPLATE, topic)) {
            if (operation == Operation.SUBSCRIBE) {
                validateSharedSubscriptionTopic(topic);
            } else {
                validateEffectiveTopic(topic);
            }
            return;
        }

        if (Pattern.matches(DIRECT_INGEST_TEMPLATE, topic)) {
            validateDirectIngestTopic(topic);
            return;
        }

        if (isMqtt3) {
            validateEffectiveTopic(topic);
            return;
        }

        // unknown reserved topic.
        // rely on IoT Core to perform topic size and forward slash validation,
        // rather than attempt to keep track of every known IoT reserved topic.
        // just check a reasonably large topic size to limit large payloads from being sent.
        if (topic.length() > MAX_LENGTH_OF_TOPIC * 2) {
            String errMsg = String.format("The topic size of request must be no "
                            + "larger than %d bytes of UTF-8 encoded characters.",
                    MAX_LENGTH_OF_TOPIC);
            throw new MqttRequestException(errMsg);
        }
    }

    private void validateSharedSubscriptionTopic(String topic) throws MqttRequestException {
        validateEffectiveTopic(topic.split(SHARED_SUBSCRIPTION_PREFIX, 2)[1]);
    }

    private void validateDirectIngestTopic(String topic) throws MqttRequestException {
        validateEffectiveTopic(topic.split(DIRECT_INGEST_PREFIX, 2)[1]);
    }

    private void validateEffectiveTopic(String effectiveTopic) throws MqttRequestException {
        if (effectiveTopic.chars().filter(num -> num == '/').count() > TOPIC_MAX_NUMBER_OF_FORWARD_SLASHES) {
            String errMsg = String.format("The request topic must have no more than %d forward slashes (/)",
                    TOPIC_MAX_NUMBER_OF_FORWARD_SLASHES);
            throw new MqttRequestException(errMsg);
        }
        if (effectiveTopic.length() > MAX_LENGTH_OF_TOPIC) {
            String errMsg = String.format("The topic size of request must be no "
                            + "larger than %d bytes of UTF-8 encoded characters. This excludes the first "
                            + "3 mandatory segments for Basic Ingest topics ($AWS/rules/rule-name/), "
                            + "or first 2 mandatory segments for MQTT Shared Subscriptions ($share/share-name/)",
                    MAX_LENGTH_OF_TOPIC);
            throw new MqttRequestException(errMsg);
        }
    }
}
