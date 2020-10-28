/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.mqttclient.spool.Spool;
import com.aws.greengrass.mqttclient.spool.SpoolerConfig;
import com.aws.greengrass.mqttclient.spool.SpoolerLoadException;
import com.aws.greengrass.mqttclient.spool.SpoolerStorageType;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import software.amazon.awssdk.crt.mqtt.QualityOfService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(GGExtension.class)
public class InMemorySpoolTest {

    @Mock
    DeviceConfiguration deviceConfiguration;

    private Spool spool;
    private SpoolerConfig config;


    @BeforeEach
    void beforeEach() {
        config = SpoolerConfig.builder().keepQos0WhenOffline(true)
                .spoolMaxMessageQueueSizeInBytes(25L).spoolStorageType(SpoolerStorageType.Memory)
                .build();
    }

    @Test
    void GIVEN_spooler_is_not_full_WHEN_add_message_THEN_add_message_without_message_dropped() throws InterruptedException, SpoolerLoadException {
        PublishRequest request = PublishRequest.builder().topic("spool").payload(new byte[0])
                .qos(QualityOfService.AT_MOST_ONCE).build();

        spool = spy(new Spool(deviceConfiguration, config));
        Long id = spool.addMessage(request);

        verify(spool, never()).removeMessageById(any());
        assertEquals(1, spool.getCurrentMessageCount());
        assertEquals(0L, id);
    }

    @Test
    void GIVEN_spooler_is_full_WHEN_add_message_THEN_drop_messages() throws InterruptedException, SpoolerLoadException {
        PublishRequest request1 = PublishRequest.builder().topic("spool").payload(new byte[10])
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        PublishRequest request2 = PublishRequest.builder().topic("spool").payload(new byte[10])
                .qos(QualityOfService.AT_MOST_ONCE).build();

        spool = spy(new Spool(deviceConfiguration, config));

        spool.addMessage(request1);
        Long id2 = spool.addMessage(request2);
        spool.addMessage(request2);

        verify(spool, times(1)).removeMessageById(id2);
        assertEquals(20, spool.getCurrentSpoolerSize());
    }

    @Test
    void GIVEN_spooler_queue_is_full_and_not_have_enough_space_for_new_message_when_add_message_THEN_throw_exception() throws InterruptedException, SpoolerLoadException {
        PublishRequest request1 = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(10).array())
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        PublishRequest request2 = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(10).array())
                .qos(QualityOfService.AT_MOST_ONCE).build();
        PublishRequest request3 = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(20).array())
                .qos(QualityOfService.AT_MOST_ONCE).build();


        spool = spy(new Spool(deviceConfiguration, config));
        spool.addMessage(request1);
        Long id2 = spool.addMessage(request2);

        assertThrows(SpoolerLoadException.class, () -> { spool.addMessage(request3); });

        verify(spool, times(1)).removeOldestMessage();
        assertEquals(10, spool.getCurrentSpoolerSize());
        verify(spool, times(1)).removeMessageById(id2);
    }

    @Test
    void GIVEN_message_size_exceeds_max_size_of_spooler_when_add_message_THEN_throw_exception() throws InterruptedException, SpoolerLoadException {
        PublishRequest request = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(30).array())
                .qos(QualityOfService.AT_LEAST_ONCE).build();

        spool = spy(new Spool(deviceConfiguration, config));
        assertThrows(SpoolerLoadException.class, () -> { spool.addMessage(request); });


        spool = spy(new Spool(deviceConfiguration, config));
        assertThrows(SpoolerLoadException.class, () -> { spool.addMessage(request); });

        assertEquals(0, spool.getCurrentSpoolerSize());
    }

    @Test
    void GIVEN_id_WHEN_remove_message_by_id_THEN_spooler_size_decreased() throws SpoolerLoadException, InterruptedException {
        PublishRequest request = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(0).array())
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        spool = spy(new Spool(deviceConfiguration, config));
        Long id = spool.addMessage(request);

        spool.removeMessageById(id);

        assertEquals(0, spool.getCurrentSpoolerSize());
    }

    @Test
    void GIVEN_message_with_qos_zero_WHEN_pop_out_messages_with_qos_zero_THEN_only_remove_message_with_qos_zero() throws SpoolerLoadException, InterruptedException {

        PublishRequest request1 = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(3).array())
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        PublishRequest request2 = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(5).array())
                .qos(QualityOfService.AT_MOST_ONCE).build();
        List<PublishRequest> requests = Arrays.asList(request1, request2, request2);

        spool = spy(new Spool(deviceConfiguration, config));
        for (PublishRequest request : requests) {
            spool.addMessage(request);
        }

        spool.popOutMessagesWithQosZero();

        verify(spool, times(2)).removeMessageById(any());
        assertEquals(3, spool.getCurrentSpoolerSize());
    }
}
