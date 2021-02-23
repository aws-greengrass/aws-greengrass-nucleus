/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;
import software.amazon.awssdk.crt.mqtt.QualityOfService;

@Builder
@Value
public class PublishRequest {
    @NonNull String topic;
    @Builder.Default
    @NonNull QualityOfService qos = QualityOfService.AT_LEAST_ONCE;
    /**
     * Retain the message in the cloud MQTT broker (only last message with retain is actually kept).
     * Subscribers will immediately receive the last retained message when they first subscribe.
     */
    boolean retain;
    @NonNull byte[] payload;
}
