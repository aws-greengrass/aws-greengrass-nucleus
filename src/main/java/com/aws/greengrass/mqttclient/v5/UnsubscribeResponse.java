/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient.v5;

import lombok.AllArgsConstructor;
import lombok.Value;
import software.amazon.awssdk.crt.mqtt5.packets.UnsubAckPacket;

import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

@AllArgsConstructor
@Value
public class UnsubscribeResponse {
    @Nullable
    String reasonString;
    @Nullable
    List<Integer> reasonCodes;
    @Nullable
    List<UserProperty> userProperties;

    /**
     * Convert an UnsubAckPacket to an UnsubscribeResponse.
     *
     * @param r UnsubAckPacket
     * @return UnsubscribeResponse
     */
    public static UnsubscribeResponse fromCrtUnsubAck(UnsubAckPacket r) {
        return new UnsubscribeResponse(r.getReasonString(),
                r.getReasonCodes() == null
                        ? null
                        : r.getReasonCodes()
                                .stream()
                                .map(UnsubAckPacket.UnsubAckReasonCode::getValue)
                                .collect(Collectors.toList()),
                r.getUserProperties() == null
                        ? null
                        : r.getUserProperties()
                                .stream()
                                .map(u -> new UserProperty(u.key, u.value))
                                .collect(Collectors.toList()));
    }
}
