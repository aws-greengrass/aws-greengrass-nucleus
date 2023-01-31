/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient.v5;

import lombok.AllArgsConstructor;
import lombok.Value;

import java.util.List;
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
}
