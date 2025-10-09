/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;

import java.util.function.Consumer;

@Builder
@Value
public class SubscribeRequest {
    @NonNull
    String topic;
    @Builder.Default
    @NonNull
    QualityOfService qos = QualityOfService.AT_LEAST_ONCE;
    @NonNull
    Consumer<MqttMessage> callback;
}
