/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient.v5;

import lombok.Value;
import software.amazon.awssdk.crt.mqtt5.packets.PubAckPacket;

import java.util.List;
import java.util.stream.Collectors;

@Value
public class PubAck {
    int reasonCode;
    String reasonString;
    List<UserProperty> userProperties;

    /**
     * Convert a PubAckPacket to a PubAck.
     *
     * @param p PubAckPacket
     * @return PubAck
     */
    public static PubAck fromCrtPubAck(PubAckPacket p) {
        return new PubAck(p.getReasonCode() == null ? 0 : p.getReasonCode().getValue(), p.getReasonString(),
                p.getUserProperties() == null ? null
                        : p.getUserProperties().stream().map(u -> new UserProperty(u.key, u.value))
                                .collect(Collectors.toList()));
    }

    public boolean isSuccessful() {
        return reasonCode == PubAckPacket.PubAckReasonCode.SUCCESS.getValue()
                || reasonCode == PubAckPacket.PubAckReasonCode.NO_MATCHING_SUBSCRIBERS.getValue();
    }
}
