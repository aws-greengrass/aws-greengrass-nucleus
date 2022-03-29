/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.pubsub;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.aws.greengrass.model.ReceiveMode;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionCallback {
    String sourceComponent;
    ReceiveMode receiveMode;
    Object callback;
}
