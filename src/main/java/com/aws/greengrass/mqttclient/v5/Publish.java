/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient.v5;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

import java.util.List;

@SuppressWarnings({"PMD.ClassWithOnlyPrivateConstructorsShouldBeFinal", "PMD.ExcessiveParameterList"})
@Value
public class Publish {
    @NonNull String topic;
    @NonNull QOS qos;
    /**
     * Retain the message in the cloud MQTT broker (only last message with retain is actually kept).
     * Subscribers will immediately receive the last retained message when they first subscribe.
     */
    boolean retain;
    byte[] payload;
    PayloadFormatIndicator payloadFormat;
    Long messageExpiryIntervalSeconds;
    String responseTopic;
    byte[] correlationData;
    String contentType;
    List<UserProperty> userProperties;

    // Readonly - Not used for Publish, only set for received messages
    List<Long> subscriptionIdentifiers;

    @Builder
    protected Publish(String topic, QOS qos, boolean retain, byte[] payload, PayloadFormatIndicator payloadFormat,
                      Long messageExpiryIntervalSeconds, String responseTopic, byte[] correlationData,
                      String contentType, List<UserProperty> userProperties, List<Long> subscriptionIdentifiers) {
        // Intern the string to deduplicate topic strings in memory
        this.topic = topic.intern();
        if (qos == null) {
            qos = QOS.AT_LEAST_ONCE;
        }
        this.qos = qos;
        this.retain = retain;
        this.payload = payload;
        this.payloadFormat = payloadFormat;
        this.messageExpiryIntervalSeconds = messageExpiryIntervalSeconds;
        if (responseTopic != null) {
            responseTopic = responseTopic.intern();
        }
        this.responseTopic = responseTopic;
        this.correlationData = correlationData;
        if (contentType != null) {
            contentType = contentType.intern();
        }
        this.contentType = contentType;
        this.userProperties = userProperties;
        this.subscriptionIdentifiers = subscriptionIdentifiers;
    }

    public enum PayloadFormatIndicator {
        /**
         * The payload is arbitrary binary data.
         */
        BYTES(0),

        /**
         * The payload is a well-formed utf-8 string value.
         */
        UTF8(1);

        private final int indicator;

        PayloadFormatIndicator(int value) {
            indicator = value;
        }

        /**
         * Get the integer value.
         * @return The native enum integer value associated with this Java enum value.
         */
        public int getValue() {
            return indicator;
        }
    }
}
