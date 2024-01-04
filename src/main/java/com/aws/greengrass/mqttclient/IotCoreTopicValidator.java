/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import com.aws.greengrass.util.Utils;
import lombok.NonNull;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.aws.greengrass.mqttclient.MqttClient.MQTT_VERSION_5;


public final class IotCoreTopicValidator {

    public enum Operation {
        PUBLISH,
        SUBSCRIBE
    }

    public static final int TOPIC_MAX_NUMBER_OF_FORWARD_SLASHES = 7;
    public static final int MAX_LENGTH_OF_TOPIC = 256;
    private static final int MAX_LENGTH_FOR_UNKNOWN_RESERVED_TOPIC = 512;

    private static final String SHARED_SUBSCRIPTION_TEMPLATE = "^\\$share/\\S+/\\S+";
    private static final String SHARED_SUBSCRIPTION_PREFIX = "^\\$share/\\S+?/";
    private static final String DIRECT_INGEST_TEMPLATE = "^\\$aws/rules/\\S+/\\S+";
    private static final String DIRECT_INGEST_PREFIX = "^\\$aws/rules/\\S+?/";

    private IotCoreTopicValidator() {
    }

    /**
     * Check that a given topic adheres to IoT Core limits,
     * such as number of forward slashes and length.
     *
     * @param topic       topic
     * @param mqttVersion mqtt version (mqtt3, mqtt5)
     * @param operation   operation
     * @throws MqttRequestException if the topic is deemed to be invalid
     */
    public static void validateTopic(@NonNull String topic,
                                     @NonNull String mqttVersion,
                                     @NonNull Operation operation) throws MqttRequestException {
        if (Utils.isEmpty(topic)) {
            throw new MqttRequestException("Topic must not be empty");
        }

        if (operation == Operation.PUBLISH && (topic.contains("#") || topic.contains("+"))) {
            throw new MqttRequestException("Publish topics must not contain wildcard "
                    + "characters of '#' or '+'");
        }

        topic = topic.toLowerCase().trim();

        if (topic.charAt(0) != '$') {
            validateEffectiveTopic(topic, operation);
            return;
        }

        // validate reserved topics

        boolean isMQTT5 = MQTT_VERSION_5.equalsIgnoreCase(mqttVersion);

        // shared subscription topics (mqtt5 only)
        if (isMQTT5 && Pattern.matches(SHARED_SUBSCRIPTION_TEMPLATE, topic)) {
            if (operation == Operation.SUBSCRIBE) {
                String effectiveTopic = removePrefix(topic, SHARED_SUBSCRIPTION_PREFIX)
                        .orElseThrow(() -> new MqttRequestException(
                                "Effective shared subscription topic (without '$share/share-group/' prefix) is empty"));
                validateEffectiveTopic(effectiveTopic, operation);
            } else {
                validateEffectiveTopic(topic, operation);
            }
            return;
        }

        // direct ingest topics
        if (Pattern.matches(DIRECT_INGEST_TEMPLATE, topic)) {
            String effectiveTopic = removePrefix(topic, DIRECT_INGEST_PREFIX)
                    .orElseThrow(() -> new MqttRequestException(
                            "Effective direct ingest topic (without '$aws/rules/rule-name/' prefix) is empty"));
            validateEffectiveTopic(effectiveTopic, operation);
            return;
        }

        // unknown reserved topic
        if (isMQTT5) {
            // rely on IoT Core to perform topic size and forward slash validation,
            // rather than attempt to keep track of every known IoT reserved topic.
            // just check a reasonably large topic size to limit large payloads from being sent.
            if (topic.length() > MAX_LENGTH_FOR_UNKNOWN_RESERVED_TOPIC) {
                String msg = String.format(
                        "Reserved topic (%s...) total length is greater than %d bytes of UTF-8 encoded characters "
                                + "and is most likely over the IoT Core limit of %d bytes (excluding prefixes).",
                        prefix(topic, 3), MAX_LENGTH_FOR_UNKNOWN_RESERVED_TOPIC, MAX_LENGTH_OF_TOPIC);
                throw new MqttRequestException(msg);
            }
        } else {
            // treat as normal topic for mqtt3
            validateEffectiveTopic(topic, operation);
        }
    }

    @SuppressWarnings("PMD.UseStringBufferForStringAppends")
    private static void validateEffectiveTopic(String effectiveTopic, Operation operation) throws MqttRequestException {
        if (effectiveTopic.chars().filter(num -> num == '/').count() > TOPIC_MAX_NUMBER_OF_FORWARD_SLASHES) {
            String msg = String.format("The request topic must have no more than %d forward slashes (/)",
                    TOPIC_MAX_NUMBER_OF_FORWARD_SLASHES);
            throw new MqttRequestException(msg);
        }
        if (effectiveTopic.length() > MAX_LENGTH_OF_TOPIC) {
            String msg = String.format("The topic size of request must be no "
                            + "larger than %d bytes of UTF-8 encoded characters. This excludes the first "
                            + "3 mandatory segments for Basic Ingest topics ($AWS/rules/rule-name/)",
                    MAX_LENGTH_OF_TOPIC);
            if (operation == Operation.SUBSCRIBE) {
                msg += " or first 2 mandatory segments for MQTT Shared Subscriptions ($share/share-name/)";
            }
            throw new MqttRequestException(msg);
        }
    }

    /**
     * Remove the given prefix from the topic.
     *
     * @param topic       non-null, non-empty, trimmed topic
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

    /**
     * Extract the prefix from a topic, with n number of topic parts.
     *
     * <p>Example: prefix("a/b/c/d", 2) -> "a/b"
     *
     * @param topic  non-null, non-empty, trimmed topic
     * @param numParts number of topic parts to include in the result
     * @return topic prefix
     */
    private static String prefix(String topic, int numParts) {
        String[] parts = topic.split("/");
        return Arrays.stream(parts)
                .limit(Math.min(parts.length, numParts))
                .collect(Collectors.joining("/"));
    }
}
