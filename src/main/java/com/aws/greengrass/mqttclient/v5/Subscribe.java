/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient.v5;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;
import software.amazon.awssdk.crt.mqtt5.packets.SubscribePacket;

import java.util.List;
import java.util.function.Consumer;

@Builder
@Value
public class Subscribe {
    @NonNull String topic;
    @Builder.Default
    @NonNull QOS qos = QOS.AT_LEAST_ONCE;

    @Builder.Default
    boolean noLocal = false;

    // True by default to request that the broker forwards MQTT packets with their original retain value.
    @Builder.Default
    boolean retainAsPublished = true;
    // Default to SEND_ON_SUBSCRIBE which is the 0 value and the only value for MQTT 3.
    @Builder.Default
    RetainHandlingType retainHandlingType = RetainHandlingType.SEND_ON_SUBSCRIBE;
    List<UserProperty> userProperties;
    Consumer<Publish> callback;

    /**
     * Convert a CRT SubscribePacket.
     *
     * @return SubscribePacket
     */
    public SubscribePacket toCrtSubscribePacket() {
        return new SubscribePacket.SubscribePacketBuilder().withSubscription(topic,
                        software.amazon.awssdk.crt.mqtt5.QOS.getEnumValueFromInteger(qos.getValue()), noLocal,
                        retainAsPublished, retainHandlingType == null ? null
                                : SubscribePacket.RetainHandlingType
                                        .getEnumValueFromInteger(retainHandlingType.getValue()))
                .build();
    }

    public enum RetainHandlingType {
        /**
         * The server should always send all retained messages on topics that match a subscription's filter.
         */
        SEND_ON_SUBSCRIBE(0),

        /**
         * The server should send retained messages on topics that match the subscription's filter, but only for the
         * first matching subscription, per session.
         */
        SEND_ON_SUBSCRIBE_IF_NEW(1),

        /**
         * Subscriptions must not trigger any retained message publishes from the server.
         */
        DONT_SEND(2);

        private final int type;

        RetainHandlingType(int code) {
            type = code;
        }

        public int getValue() {
            return type;
        }
    }
}
