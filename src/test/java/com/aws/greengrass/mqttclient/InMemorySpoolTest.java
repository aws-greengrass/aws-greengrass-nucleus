package com.aws.greengrass.mqttclient;

import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.mqttclient.spool.Spool;
import com.aws.greengrass.mqttclient.spool.SpoolerConfig;
import com.aws.greengrass.mqttclient.spool.SpoolerLoadException;
import com.aws.greengrass.mqttclient.spool.SpoolerStorageType;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingDeque;

import java.util.concurrent.LinkedBlockingDeque;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import software.amazon.awssdk.crt.mqtt.QualityOfService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class InMemorySpoolTest {

    @Mock
    DeviceConfiguration deviceConfiguration;

    private Spool spool;
    private SpoolerConfig config;
    private BlockingDeque<Long> messageQueueOfQos0;
    private BlockingDeque<Long> messageQueueOfQos1And2;

    @BeforeEach
    void beforeEach() {
        config = SpoolerConfig.builder().keepQos0WhenOffline(true)
                .spoolMaxMessageQueueSizeInBytes(new Long(25)).spoolStorageType(SpoolerStorageType.Memory)
                .build();
    }

    @Test
    void GIVEN_spooler_is_not_full_WHEN_add_message_THEN_add_message_without_message_dropped() throws InterruptedException, SpoolerLoadException {
        PublishRequest request = PublishRequest.builder().topic("spool").payload(new byte[0])
                .qos(QualityOfService.AT_MOST_ONCE).build();

        spool = spy(new Spool(deviceConfiguration, config));
        doNothing().when(spool).addMessageToSpooler(any(), any());
        Long id = spool.addMessage(request);

        verify(spool, never()).removeMessageById(any());
        verify(spool, times(1)).addMessageToSpooler(any(), any());
        assertEquals(1, spool.messageCount());
        assertEquals(new Long(0), id);
    }

    @Test
    void GIVEN_spooler_is_full_WHEN_add_message_THEN_drop_messages() throws InterruptedException, SpoolerLoadException {
        PublishRequest request1 = PublishRequest.builder().topic("spool").payload(new byte[10])
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        PublishRequest request2 = PublishRequest.builder().topic("spool").payload(new byte[10])
                .qos(QualityOfService.AT_MOST_ONCE).build();

        spool = spy(new Spool(deviceConfiguration, config));
        //doNothing().when(spool).addMessageToSpooler(any(), any());
        //when(spool.getMessageById(any())).thenReturn(request1).thenReturn(request2);
        spool.addMessage(request1);
        Long id2 = spool.addMessage(request2);
        spool.addMessage(request2);

        //verify(spool, times(1)).removeOldestMessage();
        verify(spool, times(3)).addMessageToSpooler(any(), any());
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
        verify(spool, times(2)).addMessageToSpooler(any(), any());
        assertEquals(10, spool.getCurrentSpoolerSize());
        verify(spool, times(1)).removeMessageById(id2);
    }

    @Test
    void GIVEN_message_size_exceeds_max_size_of_spooler_when_add_message_THEN_throw_exception() throws InterruptedException, SpoolerLoadException {
        PublishRequest request = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(30).array())
                .qos(QualityOfService.AT_LEAST_ONCE).build();

        spool = spy(new Spool(deviceConfiguration, config));
        assertThrows(SpoolerLoadException.class, () -> { spool.addMessage(request); });

        verify(spool, times(0)).addMessageToSpooler(any(), any());
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
