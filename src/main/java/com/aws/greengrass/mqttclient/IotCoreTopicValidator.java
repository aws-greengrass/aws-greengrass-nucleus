/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import com.aws.greengrass.util.Utils;
import lombok.NonNull;

import java.util.Optional;
import java.util.regex.Pattern;

import static com.aws.greengrass.mqttclient.MqttClient.MQTT_VERSION_5;

public final class IotCoreTopicValidator {

    public enum Operation {
        PUBLISH, SUBSCRIBE
    }

    private static final int TOPIC_MAX_NUMBER_OF_FORWARD_SLASHES = 7;
    public static final int MAX_LENGTH_OF_TOPIC = 256;
    private static final int MAX_LENGTH_FOR_UNKNOWN_RESERVED_TOPIC = 512;

    private static final String SHARED_SUBSCRIPTION_TEMPLATE = "^\\$share/\\S+/\\S+";
    private static final String SHARED_SUBSCRIPTION_PREFIX = "^\\$share/\\S+?/";
    private static final String DIRECT_INGEST_TEMPLATE = "^\\$aws/rules/\\S+/\\S+";
    private static final String DIRECT_INGEST_PREFIX = "^\\$aws/rules/\\S+?/";

    private static final char RESERVED_TOPIC_PREFIX = '$';
    private static final char FORWARD_SLASH = '/';
    private static final String MULTI_LEVEL_WILDCARD = "#";
    private static final String SINGLE_LEVEL_WILDCARD = "+";

    private static final String ERROR_PUBLISH_TOPIC_TOO_LONG = String.format("The topic size of request must be no "
            + "larger than %d bytes of UTF-8 encoded characters. This excludes the first "
            + "3 mandatory segments for Basic Ingest topics ($AWS/rules/rule-name/)", MAX_LENGTH_OF_TOPIC);
    private static final String ERROR_SUBSCRIBE_TOPIC_TOO_LONG =
            String.format("%s or first 2 mandatory segments for MQTT Shared Subscriptions ($share/share-name/)",
                    ERROR_PUBLISH_TOPIC_TOO_LONG);
    private static final String ERROR_UNKNOWN_RESERVED_TOPIC_TOO_LONG = String.format(
            "Reserved topic total length is greater than %d bytes of UTF-8 encoded characters "
                    + "and is most likely over the IoT Core limit of %d bytes (excluding prefixes).",
            MAX_LENGTH_FOR_UNKNOWN_RESERVED_TOPIC, MAX_LENGTH_OF_TOPIC);
    private static final String ERROR_TOPIC_HAS_TOO_MANY_SLASHES = String.format(
            "The request topic must have no more than %d forward slashes (/)", TOPIC_MAX_NUMBER_OF_FORWARD_SLASHES);
    private static final String ERROR_DIRECT_INGEST_TOPIC_EMPTY =
            "Effective direct ingest topic (without '$aws/rules/rule-name/' prefix) is empty";
    private static final String ERROR_SHARED_SUBSCRIPTION_TOPIC_EMPTY =
            "Effective shared subscription topic (without '$share/share-group/' prefix) is empty";
    private static final String ERROR_WILDCARD_IN_PUBLISH_TOPIC =
            "Publish topics must not contain wildcard characters of '#' or '+'";
    private static final String ERROR_EMPTY_TOPIC = "Topic must not be empty";

    private IotCoreTopicValidator() {
    }

    /**
     * Check that a given topic adheres to IoT Core limits, such as number of forward slashes and length.
     *
     * @param topic topic
     * @param mqttVersion mqtt version (mqtt3, mqtt5)
     * @param operation operation
     * @throws MqttRequestException if the topic is deemed to be invalid
     */
    public static void validateTopic(@NonNull String topic, @NonNull String mqttVersion, @NonNull Operation operation)
            throws MqttRequestException {
        if (Utils.isEmpty(topic)) {
            throw new MqttRequestException(ERROR_EMPTY_TOPIC);
        }

        if (operation == Operation.PUBLISH && containsWildcards(topic)) {
            throw new MqttRequestException(ERROR_WILDCARD_IN_PUBLISH_TOPIC);
        }

        if (topic.charAt(0) != RESERVED_TOPIC_PREFIX) {
            validateEffectiveTopic(topic, operation);
            return;
        }

        // validate reserved topics

        boolean isMQTT5 = MQTT_VERSION_5.equalsIgnoreCase(mqttVersion);

        // shared subscription topics (mqtt5 only)
        if (isMQTT5 && Pattern.matches(SHARED_SUBSCRIPTION_TEMPLATE, topic)) {
            if (operation == Operation.SUBSCRIBE) {
                String effectiveTopic = removePrefix(topic, SHARED_SUBSCRIPTION_PREFIX)
                        .orElseThrow(() -> new MqttRequestException(ERROR_SHARED_SUBSCRIPTION_TOPIC_EMPTY));
                validateEffectiveTopic(effectiveTopic, operation);
            } else {
                validateEffectiveTopic(topic, operation);
            }
            return;
        }

        // direct ingest topics
        if (Pattern.matches(DIRECT_INGEST_TEMPLATE, topic)) {
            String effectiveTopic = removePrefix(topic, DIRECT_INGEST_PREFIX)
                    .orElseThrow(() -> new MqttRequestException(ERROR_DIRECT_INGEST_TOPIC_EMPTY));
            validateEffectiveTopic(effectiveTopic, operation);
            return;
        }

        // unknown reserved topic
        if (isMQTT5) {
            // rely on IoT Core to perform topic size and forward slash validation,
            // rather than attempt to keep track of every known IoT reserved topic.
            // just check a reasonably large topic size to limit large payloads from being sent.
            if (topic.length() > MAX_LENGTH_FOR_UNKNOWN_RESERVED_TOPIC) {
                throw new MqttRequestException(ERROR_UNKNOWN_RESERVED_TOPIC_TOO_LONG);
            }
        } else {
            // treat as normal topic for mqtt3
            validateEffectiveTopic(topic, operation);
        }
    }

    private static void validateEffectiveTopic(String effectiveTopic, Operation operation) throws MqttRequestException {
        if (effectiveTopic.chars().filter(num -> num == FORWARD_SLASH).count() > TOPIC_MAX_NUMBER_OF_FORWARD_SLASHES) {
            throw new MqttRequestException(ERROR_TOPIC_HAS_TOO_MANY_SLASHES);
        }
        if (effectiveTopic.length() > MAX_LENGTH_OF_TOPIC) {
            throw new MqttRequestException(
                    operation == Operation.SUBSCRIBE ? ERROR_SUBSCRIBE_TOPIC_TOO_LONG : ERROR_PUBLISH_TOPIC_TOO_LONG);
        }
    }

    /**
     * Remove the given prefix from the topic.
     *
     * @param topic non-null, non-empty, trimmed topic
     * @param prefixRegex prefix to remove from topic
     * @return topic without prefix
     */
    private static Optional<String> removePrefix(String topic, String prefixRegex) {
        String[] firstAndRest = topic.split(prefixRegex, 2);
        if (firstAndRest.length == 2) {
            return Optional.of(firstAndRest[1]);
        }
        return Optional.empty();
    }

    private static boolean containsWildcards(String topic) {
        return topic.contains(MULTI_LEVEL_WILDCARD) || topic.contains(SINGLE_LEVEL_WILDCARD);
    }
}
