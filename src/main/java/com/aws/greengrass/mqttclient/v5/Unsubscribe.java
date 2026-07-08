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
    // The callback provided in Subscribe which should be removed from the MQTT client's callback mapping.
    // MqttClient locates the registration (cloud or local-only) by (topic, callback identity), so no
    // cloud/local mode flag is needed here.
    Consumer<Publish> subscriptionCallback;
}
