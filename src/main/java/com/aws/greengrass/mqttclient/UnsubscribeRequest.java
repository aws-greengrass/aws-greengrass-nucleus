/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;
import software.amazon.awssdk.crt.mqtt.MqttMessage;

import java.util.function.Consumer;

@Builder
@Value
public class UnsubscribeRequest {
    @NonNull
    String topic;
    @NonNull
    Consumer<MqttMessage> callback;
}
