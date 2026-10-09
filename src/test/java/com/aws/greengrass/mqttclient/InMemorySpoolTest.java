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
import com.aws.greengrass.testcommons.testutilities.TestUtils;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    // Runs tasks on the calling thread to keep tests deterministic.
    private final ExecutorService executorService = TestUtils.synchronousExecutorService();
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
        spool = spy(new Spool(deviceConfiguration, kernel, executorService));
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
    void GIVEN_spooler_queue_is_full_with_qos1_messages_WHEN_add_3_new_messages_THEN_remove_qos0_check_is_done_only_once() throws InterruptedException, SpoolerStoreException {
        Publish request1 = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(10).array())
                .qos(QualityOfService.AT_LEAST_ONCE).build().toPublish();
        Publish request2 = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(10).array())
                .qos(QualityOfService.AT_LEAST_ONCE).build().toPublish();
        Publish request3 = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(10).array())
                .qos(QualityOfService.AT_LEAST_ONCE).build().toPublish();


        spool.addMessage(request1);
        spool.addMessage(request2);

        // Try to add 3 new messages
        assertThrows(SpoolerStoreException.class, () -> { spool.addMessage(request3); });
        assertThrows(SpoolerStoreException.class, () -> { spool.addMessage(request3); });
        assertThrows(SpoolerStoreException.class, () -> { spool.addMessage(request3); });
        verify(spool, times(3)).removeOldestMessage();
        // Check that the 2 existing messages were read(to see if they are qos0) only once when we try to
        // add message3 for the first time and skip for the remaining 2 attempts.
        // This verifies that the qos0MessageCheckRequired flag was set to false after the first attempt
        verify(spool, times(2)).getMessageById(anyLong());
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
        spool = new Spool(deviceConfiguration, kernel, executorService);
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

        spool = new Spool(deviceConfiguration, kernel, executorService);
        spool.addMessage(request);
        assertEquals(1, spool.getCurrentMessageCount());
        // getAllMessageIds() failing during setup must fall back to the in-memory spooler, not retain
        // a disk spooler whose nextId is still 0 over a populated DB. Verify the disk spooler is never
        // written to: addMessage() must not reach persistenceSpool.add(...).
        verify(persistenceSpool, never()).add(anyLong(), any(SpoolMessage.class));
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
        spool = new Spool(deviceConfiguration, kernel, executorService);

        assertEquals(3, spool.getCurrentMessageCount());

        // try to add 4th message
        spool.addMessage(request);
        // Should be able to add to InMemory spooler even if Disk Spooler Add failed
        assertEquals(4, spool.getCurrentMessageCount());
        // Should read from InMemory spooler first and successfully return a message, even if "Disk" Spooler is configured
        assertNotNull(spool.getMessageById(3L));
    }

    @Test
    void GIVEN_disk_load_in_progress_WHEN_popId_called_THEN_it_waits_for_full_load_before_returning()
            throws Exception {
        // Use a REAL async executor (not the synchronous one) so the background load genuinely runs
        // concurrently with the caller, exercising the awaitDiskQueueLoaded() gate on popId().
        ExecutorService asyncExecutor = Executors.newSingleThreadExecutor();
        try {
            GreengrassService persistenceSpoolService =
                    Mockito.mock(GreengrassService.class, withSettings().extraInterfaces(CloudMessageSpool.class));
            CloudMessageSpool persistenceSpool = (CloudMessageSpool) persistenceSpoolService;

            List<Long> messageIds = Arrays.asList(0L, 1L);
            CountDownLatch firstMessageLoaded = new CountDownLatch(1);
            CountDownLatch releaseSecondRead = new CountDownLatch(1);
            AtomicLong getByIdCalls = new AtomicLong(0);
            Publish request = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(1).array())
                    .qos(QualityOfService.AT_LEAST_ONCE).build().toPublish();

            lenient().when(kernel.locate(anyString())).thenReturn(persistenceSpoolService);
            lenient().when(persistenceSpool.getAllMessageIds()).thenReturn(messageIds);
            lenient().when(persistenceSpool.getMessageById(anyLong())).thenAnswer(inv -> {
                long id = inv.getArgument(0);
                getByIdCalls.incrementAndGet();
                if (id == 0L) {
                    // id 0 is enqueued right after this returns; signal that so the popper starts.
                    firstMessageLoaded.countDown();
                } else {
                    // Hold the load open before id 1 so popId() observes a partially loaded queue.
                    releaseSecondRead.await();
                }
                return SpoolMessage.builder().id(id).request(request).build();
            });

            config.lookup("spooler", SPOOL_STORAGE_TYPE_KEY).withValue("Disk");
            spool = new Spool(deviceConfiguration, kernel, asyncExecutor);

            AtomicLong firstPop = new AtomicLong(-1);
            // Number of getMessageById calls observed AT the moment popId() returned. Note popId itself
            // calls getMessageById once on the id it pops, so: gate present => id0 + id1 loaded (2) +
            // popId's own read = 3; gate absent => popId returns after only id0 loaded (1) + its own
            // read = 2, because id1's read is still blocked. This value distinguishes the two.
            AtomicLong callsAtReturn = new AtomicLong(-1);
            CountDownLatch firstPopCompleted = new CountDownLatch(1);
            Thread popper = new Thread(() -> {
                try {
                    long id = spool.popId();
                    callsAtReturn.set(getByIdCalls.get());
                    firstPop.set(id);
                    firstPopCompleted.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            popper.start();
            // Wait for Message 0 to load from disk
            firstMessageLoaded.await();

            // popId() must NOT return while the load is still mid-flight (id 1 read is blocked).
            assertFalse(firstPopCompleted.await(1, TimeUnit.SECONDS),
                    "popId() must block until the full background disk load completes, "
                            + "not return the partially-loaded head");

            // Release id 1's read; the load finishes and popId() may now return.
            releaseSecondRead.countDown();
            assertTrue(firstPopCompleted.await(10, TimeUnit.SECONDS),
                    "popId() must return once the background load completes");
            assertEquals(0L, firstPop.get(), "popId() must return the oldest persisted id first");

            // Without the gate in popId(), this would be 2 (for all the messages on disk)
            assertTrue(callsAtReturn.get() >= 3,
                    "popId() must not return until the entire disk load completed; getMessageById calls "
                            + "at return = " + callsAtReturn.get() + " (expected >= 3 with the gate)");
        } finally {
            asyncExecutor.shutdownNow();
        }
    }

    @Test
    void GIVEN_disk_load_in_progress_WHEN_addMessage_called_THEN_it_blocks_until_load_completes_then_allocates_id_above_persisted()
            throws Exception {
        // Use a REAL async executor so the background load genuinely runs concurrently with the caller,
        // exercising the awaitDiskQueueLoaded() gate on addMessage(). This is the addMessage() sibling
        // of the popId() concurrency test above and covers the other half of the new blocking contract.
        ExecutorService asyncExecutor = Executors.newSingleThreadExecutor();
        try {
            GreengrassService persistenceSpoolService =
                    Mockito.mock(GreengrassService.class, withSettings().extraInterfaces(CloudMessageSpool.class));
            CloudMessageSpool persistenceSpool = (CloudMessageSpool) persistenceSpoolService;

            // Two persisted messages, ids 5 and 6 (deliberately non-zero so a gate-less addMessage() that
            // allocated from nextId=0... would visibly collide with a persisted id). The mock loads id 5,
            // then BLOCKS before loading id 6, holding the background sync mid-flight.
            //
            // nextId is advanced to highestId+1 (== 7) synchronously in setupDiskSpooler before the load
            // is scheduled, so addMessage() should allocate 7 -- above every persisted id -- but only once
            // the load has completed (the gate). We assert both: it does not return early, and the id it
            // allocates is strictly greater than every persisted id.
            List<Long> messageIds = Arrays.asList(5L, 6L);
            CountDownLatch id5Loaded = new CountDownLatch(1);
            CountDownLatch releaseSecondRead = new CountDownLatch(1);
            AtomicLong getByIdCalls = new AtomicLong(0);
            Publish request = PublishRequest.builder().topic("spool").payload(ByteBuffer.allocate(1).array())
                    .qos(QualityOfService.AT_LEAST_ONCE).build().toPublish();

            lenient().when(kernel.locate(anyString())).thenReturn(persistenceSpoolService);
            lenient().when(persistenceSpool.getAllMessageIds()).thenReturn(messageIds);
            lenient().when(persistenceSpool.getMessageById(anyLong())).thenAnswer(inv -> {
                long id = inv.getArgument(0);
                getByIdCalls.incrementAndGet();
                if (id == 5L) {
                    id5Loaded.countDown();
                } else {
                    // Hold the load open before id 6 so addMessage() observes a mid-load state.
                    releaseSecondRead.await();
                }
                return SpoolMessage.builder().id(id).request(request).build();
            });

            config.lookup("spooler", SPOOL_STORAGE_TYPE_KEY).withValue("Disk");
            // Give the spool enough room that queueCapacityCheck never trips for these tiny messages.
            config.lookup("spooler", GG_SPOOL_MAX_SIZE_IN_BYTES_KEY).withValue(25000L);
            spool = new Spool(deviceConfiguration, kernel, asyncExecutor);

            AtomicLong allocatedId = new AtomicLong(-1);
            CountDownLatch addReturned = new CountDownLatch(1);
            Thread adder = new Thread(() -> {
                try {
                    SpoolMessage message = spool.addMessage(request);
                    allocatedId.set(message.getId());
                    addReturned.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (SpoolerStoreException e) {
                    // leave addReturned uncounted; the await assertion below will fail and surface it
                }
            });

            // Make sure the load has actually started (id 5 read) before we call addMessage().
            id5Loaded.await();
            adder.start();

            // addMessage() must NOT return while the load is still mid-flight (id 6 read is blocked),
            // otherwise nextId is not yet settled relative to the persisted ids still loading.
            assertFalse(addReturned.await(1, TimeUnit.SECONDS),
                    "addMessage() must block until the background disk load completes");

            // Release id 6's read; the load finishes and addMessage() may now proceed.
            releaseSecondRead.countDown();
            assertTrue(addReturned.await(10, TimeUnit.SECONDS),
                    "addMessage() must return once the background load completes");

            // The allocated id must be strictly greater than every persisted id (5, 6): the whole point
            // of gating addMessage() on the load is that nextId is advanced past the persisted range, so
            // a newly added message can never collide with a persisted row still on disk.
            assertEquals(7L, allocatedId.get(),
                    "addMessage() must allocate an id above every persisted id (max persisted = 6)");
            // Sanity: both persisted messages plus the newly added one are in the runtime queue.
            assertEquals(3, spool.getCurrentMessageCount());
        } finally {
            asyncExecutor.shutdownNow();
        }
    }
}
