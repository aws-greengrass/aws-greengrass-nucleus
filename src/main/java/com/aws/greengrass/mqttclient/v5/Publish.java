/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient.v5;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;
import software.amazon.awssdk.crt.mqtt5.packets.PublishPacket;

import java.util.List;
import java.util.stream.Collectors;

@SuppressWarnings({
        "PMD.ClassWithOnlyPrivateConstructorsShouldBeFinal", "PMD.ExcessiveParameterList"
})
@Value
public class Publish {
    @NonNull
    String topic;
    @NonNull
    QOS qos;
    /**
     * Retain the message in the cloud MQTT broker (only last message with retain is actually kept). Subscribers will
     * immediately receive the last retained message when they first subscribe.
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
            Long messageExpiryIntervalSeconds, String responseTopic, byte[] correlationData, String contentType,
            List<UserProperty> userProperties, List<Long> subscriptionIdentifiers) {
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

    /**
     * Convert a PublishPacket to a Publish.
     *
     * @param m PublishPacket
     * @return Publish
     */
    public static Publish fromCrtPublishPacket(PublishPacket m) {
        return Publish.builder()
                .payload(m.getPayload())
                .qos(m.getQOS() == null ? QOS.AT_MOST_ONCE : QOS.fromInt(m.getQOS().getValue()))
                .retain(m.getRetain())
                .topic(m.getTopic())
                .payloadFormat(m.getPayloadFormat() == null
                        ? null
                        : PayloadFormatIndicator.fromInt(m.getPayloadFormat().getValue()))
                .messageExpiryIntervalSeconds(m.getMessageExpiryIntervalSeconds())
                .responseTopic(m.getResponseTopic())
                .correlationData(m.getCorrelationData())
                .subscriptionIdentifiers(m.getSubscriptionIdentifiers())
                .contentType(m.getContentType())
                .userProperties(m.getUserProperties() == null
                        ? null
                        : m.getUserProperties()
                                .stream()
                                .map(u -> new UserProperty(u.key, u.value))
                                .collect(Collectors.toList()))
                .build();
    }

    /**
     * Convert a Publish to a PublishPacket.
     *
     * @return PublishPacket
     */
    public PublishPacket toCrtPublishPacket() {
        return new PublishPacket.PublishPacketBuilder().withPayload(payload)
                .withQOS(software.amazon.awssdk.crt.mqtt5.QOS.getEnumValueFromInteger(qos.getValue()))
                .withRetain(retain)
                .withTopic(topic)
                .withPayloadFormat(payloadFormat == null
                        ? null
                        : PublishPacket.PayloadFormatIndicator.getEnumValueFromInteger(payloadFormat.getValue()))
                .withMessageExpiryIntervalSeconds(messageExpiryIntervalSeconds)
                .withResponseTopic(responseTopic)
                .withCorrelationData(correlationData)
                .withContentType(contentType)
                .withUserProperties(userProperties == null
                        ? null
                        : userProperties.stream()
                                .map(u -> new software.amazon.awssdk.crt.mqtt5.packets.UserProperty(u.getKey(),
                                        u.getValue()))
                                .collect(Collectors.toList()))
                .build();
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
         * 
         * @return The native enum integer value associated with this Java enum value.
         */
        public int getValue() {
            return indicator;
        }

        /**
         * Create a PayloadFormatIndicator from its integer value.
         *
         * @param i integer value
         * @return {@link PayloadFormatIndicator}
         * @throws IllegalArgumentException if the value is not 0, 1, or 2
         */
        public static PayloadFormatIndicator fromInt(int i) {
            switch (i) {
            case 0:
                return BYTES;
            case 1:
                return UTF8;
            default:
                throw new IllegalArgumentException(String.format("Invalid value for payload format indicator %d", i));
            }
        }
    }
}
