/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;


import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.PublishRequest;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class MqttChunkedPayloadPublisherTest {
    static final ObjectMapper MAPPER = new ObjectMapper();
    MqttChunkedPayloadPublisher<String> publisher;
    @Mock
    private MqttClient mqttClient;
    @Captor
    private ArgumentCaptor<PublishRequest> publishRequestArgumentCaptor;

    @BeforeEach
    void setup() {
        lenient().when(mqttClient.publish(any(PublishRequest.class))).thenReturn(CompletableFuture.completedFuture(0));
        publisher = new MqttChunkedPayloadPublisher<>(mqttClient);
        publisher.setUpdateTopic("topic");
    }

    @Test
    void GIVEN_variable_payloads_WHEN_size_limit_not_breach_THEN_send_in_one_chunk() throws IOException {
        ChunkableTestMessage message = new ChunkableTestMessage("commonPayload");

        String payload1 = RandomStringUtils.randomAlphanumeric(20);
        String payload2 = RandomStringUtils.randomAlphanumeric(20);
        String payload3 = RandomStringUtils.randomAlphanumeric(20);

        // compute max size
        message.setVariablePayload(Collections.singletonList(payload1));
        publisher.setMaxPayloadLengthBytes(Integer.MAX_VALUE);

        // publish
        message.setVariablePayload(Collections.emptyList());
        publisher.publish(message, Arrays.asList(payload1, payload2, payload3));
        verify(mqttClient, times(1)).publish(publishRequestArgumentCaptor.capture());
        List<PublishRequest> publishRequests = publishRequestArgumentCaptor.getAllValues();

        //check results
        ChunkableTestMessage message1 =
                MAPPER.readValue(publishRequests.get(0).getPayload(), ChunkableTestMessage.class);
        assertEquals("commonPayload", message1.getCommonPayload());
        assertEquals(3, message1.getVariablePayload().size());
        assertEquals(message1.getVariablePayload().get(0), payload1);
        assertEquals(message1.getVariablePayload().get(1), payload2);
        assertEquals(message1.getVariablePayload().get(2), payload3);

        assertEquals(message1.getId(), 1);
        assertEquals(message1.getTotalChunks(), 1);
    }


    @Test
    void GIVEN_two_variable_payloads_WHEN_reach_size_limit_THEN_break_into_two_chunks() throws IOException {
        ChunkableTestMessage message = new ChunkableTestMessage("commonPayload");
        message.setChunkInfo(Integer.MAX_VALUE, Integer.MAX_VALUE);

        String payload1 = RandomStringUtils.randomAlphanumeric(20);
        String payload2 = RandomStringUtils.randomAlphanumeric(20);

        // compute max size
        message.setVariablePayload(Collections.singletonList(payload1));
        publisher.setMaxPayloadLengthBytes(MAPPER.writeValueAsBytes(message).length + 1);

        // publish
        message.setVariablePayload(Collections.emptyList());
        publisher.publish(message, Arrays.asList(payload1, payload2));
        verify(mqttClient, times(2)).publish(publishRequestArgumentCaptor.capture());
        List<PublishRequest> publishRequests = publishRequestArgumentCaptor.getAllValues();

        //check results
        ChunkableTestMessage message1 =
                MAPPER.readValue(publishRequests.get(0).getPayload(), ChunkableTestMessage.class);
        assertEquals("commonPayload", message1.getCommonPayload());
        assertEquals(1, message1.getVariablePayload().size());
        assertEquals(message1.getVariablePayload().get(0), payload1);
        assertEquals(message1.getId(), 1);
        assertEquals(message1.getTotalChunks(), 2);


        ChunkableTestMessage message2 =
                MAPPER.readValue(publishRequests.get(1).getPayload(), ChunkableTestMessage.class);
        assertEquals("commonPayload", message2.getCommonPayload());
        assertEquals(1, message2.getVariablePayload().size());
        assertEquals(message2.getVariablePayload().get(0), payload2);
        assertEquals(message2.getId(), 2);
        assertEquals(message2.getTotalChunks(), 2);
    }

    @Test
    void GIVEN_3_variable_payloads_WHEN_payload2_requests_a_new_chunk_THEN_payload3_can_still_place_in_first_chunk()
            throws IOException {
        ChunkableTestMessage message = new ChunkableTestMessage("commonPayload");
        message.setChunkInfo(Integer.MAX_VALUE, Integer.MAX_VALUE);

        String payload1 = RandomStringUtils.randomAlphanumeric(20);
        String payload2 = RandomStringUtils.randomAlphanumeric(100);
        String payload3 = RandomStringUtils.randomAlphanumeric(20);

        // compute max size
        message.setVariablePayload(Collections.singletonList(payload2));
        publisher.setMaxPayloadLengthBytes(MAPPER.writeValueAsBytes(message).length + 1);

        // publish
        message.setVariablePayload(Collections.emptyList());
        publisher.publish(message, Arrays.asList(payload1, payload2, payload3));
        verify(mqttClient, times(2)).publish(publishRequestArgumentCaptor.capture());
        List<PublishRequest> publishRequests = publishRequestArgumentCaptor.getAllValues();

        //check results
        ChunkableTestMessage message1 =
                MAPPER.readValue(publishRequests.get(0).getPayload(), ChunkableTestMessage.class);
        assertEquals("commonPayload", message1.getCommonPayload());
        assertEquals(2, message1.getVariablePayload().size());
        assertEquals(message1.getVariablePayload().get(0), payload1);
        assertEquals(message1.getVariablePayload().get(1), payload3);
        assertEquals(message1.getId(), 1);
        assertEquals(message1.getTotalChunks(), 2);


        ChunkableTestMessage message2 =
                MAPPER.readValue(publishRequests.get(1).getPayload(), ChunkableTestMessage.class);
        assertEquals("commonPayload", message2.getCommonPayload());
        assertEquals(1, message2.getVariablePayload().size());
        assertEquals(message2.getVariablePayload().get(0), payload2);
        assertEquals(message2.getId(), 2);
        assertEquals(message2.getTotalChunks(), 2);
    }

    @Test
    void GIVEN_variable_payloads_too_large_WHEN_publish_THEN_drop_message() throws IOException {
        ChunkableTestMessage message = new ChunkableTestMessage("commonPayload");
        message.setChunkInfo(Integer.MAX_VALUE, Integer.MAX_VALUE);

        String payload1 = RandomStringUtils.randomAlphanumeric(20);
        String payload2 = RandomStringUtils.randomAlphanumeric(100);
        String payload3 = RandomStringUtils.randomAlphanumeric(20);

        // compute max size
        message.setVariablePayload(Collections.singletonList(payload1));
        publisher.setMaxPayloadLengthBytes(MAPPER.writeValueAsBytes(message).length + 1);

        // publish
        message.setVariablePayload(Collections.emptyList());
        publisher.publish(message, Arrays.asList(payload1, payload2, payload3));
        verify(mqttClient, times(2)).publish(publishRequestArgumentCaptor.capture());
        List<PublishRequest> publishRequests = publishRequestArgumentCaptor.getAllValues();

        //check results
        ChunkableTestMessage message1 =
                MAPPER.readValue(publishRequests.get(0).getPayload(), ChunkableTestMessage.class);
        assertEquals("commonPayload", message1.getCommonPayload());
        assertEquals(1, message1.getVariablePayload().size());
        assertEquals(message1.getVariablePayload().get(0), payload1);
        assertEquals(message1.getId(), 1);
        assertEquals(message1.getTotalChunks(), 2);


        ChunkableTestMessage message2 =
                MAPPER.readValue(publishRequests.get(1).getPayload(), ChunkableTestMessage.class);
        assertEquals("commonPayload", message2.getCommonPayload());
        assertEquals(1, message2.getVariablePayload().size());
        assertEquals(message2.getVariablePayload().get(0), payload3);
        assertEquals(message2.getId(), 2);
        assertEquals(message2.getTotalChunks(), 2);
    }

    @Test
    void GIVEN_common_payload_too_large_WHEN_publish_THEN_drop_message() throws IOException {
        ChunkableTestMessage message = new ChunkableTestMessage(RandomStringUtils.randomAlphanumeric(200));
        message.setChunkInfo(Integer.MAX_VALUE, Integer.MAX_VALUE);

        String payload1 = RandomStringUtils.randomAlphanumeric(10);
        String payload2 = RandomStringUtils.randomAlphanumeric(10);

        publisher.setMaxPayloadLengthBytes(MAPPER.writeValueAsBytes(message).length - 20);

        // publish
        message.setVariablePayload(Collections.emptyList());
        publisher.publish(message, Arrays.asList(payload1, payload2));
        verify(mqttClient, times(0)).publish(publishRequestArgumentCaptor.capture());
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    private static class ChunkableTestMessage implements Chunkable<String> {
        String commonPayload;
        List<String> variablePayload;
        int id;
        int totalChunks;

        public ChunkableTestMessage(String commonPayload) {
            this.commonPayload = commonPayload;
        }

        @Override
        public void setVariablePayload(List<String> variablePayload) {
            this.variablePayload = variablePayload;
        }

        @Override
        public void setChunkInfo(int id, int totalChunks) {
            this.id = id;
            this.totalChunks = totalChunks;
        }
    }
}
