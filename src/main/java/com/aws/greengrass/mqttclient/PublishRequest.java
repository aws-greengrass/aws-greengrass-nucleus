/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import com.aws.greengrass.mqttclient.v5.Publish;
import com.aws.greengrass.mqttclient.v5.QOS;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;
import software.amazon.awssdk.crt.mqtt.QualityOfService;

@SuppressWarnings("PMD.ClassWithOnlyPrivateConstructorsShouldBeFinal")
@Value
public class PublishRequest {
    @NonNull
    String topic;
    @NonNull
    QualityOfService qos;
    /**
     * Retain the message in the cloud MQTT broker (only last message with retain is actually kept). Subscribers will
     * immediately receive the last retained message when they first subscribe.
     */
    boolean retain;
    byte[] payload;

    @Builder
    protected PublishRequest(String topic, QualityOfService qos, boolean retain, byte[] payload) {
        // Intern the string to deduplicate topic strings in memory
        this.topic = topic.intern();
        if (qos == null) {
            qos = QualityOfService.AT_LEAST_ONCE;
        }
        this.qos = qos;
        this.retain = retain;
        this.payload = payload;
    }

    /**
     * Convert to the new Publish type.
     *
     * @return {@link Publish}
     */
    public Publish toPublish() {
        return Publish.builder()
                .topic(getTopic())
                .payload(getPayload())
                .qos(QOS.fromInt(getQos().getValue()))
                .retain(isRetain())
                .build();
    }
}
