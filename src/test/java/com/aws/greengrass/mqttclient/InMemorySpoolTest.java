/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.mqttclient.spool.Spool;
import com.aws.greengrass.mqttclient.spool.SpoolMessage;
import com.aws.greengrass.mqttclient.spool.SpoolerStoreException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.mqtt.QualityOfService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class InMemorySpoolTest {

    @Mock
    DeviceConfiguration deviceConfiguration;

    private Spool spool;
    Configuration config = new Configuration(new Context());
    private static final String GG_SPOOL_MAX_SIZE_IN_BYTES_KEY = "maxSizeInBytes";

    @BeforeEach
    void beforeEach() {
        config.lookup("spooler", GG_SPOOL_MAX_SIZE_IN_BYTES_KEY).withValue(25L);
        lenient().when(deviceConfiguration.getSpoolerNamespace()).thenReturn(config.lookupTopics("spooler"));
        spool = spy(new Spool(deviceConfiguration));
    }

    @AfterEach
    void after() throws IOException {
        config.context.close();
    }

    @Test
    void GIVEN_publish_request_should_not_be_null_WHEN_pop_id_THEN_continue_if_request_is_null() throws InterruptedException, SpoolerStoreException {
        PublishRequest request = PublishRequest.builder().topic("spool").payload(new byte[0])
                .qos(QualityOfService.AT_MOST_ONCE).build();

        long id1 = spool.addMessage(request).getId();
        long id2 = spool.addMessage(request).getId();
        spool.removeMessageById(id1);

        long id = spool.popId();
        assertEquals(id2, id);
    }

    @Test
    void GIVEN_spooler_is_not_full_WHEN_add_message_THEN_add_message_without_message_dropped() throws InterruptedException, SpoolerStoreException {
        PublishRequest request = PublishRequest.builder().topic("spool").payload(new byte[0])
                .qos(QualityOfService.AT_MOST_ONCE).build();

        long id = spool.addMessage(request).getId();

        verify(spool, never()).removeMessageById(anyLong());
        assertEquals(1, spool.getCurrentMessageCount());
        assertEquals(0L, id);
    }

    @Test
    void GIVEN_spooler_is_full_WHEN_add_message_THEN_drop_messages() throws InterruptedException, SpoolerStoreException {
        PublishRequest request1 = PublishRequest.builder().topic("spool").payload(new byte[10])
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        PublishRequest request2 = PublishRequest.builder().topic("spool").payload(new byte[10])
                .qos(QualityOfService.AT_MOST_ONCE).build();

        spool.addMessage(request1);
        long id2 = spool.addMessage(request2).getId();
        spool.addMessage(request2);

        verify(spool, times(1)).removeMessageById(id2);
        assertEquals(20, spool.getCurrentSpoolerSize());
    }

    @Test
    void GIVEN_spooler_queue_is_full_and_not_have_enough_space_for_new_message_when_add_message_THEN_throw_exception() throws InterruptedException, SpoolerStoreException {
        PublishRequest request1 = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(10).array())
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        PublishRequest request2 = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(10).array())
                .qos(QualityOfService.AT_MOST_ONCE).build();
        PublishRequest request3 = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(20).array())
                .qos(QualityOfService.AT_MOST_ONCE).build();


        spool.addMessage(request1);
        long id2 = spool.addMessage(request2).getId();

        assertThrows(SpoolerStoreException.class, () -> { spool.addMessage(request3); });

        verify(spool, times(1)).removeOldestMessage();
        assertEquals(10, spool.getCurrentSpoolerSize());
        verify(spool, times(1)).removeMessageById(id2);
    }

    @Test
    void GIVEN_message_size_exceeds_max_size_of_spooler_when_add_message_THEN_throw_exception() throws InterruptedException, SpoolerStoreException {
        PublishRequest request = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(30).array())
                .qos(QualityOfService.AT_LEAST_ONCE).build();

        assertThrows(SpoolerStoreException.class, () -> { spool.addMessage(request); });

        assertEquals(0, spool.getCurrentSpoolerSize());
    }

    @Test
    void GIVEN_id_WHEN_remove_message_by_id_THEN_spooler_size_decreased() throws SpoolerStoreException, InterruptedException {
        PublishRequest request = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(10).array())
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        SpoolMessage message = spool.addMessage(request);
        long id = message.getId();

        spool.removeMessageById(id);

        assertEquals(0, spool.getCurrentSpoolerSize());
    }

    @Test
    void GIVEN_message_with_qos_zero_WHEN_pop_out_messages_with_qos_zero_THEN_only_remove_message_with_qos_zero() throws SpoolerStoreException, InterruptedException {
        PublishRequest request1 = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(1).array())
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        PublishRequest request2 = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(2).array())
                .qos(QualityOfService.AT_MOST_ONCE).build();
        PublishRequest request3 = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(4).array())
                .qos(QualityOfService.AT_MOST_ONCE).build();
        List<PublishRequest> requests = Arrays.asList(request1, request2, request3);

        for (PublishRequest request : requests) {
            spool.addMessage(request);
        }

        spool.popOutMessagesWithQosZero();

        verify(spool, times(2)).removeMessageById(anyLong());
        assertEquals(1, spool.getCurrentSpoolerSize());
    }
}
