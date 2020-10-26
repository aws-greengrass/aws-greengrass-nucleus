package com.aws.greengrass.mqttclient;

import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.mqttclient.spool.Spool;
import com.aws.greengrass.mqttclient.spool.SpoolerConfig;
import com.aws.greengrass.mqttclient.spool.SpoolerStorageType;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingDeque;

import java.util.concurrent.LinkedBlockingDeque;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import software.amazon.awssdk.crt.mqtt.QualityOfService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class InMemorySpoolTest {

    @Mock
    DeviceConfiguration deviceConfiguration;

    private Spool spool;
    private SpoolerConfig config;
    private BlockingDeque<Long> messageQueueOfQos0;
    private BlockingDeque<Long> messageQueueOfQos1And2;

    @BeforeEach
    void beforeEach() {
        config = SpoolerConfig.builder().keepQos0WhenOffline(true).maxRetried(0)
                .spoolMaxMessageQueueSizeInBytes(new Long(25)).spoolStorageType(SpoolerStorageType.Memory)
                .build();
        messageQueueOfQos0 = new LinkedBlockingDeque<>();
        messageQueueOfQos1And2 = new LinkedBlockingDeque<>();
    }

    @Test
    void GIVEN_qos_equal_zero_WHEN_add_message_THEN_add_message_into_qos0_queue() throws InterruptedException {
        PublishRequest request = PublishRequest.builder().topic("spool").payload(new byte[0])
                .qos(QualityOfService.AT_MOST_ONCE).build();

        spool = spy(new Spool(deviceConfiguration, config, messageQueueOfQos0, messageQueueOfQos1And2));
        doNothing().when(spool).addMessageToSpooler(any(), any());
        Long id = spool.addMessage(request);

        verify(spool, never()).removeMessageById(any());
        verify(spool, times(1)).addMessageToSpooler(any(), any());
        assertEquals(1, spool.getMessageCountWithQos0());
        assertEquals(0, spool.getMessageCountWithQos1And2());
        assertEquals(new Long(0), id);
    }

    @Test
    void GIVEN_qos_not_equal_zero_WHEN_add_message_THEN_add_message_into_qos12_queue() throws InterruptedException {
        PublishRequest request = PublishRequest.builder().topic("spool").payload(new byte[0])
                .qos(QualityOfService.AT_LEAST_ONCE).build();

        spool = spy(new Spool(deviceConfiguration, config, messageQueueOfQos0, messageQueueOfQos1And2));
        doNothing().when(spool).addMessageToSpooler(any(), any());
        Long id = spool.addMessage(request);

        verify(spool, never()).removeOldestMessage();
        verify(spool, times(1)).addMessageToSpooler(any(), any());
        assertEquals(0, spool.getMessageCountWithQos0());
        assertEquals(1, spool.getMessageCountWithQos1And2());
        assertEquals(new Long(0), id);
    }

    @Test
    void GIVEN_spooler_queue_is_full_when_add_message_THEN_oldest_message_with_lower_qos_will_drop() throws InterruptedException {
        PublishRequest request1 = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(10).array())
                .qos(QualityOfService.AT_LEAST_ONCE).build();
        PublishRequest request2 = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(10).array())
                .qos(QualityOfService.AT_MOST_ONCE).build();


        spool = spy(new Spool(deviceConfiguration, config, messageQueueOfQos0, messageQueueOfQos1And2));
        spool.addMessage(request1);
        spool.addMessage(request2);
        assertEquals(1, spool.getMessageCountWithQos0());
        assertEquals(1, spool.getMessageCountWithQos1And2());

        spool.addMessage(request1);
        verify(spool, times(1)).removeOldestMessage();
        verify(spool, times(3)).addMessageToSpooler(any(), any());
        assertEquals(0, spool.getMessageCountWithQos0());
        assertEquals(2, spool.getMessageCountWithQos1And2());
    }


    @Test
    void GIVEN_qos0_and_qos12_are_not_empty_and_qos0_has_smallest_id_WHEN_get_queue_with_smallest_id_THEN_return_queue_of_qos0() {

        messageQueueOfQos0 = new LinkedBlockingDeque<>();
        messageQueueOfQos1And2 = new LinkedBlockingDeque<>();
        messageQueueOfQos0.addLast(new Long(1));
        messageQueueOfQos1And2.addLast(new Long(2));

        Spool spool = new Spool(deviceConfiguration, config, messageQueueOfQos0, messageQueueOfQos1And2);

        BlockingDeque<Long> queue = spool.getQueueWithSmallestMessageId();
        assertEquals(messageQueueOfQos0, queue);
    }

    @Test
    void GIVEN_qos0_and_qos_12_are_not_empty_and_qos12_has_smallest_id_WHEN_get_queue_with_smallest_i__THEN_return_queue_of_qos0() {

        messageQueueOfQos0 = new LinkedBlockingDeque<>();
        messageQueueOfQos1And2 = new LinkedBlockingDeque<>();
        messageQueueOfQos0.addLast(new Long(2));
        messageQueueOfQos1And2.addLast(new Long(1));

        Spool spool = new Spool(deviceConfiguration, config, messageQueueOfQos0, messageQueueOfQos1And2);

        BlockingDeque<Long> queue = spool.getQueueWithSmallestMessageId();
        assertEquals(messageQueueOfQos1And2, queue);
    }

    @Test
    void GIVEN_qos0_is_not_empty_and_qos12_is_empty_WHEN_WHEN_get_queue_with_smallest_id_THEN_return_queue_of_qos0() {
        messageQueueOfQos0 = new LinkedBlockingDeque<>();
        messageQueueOfQos1And2 = new LinkedBlockingDeque<>();
        messageQueueOfQos0.addLast(new Long(1));
        Spool spool = new Spool(deviceConfiguration, config, messageQueueOfQos0, messageQueueOfQos1And2);

        BlockingDeque<Long> queue = spool.getQueueWithSmallestMessageId();
        assertEquals(messageQueueOfQos0, queue);
    }

    @Test
    void GIVEN_qos1_is_empty_and_qos12_is_not_empty_WHEN_peek_id_THEN_return_queue_of_qos12() {
        messageQueueOfQos0 = new LinkedBlockingDeque<>();
        messageQueueOfQos1And2 = new LinkedBlockingDeque<>();
        messageQueueOfQos1And2.addLast(new Long(1));
        Spool spool = new Spool(deviceConfiguration, config, messageQueueOfQos0, messageQueueOfQos1And2);

        BlockingDeque<Long> queue = spool.getQueueWithSmallestMessageId();
        assertEquals(messageQueueOfQos1And2, queue);
    }

    @Test
    void GIVEN_qos1_is_empty_and_qos12_is_empty_WHEN_peek_id_THEN_return_null() {
        messageQueueOfQos0 = new LinkedBlockingDeque<>();
        messageQueueOfQos1And2 = new LinkedBlockingDeque<>();
        Spool spool = new Spool(deviceConfiguration, config, messageQueueOfQos0, messageQueueOfQos1And2);

        BlockingDeque<Long> queue = spool.getQueueWithSmallestMessageId();
        assertNull(queue);
    }
}
