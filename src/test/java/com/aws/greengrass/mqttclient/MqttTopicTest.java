/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import org.junit.jupiter.api.Test;

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
}
