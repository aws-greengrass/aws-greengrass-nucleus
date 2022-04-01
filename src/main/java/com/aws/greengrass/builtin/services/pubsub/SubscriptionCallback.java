/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.pubsub;

import lombok.Builder;
import lombok.Value;
import software.amazon.awssdk.aws.greengrass.model.ReceiveMode;

@Value
@Builder
public class SubscriptionCallback {
    String sourceComponent;
    ReceiveMode receiveMode;
    Object callback;
}
