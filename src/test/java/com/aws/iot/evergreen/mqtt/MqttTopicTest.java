/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.mqtt;


import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MqttTopicTest {

    @Test
    void simple_equality_no_wildcards() {
        assertTrue(MqttTopic.topicIsSupersetOf("A", "A"));
        assertTrue(MqttTopic.topicIsSupersetOf("A/B", "A/B"));
        assertTrue(MqttTopic.topicIsSupersetOf("A/B/C", "A/B/C"));

        assertFalse(MqttTopic.topicIsSupersetOf("A", "B"));
        assertFalse(MqttTopic.topicIsSupersetOf("A/B", "A/C"));
        assertFalse(MqttTopic.topicIsSupersetOf("A/B/C", "A/B/D"));
        assertFalse(MqttTopic.topicIsSupersetOf("A/B/C", "A/B/C/D"));
    }

    @Test
    void single_level_wildcards() {
        assertTrue(MqttTopic.topicIsSupersetOf("+", "A"));
        assertTrue(MqttTopic.topicIsSupersetOf("+", "X"));
        assertTrue(MqttTopic.topicIsSupersetOf("A/+", "A/B"));
        assertTrue(MqttTopic.topicIsSupersetOf("+/B", "A/B"));
        assertTrue(MqttTopic.topicIsSupersetOf("A/+/C", "A/B/C"));

        assertFalse(MqttTopic.topicIsSupersetOf("A/+", "B"));
        assertFalse(MqttTopic.topicIsSupersetOf("A/+", "B/C"));
        assertFalse(MqttTopic.topicIsSupersetOf("A/+/C", "A/B/D"));
        assertFalse(MqttTopic.topicIsSupersetOf("A/+/C", "A/B/C/D"));
    }

    @Test
    void multi_level_wildcard() {
        assertTrue(MqttTopic.topicIsSupersetOf("#", "A"));
        assertTrue(MqttTopic.topicIsSupersetOf("#", "A/B"));
        assertTrue(MqttTopic.topicIsSupersetOf("A/#", "A/B"));
        assertTrue(MqttTopic.topicIsSupersetOf("A/#", "A/B/C"));

        assertFalse(MqttTopic.topicIsSupersetOf("A/#", "B"));
        assertFalse(MqttTopic.topicIsSupersetOf("A/#", "B/C"));
    }

    @Test
    void superset_single_level_wildcards() {
        assertTrue(MqttTopic.topicIsSupersetOf("+", "+"));
        assertTrue(MqttTopic.topicIsSupersetOf("+", "X"));
        assertTrue(MqttTopic.topicIsSupersetOf("A/+", "A/B"));
        assertTrue(MqttTopic.topicIsSupersetOf("A/+", "A/D"));
        assertTrue(MqttTopic.topicIsSupersetOf("A/+/B", "A/D/B"));

        assertFalse(MqttTopic.topicIsSupersetOf("A/+/C", "A/B/+"));
        assertFalse(MqttTopic.topicIsSupersetOf("+/B", "A/+"));
        assertFalse(MqttTopic.topicIsSupersetOf("A/+", "+/B"));
        assertFalse(MqttTopic.topicIsSupersetOf("A/B/+/+", "A/B/+"));
        assertFalse(MqttTopic.topicIsSupersetOf("A/B/+", "A/+/C"));
        assertFalse(MqttTopic.topicIsSupersetOf("A/+", "B/C"));
        assertFalse(MqttTopic.topicIsSupersetOf("A/+/C", "A/B/+/+"));
    }

    @Test
    void superset_multi_level_wildcard() {
        assertTrue(MqttTopic.topicIsSupersetOf("A/#", "A/B/#"));
        assertTrue(MqttTopic.topicIsSupersetOf("A/+/#", "A/B/#"));
        assertTrue(MqttTopic.topicIsSupersetOf("A/B/#", "A/B/C"));
        assertTrue(MqttTopic.topicIsSupersetOf("A/B/#", "A/B/C/#"));

        assertFalse(MqttTopic.topicIsSupersetOf("A/#", "#"));
        assertFalse(MqttTopic.topicIsSupersetOf("A/B/C", "A/B/#"));
        assertFalse(MqttTopic.topicIsSupersetOf("A/B/C/#", "A/B/#"));
        assertFalse(MqttTopic.topicIsSupersetOf("A/#", "B/C"));
    }

    @Test
    void find_most_specific_topic() {
        assertEquals(new MqttTopic("A/B/C"), new MqttTopic("A/B/C").mostSpecificSubscription(
                Arrays.asList(new MqttTopic("A/#"), new MqttTopic("A/B/+"), new MqttTopic("A/B/C"))));
        assertEquals(new MqttTopic("A/B/+"), new MqttTopic("A/B/C")
                .mostSpecificSubscription(Arrays.asList(new MqttTopic("A/#"), new MqttTopic("A/B/+"))));

        assertEquals(new MqttTopic("A/B/C/#"), new MqttTopic("A/B/C/D/E/F").mostSpecificSubscription(
                Arrays.asList(new MqttTopic("A/#"), new MqttTopic("A/B/C/#"), new MqttTopic("A/B/+"))));
    }

    @Test
    void orderby_specificity() {
        List<MqttTopic> ordered = Stream.of(new MqttTopic("A/B"), new MqttTopic("A/B/C/D/E"), new MqttTopic("A/B/#"),
                new MqttTopic("A/#"), new MqttTopic("A/+/B"), new MqttTopic("A/B/C"), new MqttTopic("A/+/+/C"),
                new MqttTopic("A/+/+/C/D"), new MqttTopic("A/+/B/C")).sorted().collect(Collectors.toList());
        assertEquals("A/B/C/D/E", ordered.get(0).toString());
        assertEquals("A/B/C", ordered.get(1).toString());
        assertEquals("A/B", ordered.get(2).toString());
        assertEquals("A/+/B/C", ordered.get(3).toString());
        assertEquals("A/+/B", ordered.get(4).toString());
        assertEquals("A/+/+/C/D", ordered.get(5).toString());
        assertEquals("A/+/+/C", ordered.get(6).toString());
        assertEquals("A/B/#", ordered.get(7).toString());
        assertEquals("A/#", ordered.get(8).toString());
    }
}
