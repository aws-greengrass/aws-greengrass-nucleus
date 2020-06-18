/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.mqtt;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;
import software.amazon.awssdk.crt.mqtt.MqttMessage;

import java.util.function.Consumer;

@Builder
@Value
public class UnsubscribeRequest {
    @NonNull String topic;
    @NonNull Consumer<MqttMessage> callback;
}
