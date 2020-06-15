/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.mqtt;


import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class MqttTopic implements Comparable<MqttTopic> {
    private static final String SINGLE_LEVEL_WILDCARD = "+";
    private static final String MULTILEVEL_WILDCARD = "#";
    private static final String TOPIC_PATH_SEPARATOR = "/";

    @Getter
    @EqualsAndHashCode.Include
    private final String topic;
    private final List<String> subscriptionParts;
    private final int singleLevelWildcardCount;
    private final boolean usingMultilevelWildcard;

    public MqttTopic(String topic) {
        this.topic = topic;
        subscriptionParts = Arrays.asList(topic.split(TOPIC_PATH_SEPARATOR));
        usingMultilevelWildcard = topic.endsWith(MULTILEVEL_WILDCARD);
        singleLevelWildcardCount = (int) topic.chars().filter(c -> c == '+').count();
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

    /**
     * Finds the most specific subscription topic in the given list of subscriptions which includes
     * this topic. ie. For this topic being A/B/C and the potentials being A/#, A/B/+, and A/B/C it should
     * return A/B/C since this is the most specific match.
     *
     * @param possibleSubscriptions subscriptions to check for
     * @return most specific topic from the given list
     */
    public MqttTopic mostSpecificSubscription(Collection<MqttTopic> possibleSubscriptions) {
        return possibleSubscriptions.stream().filter(t -> t.isSupersetOf(this)).sorted().findFirst().get();
    }

    public static boolean topicIsSupersetOf(String topic, String testTopic) {
        return new MqttTopic(topic).isSupersetOf(new MqttTopic(testTopic));
    }

    @Override
    public int compareTo(MqttTopic t) {
        if (equals(t)) {
            return 0;
        }

        // The more specific the topic is, the more positive it should be (and vice-versa)

        // Multilevel wildcards are the most inspecific, so return a very large number
        if (t.usingMultilevelWildcard && !usingMultilevelWildcard) {
            return Integer.MIN_VALUE + t.subscriptionParts.size();
        } else if (usingMultilevelWildcard && !t.usingMultilevelWildcard) {
            return Integer.MAX_VALUE - subscriptionParts.size();
        }

        // Single level wildcards are the next most specific
        if (t.singleLevelWildcardCount > singleLevelWildcardCount) {
            return -2;
        } else if (singleLevelWildcardCount > t.singleLevelWildcardCount) {
            return 2;
        }

        // All else being equal, order by the number of topic parts with longer being "more specific"
        return t.subscriptionParts.size() - subscriptionParts.size();
    }

    @Override
    public String toString() {
        return topic;
    }
}
