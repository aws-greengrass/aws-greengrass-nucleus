/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient.v5;

public enum QOS {
    /**
     * The message is delivered according to the capabilities of the underlying network. No response is sent by the
     * receiver and no retry is performed by the sender. The message arrives at the receiver either once or not at all.
     */
    AT_MOST_ONCE(0),

    /**
     * A level of service that ensures that the message arrives at the receiver at least once.
     */
    AT_LEAST_ONCE(1),

    /**
     * A level of service that ensures that the message arrives at the receiver exactly once.
     */
    EXACTLY_ONCE(2);

    private final int qos;

    QOS(int value) {
        qos = value;
    }

    /**
     * Get the integer value.
     * @return The native enum integer value associated with this Java enum value
     */
    public int getValue() {
        return qos;
    }
}
