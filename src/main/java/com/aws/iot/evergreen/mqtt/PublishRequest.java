/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.mqtt;


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
    boolean retain;
    @NonNull byte[] payload;
}
