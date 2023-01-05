/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient.v5;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

import java.util.function.Consumer;

@Builder
@Value
public class Unsubscribe {
    @NonNull String topic;
    @NonNull Consumer<Publish> callback;
}
