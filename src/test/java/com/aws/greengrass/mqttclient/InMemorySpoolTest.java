/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.mqttclient.spool.CloudMessageSpool;
import com.aws.greengrass.mqttclient.spool.Spool;
import com.aws.greengrass.mqttclient.spool.SpoolMessage;
import com.aws.greengrass.mqttclient.spool.SpoolerStoreException;
import com.aws.greengrass.mqttclient.v5.Publish;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.mqtt.QualityOfService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class InMemorySpoolTest {

    @Mock
    DeviceConfiguration deviceConfiguration;

    private Spool spool;
    Configuration config = new Configuration(new Context());
    private static final String GG_SPOOL_MAX_SIZE_IN_BYTES_KEY = "maxSizeInBytes";
    private static final String SPOOL_STORAGE_TYPE_KEY = "storageType";
    @Mock
    Kernel kernel;
    @Mock
    Context context;

    @BeforeEach
    void beforeEach() throws SpoolerStoreException {
        config.lookup("spooler", GG_SPOOL_MAX_SIZE_IN_BYTES_KEY).withValue(25L);
        lenient().when(deviceConfiguration.getSpoolerNamespace()).thenReturn(config.lookupTopics("spooler"));
        spool = spy(new Spool(deviceConfiguration, kernel));
    }

    @AfterEach
    void after() throws IOException {
        config.context.close();
    }

    @Test
    void GIVEN_publish_request_should_not_be_null_WHEN_pop_id_THEN_continue_if_request_is_null() throws InterruptedException, SpoolerStoreException {
        Publish request = PublishRequest.builder().topic("spool").payload(new byte[0])
                .qos(QualityOfService.AT_MOST_ONCE).build().toPublish();

        long id1 = spool.addMessage(request).getId();
        long id2 = spool.addMessage(request).getId();
        spool.removeMessageById(id1);

        long id = spool.popId();
        assertEquals(id2, id);
    }

    @Test
    void GIVEN_spooler_is_not_full_WHEN_add_message_THEN_add_message_without_message_dropped() throws InterruptedException, SpoolerStoreException {
        Publish request = PublishRequest.builder().topic("spool").payload(new byte[0])
                .qos(QualityOfService.AT_MOST_ONCE).build().toPublish();

        long id = spool.addMessage(request).getId();

        verify(spool, never()).removeMessageById(anyLong());
        assertEquals(1, spool.getCurrentMessageCount());
        assertEquals(0L, id);
    }

    @Test
    void GIVEN_spooler_is_full_WHEN_add_message_THEN_drop_messages() throws InterruptedException, SpoolerStoreException {
        Publish request1 = PublishRequest.builder().topic("spool").payload(new byte[10])
                .qos(QualityOfService.AT_LEAST_ONCE).build().toPublish();
        Publish request2 = PublishRequest.builder().topic("spool").payload(new byte[10])
                .qos(QualityOfService.AT_MOST_ONCE).build().toPublish();

        spool.addMessage(request1);
        long id2 = spool.addMessage(request2).getId();
        spool.addMessage(request2);

        verify(spool, times(1)).removeMessageById(id2);
        assertEquals(20, spool.getCurrentSpoolerSize());
    }

    @Test
    void GIVEN_spooler_queue_is_full_and_not_have_enough_space_for_new_message_when_add_message_THEN_throw_exception() throws InterruptedException, SpoolerStoreException {
        Publish request1 = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(10).array())
                .qos(QualityOfService.AT_LEAST_ONCE).build().toPublish();
        Publish request2 = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(10).array())
                .qos(QualityOfService.AT_MOST_ONCE).build().toPublish();
        Publish request3 = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(20).array())
                .qos(QualityOfService.AT_MOST_ONCE).build().toPublish();


        spool.addMessage(request1);
        long id2 = spool.addMessage(request2).getId();

        assertThrows(SpoolerStoreException.class, () -> { spool.addMessage(request3); });

        verify(spool, times(1)).removeOldestMessage();
        assertEquals(10, spool.getCurrentSpoolerSize());
        verify(spool, times(1)).removeMessageById(id2);
    }

    @Test
    void GIVEN_message_size_exceeds_max_size_of_spooler_when_add_message_THEN_throw_exception() throws InterruptedException, SpoolerStoreException {
        Publish request = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(30).array())
                .qos(QualityOfService.AT_LEAST_ONCE).build().toPublish();

        assertThrows(SpoolerStoreException.class, () -> { spool.addMessage(request); });

        assertEquals(0, spool.getCurrentSpoolerSize());
    }

    @Test
    void GIVEN_id_WHEN_remove_message_by_id_THEN_spooler_size_decreased() throws SpoolerStoreException, InterruptedException {
        Publish request = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(10).array())
                .qos(QualityOfService.AT_LEAST_ONCE).build().toPublish();
        SpoolMessage message = spool.addMessage(request);
        long id = message.getId();

        spool.removeMessageById(id);

        assertEquals(0, spool.getCurrentSpoolerSize());
    }

    @Test
    void GIVEN_message_with_qos_zero_WHEN_pop_out_messages_with_qos_zero_THEN_only_remove_message_with_qos_zero() throws SpoolerStoreException, InterruptedException {
        Publish request1 = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(1).array())
                .qos(QualityOfService.AT_LEAST_ONCE).build().toPublish();
        Publish request2 = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(2).array())
                .qos(QualityOfService.AT_MOST_ONCE).build().toPublish();
        Publish request3 = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(4).array())
                .qos(QualityOfService.AT_MOST_ONCE).build().toPublish();
        List<Publish> requests = Arrays.asList(request1, request2, request3);

        for (Publish request : requests) {
            spool.addMessage(request);
        }

        spool.popOutMessagesWithQosZero();

        verify(spool, times(2)).removeMessageById(anyLong());
        assertEquals(1, spool.getCurrentSpoolerSize());
    }

    @Test
    void GIVEN_spooler_config_disk_WHEN_setup_spooler_THEN_persistent_queue_synced() throws ServiceLoadException, IOException {
        List<Long> messageIds = Arrays.asList(0L, 1L, 2L);
        GreengrassService persistenceSpoolService = Mockito.mock(GreengrassService.class, withSettings().extraInterfaces(CloudMessageSpool.class));
        CloudMessageSpool persistenceSpool = (CloudMessageSpool) persistenceSpoolService;

        Publish request = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(5).array())
                .qos(QualityOfService.AT_LEAST_ONCE).build().toPublish();

        SpoolMessage message0 = SpoolMessage.builder().id(0L).request(request).build();
        SpoolMessage message1 = SpoolMessage.builder().id(1L).request(request).build();
        SpoolMessage message2 = SpoolMessage.builder().id(2L).request(request).build();

        lenient().when(kernel.locate(anyString())).thenReturn(persistenceSpoolService);
        lenient().when(persistenceSpool.getAllMessageIds()).thenReturn(messageIds);
        lenient().when(persistenceSpool.getMessageById(0L)).thenReturn(message0);
        lenient().when(persistenceSpool.getMessageById(1L)).thenReturn(message1);
        lenient().when(persistenceSpool.getMessageById(2L)).thenReturn(message2);

        config.lookup("spooler", SPOOL_STORAGE_TYPE_KEY).withValue("Disk");
        spool = new Spool(deviceConfiguration, kernel);
        assertEquals(3, spool.getCurrentMessageCount());
    }

    @Test
    void GIVEN_spooler_config_disk_WHEN_persistent_queue_sync_THEN_sync_only_adds_new_messageIDs() throws ServiceLoadException, IOException, SpoolerStoreException, InterruptedException {
        List<Long> messageIds = Arrays.asList(0L, 1L, 2L);
        GreengrassService persistenceSpoolService = Mockito.mock(GreengrassService.class, withSettings().extraInterfaces(CloudMessageSpool.class));
        CloudMessageSpool persistenceSpool = (CloudMessageSpool) persistenceSpoolService;

        Publish request = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(1).array())
                .qos(QualityOfService.AT_LEAST_ONCE).build().toPublish();

        SpoolMessage message0 = SpoolMessage.builder().id(0L).request(request).build();
        SpoolMessage message1 = SpoolMessage.builder().id(1L).request(request).build();
        SpoolMessage message2 = SpoolMessage.builder().id(2L).request(request).build();

        config.lookup("spooler", SPOOL_STORAGE_TYPE_KEY).withValue("Disk");
        lenient().when(kernel.locate(anyString())).thenReturn(persistenceSpoolService);
        lenient().when(persistenceSpool.getMessageById(0L)).thenReturn(message0);
        lenient().when(persistenceSpool.getMessageById(1L)).thenReturn(message1);
        lenient().when(persistenceSpool.getMessageById(2L)).thenReturn(message2);

        spool = new Spool(deviceConfiguration, kernel);
        // Add 3 messages
        spool.addMessage(request);
        spool.addMessage(request);
        spool.addMessage(request);
        assertEquals(3, spool.getCurrentMessageCount());

        // Sync messages from Database (mocked to give out 3 messages with IDs 0,1,2)
        lenient().when(persistenceSpool.getAllMessageIds()).thenReturn(messageIds);
        spool.persistentQueueSync(persistenceSpool.getAllMessageIds(), persistenceSpool);
        // Validate Message IDs Queue size is still same as these IDs are already present in the Queue
        assertEquals(3, spool.getCurrentMessageCount());
    }

    @Test
    void GIVEN_spooler_config_disk_WHEN_setup_spooler_failed_THEN_use_in_memory_spooler(ExtensionContext context) throws ServiceLoadException, IOException, SpoolerStoreException, InterruptedException {
        ignoreExceptionOfType(context, IOException.class);
        GreengrassService persistenceSpoolService = Mockito.mock(GreengrassService.class, withSettings().extraInterfaces(CloudMessageSpool.class));
        CloudMessageSpool persistenceSpool = (CloudMessageSpool) persistenceSpoolService;
        Publish request = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(5).array())
                .qos(QualityOfService.AT_LEAST_ONCE).build().toPublish();

        config.lookup("spooler", SPOOL_STORAGE_TYPE_KEY).withValue("Disk");
        lenient().when(kernel.locate(anyString())).thenReturn(persistenceSpoolService);
        lenient().when(persistenceSpool.getAllMessageIds()).thenThrow(new IOException("Get all message IDs failed for Disk Spooler"));

        spool = new Spool(deviceConfiguration, kernel);
        spool.addMessage(request);
        assertEquals(1, spool.getCurrentMessageCount());
    }

    @Test
    void GIVEN_spooler_config_disk_WHEN_disk_spooler_add_fail_THEN_add_in_memory_spooler(ExtensionContext context) throws ServiceLoadException, IOException, InterruptedException, SpoolerStoreException {
        ignoreExceptionOfType(context, IOException.class);
        List<Long> messageIds = Arrays.asList(0L, 1L, 2L);
        GreengrassService persistenceSpoolService = Mockito.mock(GreengrassService.class, withSettings().extraInterfaces(CloudMessageSpool.class));
        CloudMessageSpool persistenceSpool = (CloudMessageSpool) persistenceSpoolService;

        Publish request = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(5).array())
                .qos(QualityOfService.AT_LEAST_ONCE).build().toPublish();

        SpoolMessage message0 = SpoolMessage.builder().id(0L).request(request).build();
        SpoolMessage message1 = SpoolMessage.builder().id(1L).request(request).build();
        SpoolMessage message2 = SpoolMessage.builder().id(2L).request(request).build();

        lenient().when(kernel.locate(anyString())).thenReturn(persistenceSpoolService);
        lenient().when(persistenceSpool.getAllMessageIds()).thenReturn(messageIds);
        lenient().when(persistenceSpool.getMessageById(0L)).thenReturn(message0);
        lenient().when(persistenceSpool.getMessageById(1L)).thenReturn(message1);
        lenient().when(persistenceSpool.getMessageById(2L)).thenReturn(message2);
        lenient().doThrow(new IOException("Spooler Add failed")).
                when(persistenceSpool).add(anyLong(), any(SpoolMessage.class));

        config.lookup("spooler", SPOOL_STORAGE_TYPE_KEY).withValue("Disk");
        spool = new Spool(deviceConfiguration, kernel);

        assertEquals(3, spool.getCurrentMessageCount());

        // try to add 4th message
        spool.addMessage(request);
        // Should be able to add to InMemory spooler even if Disk Spooler Add failed
        assertEquals(4, spool.getCurrentMessageCount());
        // Should read from InMemory spooler first and successfully return a message, even if "Disk" Spooler is configured
        assertNotNull(spool.getMessageById(3L));
    }
}
