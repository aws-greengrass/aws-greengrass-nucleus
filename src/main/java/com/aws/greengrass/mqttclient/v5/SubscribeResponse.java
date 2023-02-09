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
    @Nullable
    List<Integer> reasonCodes;
    @Nullable
    List<UserProperty> userProperties;

    /**
     * Convert a SubAckPacket to a SubscribeResponse.
     *
     * @param r SubAckPacket
     * @return SubscribeResponse
     */
    public static SubscribeResponse fromCrtSubAck(SubAckPacket r) {
        return new SubscribeResponse(r.getReasonString(), r.getReasonCodes() == null ? null
                : r.getReasonCodes().stream().map(SubAckPacket.SubAckReasonCode::getValue).collect(Collectors.toList()),
                r.getUserProperties() == null ? null
                        : r.getUserProperties().stream().map((u) -> new UserProperty(u.key, u.value))
                                .collect(Collectors.toList()));
    }
}
