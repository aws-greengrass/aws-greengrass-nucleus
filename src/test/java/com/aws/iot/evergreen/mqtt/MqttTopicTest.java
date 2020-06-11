/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.mqtt;


import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MqttTopicTest {

    @Test
    void simple_equality_no_wildcards() {
        assertTrue(MqttTopic.topicIncludes("A", "A"));
        assertTrue(MqttTopic.topicIncludes("A/B", "A/B"));
        assertTrue(MqttTopic.topicIncludes("A/B/C", "A/B/C"));

        assertFalse(MqttTopic.topicIncludes("A", "B"));
        assertFalse(MqttTopic.topicIncludes("A/B", "A/C"));
        assertFalse(MqttTopic.topicIncludes("A/B/C", "A/B/D"));
        assertFalse(MqttTopic.topicIncludes("A/B/C", "A/B/C/D"));
    }

    @Test
    void single_level_wildcards() {
        assertTrue(MqttTopic.topicIncludes("+", "A"));
        assertTrue(MqttTopic.topicIncludes("+", "X"));
        assertTrue(MqttTopic.topicIncludes("A/+", "A/B"));
        assertTrue(MqttTopic.topicIncludes("+/B", "A/B"));
        assertTrue(MqttTopic.topicIncludes("A/+/C", "A/B/C"));

        assertFalse(MqttTopic.topicIncludes("A/+", "B"));
        assertFalse(MqttTopic.topicIncludes("A/+", "B/C"));
        assertFalse(MqttTopic.topicIncludes("A/+/C", "A/B/D"));
        assertFalse(MqttTopic.topicIncludes("A/+/C", "A/B/C/D"));
    }

    @Test
    void multi_level_wildcard() {
        assertTrue(MqttTopic.topicIncludes("#", "A"));
        assertTrue(MqttTopic.topicIncludes("#", "A/B"));
        assertTrue(MqttTopic.topicIncludes("A/#", "A/B"));
        assertTrue(MqttTopic.topicIncludes("A/#", "A/B/C"));

        assertFalse(MqttTopic.topicIncludes("A/#", "B"));
        assertFalse(MqttTopic.topicIncludes("A/#", "B/C"));
    }

    @Test
    void superset_single_level_wildcards() {
        assertTrue(MqttTopic.topicIncludes("+", "+"));
        assertTrue(MqttTopic.topicIncludes("+", "X"));
        assertTrue(MqttTopic.topicIncludes("A/+", "A/B"));
        assertTrue(MqttTopic.topicIncludes("A/+", "A/D"));
        assertTrue(MqttTopic.topicIncludes("A/+/B", "A/D/B"));

        assertFalse(MqttTopic.topicIncludes("A/+/C", "A/B/+"));
        assertFalse(MqttTopic.topicIncludes("+/B", "A/+"));
        assertFalse(MqttTopic.topicIncludes("A/+", "+/B"));
        assertFalse(MqttTopic.topicIncludes("A/B/+/+", "A/B/+"));
        assertFalse(MqttTopic.topicIncludes("A/B/+", "A/+/C"));
        assertFalse(MqttTopic.topicIncludes("A/+", "B/C"));
        assertFalse(MqttTopic.topicIncludes("A/+/C", "A/B/+/+"));
    }

    @Test
    void superset_multi_level_wildcard() {
        assertTrue(MqttTopic.topicIncludes("A/#", "A/B/#"));
        assertTrue(MqttTopic.topicIncludes("A/+/#", "A/B/#"));
        assertTrue(MqttTopic.topicIncludes("A/B/#", "A/B/C"));
        assertTrue(MqttTopic.topicIncludes("A/B/#", "A/B/C/#"));

        assertFalse(MqttTopic.topicIncludes("A/#", "#"));
        assertFalse(MqttTopic.topicIncludes("A/B/C", "A/B/#"));
        assertFalse(MqttTopic.topicIncludes("A/B/C/#", "A/B/#"));
        assertFalse(MqttTopic.topicIncludes("A/#", "B/C"));
    }
}
