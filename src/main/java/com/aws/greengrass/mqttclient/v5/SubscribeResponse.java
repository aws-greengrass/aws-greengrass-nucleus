/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient.v5;

import lombok.AllArgsConstructor;
import lombok.Value;
import software.amazon.awssdk.crt.mqtt5.packets.SubAckPacket;

import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

@AllArgsConstructor
@Value
public class SubscribeResponse {
    @Nullable
    String reasonString;

    // Our subscribe only lets us specify a single topic, so we will only get a single reason code back
    int reasonCode;
    @Nullable
    List<UserProperty> userProperties;

    /**
     * Convert a SubAckPacket to a SubscribeResponse.
     *
     * @param r SubAckPacket
     * @return SubscribeResponse
     */
    public static SubscribeResponse fromCrtSubAck(SubAckPacket r) {
        return new SubscribeResponse(r.getReasonString(),
                r.getReasonCodes() == null
                        ? 0
                        : r.getReasonCodes()
                                .stream()
                                .map(SubAckPacket.SubAckReasonCode::getValue)
                                .max(Integer::compareTo)
                                .orElse(0),
                r.getUserProperties() == null
                        ? null
                        : r.getUserProperties()
                                .stream()
                                .map((u) -> new UserProperty(u.key, u.value))
                                .collect(Collectors.toList()));
    }

    public boolean isSuccessful() {
        return reasonCode <= SubAckPacket.SubAckReasonCode.GRANTED_QOS_2.getValue();
    }
}
