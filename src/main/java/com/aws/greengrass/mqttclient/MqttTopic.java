/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Arrays;
import java.util.List;

@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class MqttTopic {
    private static final String SINGLE_LEVEL_WILDCARD = "+";
    private static final String MULTILEVEL_WILDCARD = "#";
    private static final String TOPIC_PATH_SEPARATOR = "/";

    @Getter
    @EqualsAndHashCode.Include
    private final String topic;
    private final List<String> subscriptionParts;

    /**
     * Constructor.
     *
     * @param topic string topic
     */
    public MqttTopic(String topic) {
        this.topic = topic;
        subscriptionParts = Arrays.asList(topic.split(TOPIC_PATH_SEPARATOR));
    }

    /**
     * Returns true if the topic that is the current instance is a superset (includes) or equals the provided topic
     * string. For example, A/B and A/B would be true because they are equal. A/+ and A/B would also work because of the
     * wildcard, as would A/# and A/B/C/# because the first one's wildcard includes all of the second.
     *
     * @param testTopic topic to compare against
     * @return true if this instance equals or contains the testTopic
     */
    public boolean isSupersetOf(MqttTopic testTopic) {
        if (this.topic.equals(testTopic.topic)) {
            return true;
        }

        int i;
        for (i = 0; i < Math.min(subscriptionParts.size(), testTopic.subscriptionParts.size()); i++) {
            if (MULTILEVEL_WILDCARD.equals(subscriptionParts.get(i))) {
                return true;
            }
            if (SINGLE_LEVEL_WILDCARD.equals(subscriptionParts.get(i))) {
                continue; // single wildcard, continue to match on the rest of the topic
            }
            String testStr = testTopic.subscriptionParts.get(i);
            if (!subscriptionParts.get(i).equals(testStr)) {
                return false;
            }
        }
        // If we didn't make it to the end of either topic, then it doesn't match
        return i >= testTopic.subscriptionParts.size() && i >= subscriptionParts.size();
    }

    public static boolean topicIsSupersetOf(String topic, String testTopic) {
        return new MqttTopic(topic).isSupersetOf(new MqttTopic(testTopic));
    }

    @Override
    public String toString() {
        return topic;
    }
}
