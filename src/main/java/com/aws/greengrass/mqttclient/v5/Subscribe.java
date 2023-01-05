/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient.v5;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

import java.util.List;
import java.util.function.Consumer;

@Builder
@Value
public class Subscribe {
    @NonNull String topic;
    @Builder.Default
    @NonNull QOS qos = QOS.AT_LEAST_ONCE;

    Boolean noLocal;
    Boolean retainAsPublished;
    RetainHandlingType retainHandlingType;
    List<UserProperty> userProperties;
    @NonNull Consumer<Publish> callback;

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
