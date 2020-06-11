/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.mqtt;


import java.util.Arrays;
import java.util.List;

public class MqttTopic {
    private static final String SINGLE_LEVEL_WILDCARD = "+";
    private static final String MULTILEVEL_WILDCARD = "#";
    private static final String TOPIC_PATH_SEPARATOR = "/";

    private final String topic;
    private final List<String> subscriptionParts;

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
    public boolean includes(String testTopic) {
        if (this.topic.equals(testTopic)) {
            return true;
        }

        MqttTopic tester = new MqttTopic(testTopic);

        int i;
        for (i = 0; i < Math.min(subscriptionParts.size(), tester.subscriptionParts.size()); i++) {
            if (MULTILEVEL_WILDCARD.equals(subscriptionParts.get(i))) {
                return true;
            }
            if (SINGLE_LEVEL_WILDCARD.equals(subscriptionParts.get(i))) {
                continue; // single wildcard, continue to match on the rest of the topic
            }
            String testStr = tester.subscriptionParts.get(i);
            if (!subscriptionParts.get(i).equals(testStr)) {
                return false;
            }
        }
        // If we didn't make it to the end of either topic, then it doesn't match
        return i >= tester.subscriptionParts.size() && i >= subscriptionParts.size();
    }

    public static boolean topicIncludes(String topic, String testTopic) {
        return new MqttTopic(topic).includes(testTopic);
    }
}
