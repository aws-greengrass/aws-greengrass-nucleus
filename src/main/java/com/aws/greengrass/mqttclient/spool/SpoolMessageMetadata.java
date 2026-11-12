/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient.spool;

import com.aws.greengrass.mqttclient.v5.QOS;
import lombok.AllArgsConstructor;
import lombok.Value;

import javax.annotation.Nullable;

/**
 * Metadata of a persisted spool message, used to rebuild the spool runtime queue without loading payloads.
 */
@Value
@AllArgsConstructor
public class SpoolMessageMetadata {
    long id;
    long payloadSizeInBytes;
    /**
     * QoS of the message, or null if unknown. Unknown QoS is treated as possibly QoS 0.
     */
    @Nullable
    QOS qos;
}
