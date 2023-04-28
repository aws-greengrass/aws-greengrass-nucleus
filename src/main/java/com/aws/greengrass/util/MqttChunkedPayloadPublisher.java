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
import lombok.Setter;
import software.amazon.awssdk.crt.mqtt.QualityOfService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MqttChunkedPayloadPublisher<T> {
    private static final Logger logger = LogManager.getLogger(MqttChunkedPayloadPublisher.class);
    private static final String topicKey = "topic";
    private static final ObjectMapper SERIALIZER = new ObjectMapper();
    private final MqttClient mqttClient;
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
     * @param chunkablePayload The common object payload included in all the messages
     * @param variablePayloads The variable objects in the payload to chunk
     */
    public void publish(Chunkable<T> chunkablePayload, List<T> variablePayloads) {
        // reserve enough space for chunk info
        chunkablePayload.setChunkInfo(Integer.MAX_VALUE, Integer.MAX_VALUE);
        int payloadCommonInformationSize;
        try {
            payloadCommonInformationSize = SERIALIZER.writeValueAsBytes(chunkablePayload).length;
        } catch (JsonProcessingException e) {
            logger.atError().cause(e).kv(topicKey, updateTopic)
                    .log("Unable to write common payload as bytes. Dropping the message");
            return;
        }

        // if common info already exceeds limit, drop the publish request
        if (payloadCommonInformationSize > maxPayloadLengthBytes) {
            logger.atError().kv(topicKey, updateTopic).log("Failed to publish payload via "
                    + "MqttChunkedPayloadPublisher because the common information payload size "
                    + "exceeded the max limit allowed");
            return;
        }

        // chunk variable payloads into multiple lists conforming to limit
        List<List<T>> chunkedVariablePayloadList = chunkVariablePayloads(chunkablePayload, variablePayloads);

        for (int i = 0; i < chunkedVariablePayloadList.size(); i++) {
            chunkablePayload.setVariablePayload(chunkedVariablePayloadList.get(i));
            chunkablePayload.setChunkInfo(i + 1, chunkedVariablePayloadList.size());
            try {
                byte[] payloadInBytes = SERIALIZER.writeValueAsBytes(chunkablePayload);
                this.mqttClient.publish(PublishRequest.builder()
                        .qos(QualityOfService.AT_LEAST_ONCE)
                        .topic(this.updateTopic)
                        .payload(payloadInBytes).build())
                        .whenComplete((r, t) -> {
                            if (t == null) {
                                logger.atDebug().kv(topicKey, updateTopic).log("MQTT publish succeeded");
                            } else {
                                logger.atWarn().kv(topicKey, updateTopic).log("MQTT publish failed", t);
                            }
                        });
            } catch (JsonProcessingException e) {
                logger.atError().cause(e).kv(topicKey, updateTopic).log("Failed to publish message via "
                        + "MqttChunkedPayloadPublisher. Unable to write message as bytes");
            }
        }
    }

    /**
     * Chunk the variable objects into multiple lists below size limit.
     *
     * @param variablePayloads variable objects
     * @param chunkablePayload common objects
     * @return a list of variable object list
     */
    private List<List<T>> chunkVariablePayloads(Chunkable<T> chunkablePayload, List<T> variablePayloads) {
        List<List<T>> chunkedVariablePayloadList = new ArrayList<>();

        // if the total size is smaller than the limit, then we don't need to chunk at all
        try {
            if (getUpdatedChunkablePayloadSize(chunkablePayload, variablePayloads) < maxPayloadLengthBytes) {
                chunkedVariablePayloadList.add(variablePayloads);
                return chunkedVariablePayloadList;
            }
        } catch (JsonProcessingException e) {
            logger.atError().cause(e).kv(topicKey, updateTopic)
                    .log("Unable to write chunkable payload as bytes. Will continue with chunking");
        }


        chunkedVariablePayloadList.add(new ArrayList<>());
        for (T payload : variablePayloads) {
            // if the single payload size plus common info size exceeds the max limit, drop the payload
            try {
                if (getUpdatedChunkablePayloadSize(chunkablePayload, Collections.singletonList(payload))
                        > maxPayloadLengthBytes) {
                    logger.atWarn().kv(topicKey, updateTopic).log("Dropping a variable payload in "
                            + "chunkable payload publish because its size exceed the max limit allowed");
                    continue;
                }
            } catch (JsonProcessingException e) {
                logger.atError().cause(e).kv(topicKey, updateTopic)
                        .log("Unable to write chunkable payload as bytes. Dropping the variable payload");
                continue;
            }

            boolean fitIntoExistingChunks = false;
            // try adding to an existing chunk
            for (List<T> chunk : chunkedVariablePayloadList) {
                try {
                    // get payload size from updated chunkable
                    // note that size(existing_chunk) + size(payload) may not equal size(updated_chunk)
                    // because of how serializer works
                    chunk.add(payload);
                    if (getUpdatedChunkablePayloadSize(chunkablePayload, chunk) < maxPayloadLengthBytes) {
                        fitIntoExistingChunks = true;
                    } else {
                        chunk.remove(chunk.size() - 1);
                    }
                } catch (JsonProcessingException e) {
                    logger.atError().cause(e).kv(topicKey, updateTopic)
                            .log("Unable to write chunkable payload as bytes. Dropping the variable payload");
                    chunk.remove(chunk.size() - 1);
                    break;
                }
            }

            // if we can't add to any exiting chunk, then we should create a new chunk,
            if (!fitIntoExistingChunks) {
                chunkedVariablePayloadList.add(new ArrayList<>(Collections.singletonList(payload)));
            }
        }
        return chunkedVariablePayloadList;
    }

    private int getUpdatedChunkablePayloadSize(Chunkable<T> chunkablePayload, List<T> variablePayloads)
            throws JsonProcessingException {
        chunkablePayload.setVariablePayload(variablePayloads);
        return SERIALIZER.writeValueAsBytes(chunkablePayload).length;
    }
}
