/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.PublishRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.crt.mqtt.QualityOfService;

import java.util.List;

public class MqttChunkedPayloadPublisher<T> {
    private static final Logger logger = LogManager.getLogger(MqttChunkedPayloadPublisher.class);
    private final MqttClient mqttClient;
    private static final ObjectMapper SERIALIZER = new ObjectMapper();
    @Setter
    private String updateTopic;
    @Setter
    private int maxPayloadLengthBytes;

    public MqttChunkedPayloadPublisher(MqttClient mqttClient) {
        this.mqttClient = mqttClient;
    }

    /**
     * Publish the payload using MQTT.
     *
     * @param chunkablePayload  The common object payload included in all the messages
     * @param variablePayloads  The variable objects in the payload to chunk
     */
    public void publish(Chunkable<T> chunkablePayload, List<T> variablePayloads) {
        try {
            int start = 0;
            int payloadVariableInformationSize = SERIALIZER.writeValueAsBytes(variablePayloads).length;
            int payloadCommonInformationSize = SERIALIZER.writeValueAsBytes(chunkablePayload).length;

            MqttChunkingInformation chunkingInformation =
                    getChunkingInformation(payloadVariableInformationSize, variablePayloads.size(),
                            payloadCommonInformationSize);
            for (int chunkId = 0; chunkId < chunkingInformation.getNumberOfChunks(); chunkId++,
                    start += chunkingInformation.getNumberOfComponentsPerPublish()) {
                chunkablePayload.setVariablePayload(variablePayloads.subList(start,
                        start + chunkingInformation.getNumberOfComponentsPerPublish()));
                this.mqttClient.publish(PublishRequest.builder()
                        .qos(QualityOfService.AT_LEAST_ONCE)
                        .topic(this.updateTopic)
                        .payload(SERIALIZER.writeValueAsBytes(chunkablePayload)).build());
            }
        } catch (JsonProcessingException e) {
            logger.atError().cause(e).kv("topic", updateTopic).log("Unable to publish data via topic.");
        }
    }

    /**
     * Gets the chunking information based on the variable payload size and the common payload size.
     *
     * @param payloadVariableInformationByteSize variable payload size in bytes.
     * @param payloadVariableInformationListSize variable payload list size.
     * @param payloadCommonInformationSize       common payload size in bytes.
     * @return the chunking information containing the number of chunks to be sent along with number of variable
     *     payload object count to be sent in each chunk.
     */
    private MqttChunkingInformation getChunkingInformation(int payloadVariableInformationByteSize,
                                                                 int payloadVariableInformationListSize,
                                                                 int payloadCommonInformationSize) {
        // The number of chunks to send would be the variable payload byte size divided by the available bytes in per
        // publish message after adding the common payload byte size.
        int numberOfChunks = Math.floorDiv(payloadVariableInformationByteSize,
                maxPayloadLengthBytes - payloadCommonInformationSize) + 1;
        int numberOfComponentsPerPublish = Math.floorDiv(payloadVariableInformationListSize, numberOfChunks);
        return MqttChunkingInformation.builder()
                .numberOfChunks(numberOfChunks)
                .numberOfComponentsPerPublish(numberOfComponentsPerPublish)
                .build();
    }

    @Builder
    private static class MqttChunkingInformation {
        @Getter
        private int numberOfChunks;
        @Getter
        private int numberOfComponentsPerPublish;
    }
}
