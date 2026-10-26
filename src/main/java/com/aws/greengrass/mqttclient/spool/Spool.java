/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient.spool;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.mqttclient.v5.Publish;
import com.aws.greengrass.mqttclient.v5.QOS;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.LockFactory;
import com.aws.greengrass.util.LockScope;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import javax.annotation.Nullable;

public class Spool {
    private static final Logger logger = LogManager.getLogger(Spool.class);
    private static final String DEFAULT_GG_PERSISTENCE_SPOOL_SERVICE_NAME = "aws.greengrass.DiskSpooler";
    private static final String PERSISTENCE_SPOOL_SERVICE_NAME_KEY = "pluginName";
    public static final String SPOOL_STORAGE_TYPE_KEY = "storageType";
    private static final String SPOOL_MAX_SIZE_IN_BYTES_KEY = "maxSizeInBytes";
    private static final String SPOOL_KEEP_QOS_0_WHEN_OFFLINE_KEY = "keepQos0WhenOffline";
    private static final boolean DEFAULT_KEEP_Q0S_0_WHEN_OFFLINE = false;
    public static final SpoolerStorageType DEFAULT_SPOOL_STORAGE_TYPE = SpoolerStorageType.Memory;
    private static final int DEFAULT_SPOOL_MAX_MESSAGE_QUEUE_SIZE_IN_BYTES = (int) (2.5 * 1024 * 1024); // 2.5MB
    private final DeviceConfiguration deviceConfiguration;
    private final CloudMessageSpool spooler;
    private final InMemorySpool inMemorySpooler;
    private final Kernel kernel;
    private final ExecutorService executorService;
    private final AtomicLong nextId = new AtomicLong(0);
    private final BlockingDeque<Long> queueOfMessageId = new LinkedBlockingDeque<>();
    /**
     * Flag to see if we need to check for QOS0 messages or not, when we attempt to remove QOS0 messages
     * with removeMessagesWithQosZeromethod.
     * removeMessagesWithQosZero is called to remove QOS0 messages from Queue either when we are offline
     * or when we want to make space to accommodate a new incoming message.
     * - It is set to true everytime a new message has been added to spooler queue.
     * - If the flag is true, we will check the queue to look for QOS 0 messages
     *  when removeMessagesWithQosZero is called.
     * - It is set back to false at the end of the removeMessagesWithQosZero method.
     * - The flag remains false, if we know for sure that we removed all QOS0 messages due to being offline, or while
     *  trying to make space for a new message(and failed to do so)
     */
    private final AtomicBoolean qos0MessageCheckRequired = new AtomicBoolean(false);
    private final AtomicLong curMessageQueueSizeInBytes = new AtomicLong(0);
    private SpoolerConfig config;
    private final Lock lock = LockFactory.newReentrantLock(this);

    /**
     * Signals when the background disk load is complete. The persisted ids are already in the deque before the
     * load starts, but their sizes are not yet counted. Anything that removes from the deque (popId, the QoS 0
     * prune) must wait for, or skip until, the load completes. addMessage() only appends and never waits.
     */
    private CompletableFuture<Void> diskQueueLoaded;

    /**
     * Constructor.
     *
     * @param deviceConfiguration the device configuration
     * @param kernel              a kernel instance
     * @param executorService     executor for running background tasks
     */
    public Spool(DeviceConfiguration deviceConfiguration, Kernel kernel, ExecutorService executorService) {
        inMemorySpooler = new InMemorySpool();
        this.deviceConfiguration = deviceConfiguration;
        this.kernel = kernel;
        this.executorService = executorService;
        Topics topics = this.deviceConfiguration.getSpoolerNamespace();
        setSpoolerConfigFromDeviceConfig(topics);
        spooler = setupSpooler(executorService);
        topics.subscribe((what, node) -> {
            if (WhatHappened.childChanged.equals(what) && node != null) {
                setSpoolerConfigFromDeviceConfig(topics);
            }
        });
    }


    private void setSpoolerConfigFromDeviceConfig(Topics topics) {
        SpoolerStorageType spoolStorageType = Coerce.toEnum(SpoolerStorageType.class, topics
                .findOrDefault(DEFAULT_SPOOL_STORAGE_TYPE, SPOOL_STORAGE_TYPE_KEY));
        long spoolMaxMessageQueueSizeInBytes = Coerce.toLong(topics
                .findOrDefault(DEFAULT_SPOOL_MAX_MESSAGE_QUEUE_SIZE_IN_BYTES,
                        SPOOL_MAX_SIZE_IN_BYTES_KEY));
        boolean spoolKeepQos0WhenOffline = Coerce.toBoolean(topics
                .findOrDefault(DEFAULT_KEEP_Q0S_0_WHEN_OFFLINE, SPOOL_KEEP_QOS_0_WHEN_OFFLINE_KEY));
        String persistenceSpoolerServiceName = Coerce.toString(topics
                .findOrDefault(DEFAULT_GG_PERSISTENCE_SPOOL_SERVICE_NAME, PERSISTENCE_SPOOL_SERVICE_NAME_KEY));

        logger.atInfo().kv(SPOOL_STORAGE_TYPE_KEY, spoolStorageType)
                .kv(SPOOL_MAX_SIZE_IN_BYTES_KEY, spoolMaxMessageQueueSizeInBytes)
                .kv(SPOOL_KEEP_QOS_0_WHEN_OFFLINE_KEY, spoolKeepQos0WhenOffline)
                .log("Spooler has been configured");

        this.config = SpoolerConfig.builder().storageType(spoolStorageType)
                .spoolSizeInBytes(spoolMaxMessageQueueSizeInBytes)
                .keepQos0WhenOffline(spoolKeepQos0WhenOffline)
                .persistenceSpoolServiceName(persistenceSpoolerServiceName).build();
    }

    /**
     * create a spooler instance.
     *
     * @return CloudMessageSpool    spooler instance
     */
    private CloudMessageSpool setupSpooler(ExecutorService executorService) {
        if (config.getStorageType() == SpoolerStorageType.Disk) {
            try {
                return setupDiskSpooler(executorService);
            } catch (ServiceLoadException | IOException e) {
                logger.atWarn()
                        .kv(PERSISTENCE_SPOOL_SERVICE_NAME_KEY, config.getPersistenceSpoolServiceName())
                        .cause(e).log("Persistence spool set up failed, defaulting to InMemory Spooler");
            }
        }
        logger.atInfo().log("Memory Spooler has been set up");
        diskQueueLoaded = CompletableFuture.completedFuture(null);
        return inMemorySpooler;
    }

    /**
     * This function looks for the Greengrass service associated with the persistence spooler plugin.
     * @param executorService executor service
     * @return CloudMessageSpool instance
     * @throws ServiceLoadException thrown if the service cannot be located
     */
    private CloudMessageSpool setupDiskSpooler(ExecutorService executorService)
            throws ServiceLoadException, IOException {
        GreengrassService locatedService = kernel.locate(config.getPersistenceSpoolServiceName());
        if (!(locatedService instanceof CloudMessageSpool)) {
            throw new ServiceLoadException(
                    "The Greengrass service located was not an instance of CloudMessageSpool");
        }
        CloudMessageSpool persistenceSpool = (CloudMessageSpool) locatedService;
        persistenceSpool.initializeSpooler();

        // Fetch the persisted ids and advance nextId synchronously, before spawning the background
        // load. Reading the id list is cheap (no per-message body reads); the slow part that motivated
        // issue #1832 is the per-message getMessageById() loop, which stays on the background thread.
        //
        // Advancing nextId here guarantees a later addMessage() always allocates an id above every id
        // still on disk, so it cannot collide with a persisted row. If getAllMessageIds() fails, the
        // IOException propagates to setupSpooler() and we fall back to InMemorySpool, matching the
        // pre-async behavior instead of retaining a disk spooler with nextId=0 over a populated DB.
        List<Long> diskQueueOfIds = new ArrayList<>();
        long highestId = -1;
        for (long id : persistenceSpool.getAllMessageIds()) {
            diskQueueOfIds.add(id);
            highestId = Math.max(highestId, id);
        }
        nextId.set(highestId + 1);

        // Deliver oldest first. getAllMessageIds() does not guarantee an order, and persistentQueueSync()
        // relies on ascending ids to clean up after a partial load.
        Collections.sort(diskQueueOfIds);

        // Enqueue the persisted ids now, ahead of anything addMessage() appends, so delivery order is
        // preserved without making addMessage() wait for the load.
        queueOfMessageId.addAll(diskQueueOfIds);

        // Read each message on a background thread to account for its size and QoS without blocking kernel
        // startup (issue #1832). popId() will wait for this to complete before draining messages.
        diskQueueLoaded = CompletableFuture.runAsync(() -> {
            try {
                persistentQueueSync(diskQueueOfIds, persistenceSpool);
                logger.atInfo().kv(PERSISTENCE_SPOOL_SERVICE_NAME_KEY, config.getPersistenceSpoolServiceName())
                        .log("Persistent Spooler background load complete");
            } catch (SpoolerStoreException e) {
                logger.atWarn()
                        .kv(PERSISTENCE_SPOOL_SERVICE_NAME_KEY, config.getPersistenceSpoolServiceName())
                        .cause(e).log("Persistence spool queue sync was not completed, continuing with"
                                + " Persistent Spooler anyways");
            }
        }, executorService);

        // Note: this fires after the background load is *scheduled*, not after it finishes. The
        // "background load complete" log above marks actual completion.
        logger.atInfo().log("Persistent Spooler set up; message load running in background");
        return persistenceSpool;
    }

    /**
     * Blocks until all persisted message IDs have been loaded from disk into the in-memory deque.
     * Returns immediately if already complete or if using Memory storage.
     *
     * <p>Must be called by any method that consumes from or iterates the deque to guarantee
     * all persisted messages are present and ordered correctly.</p>
     */
    private void awaitDiskQueueLoaded() throws InterruptedException {
        try {
            diskQueueLoaded.get();
        } catch (ExecutionException e) {
            // Background load already logged the specific failure; surface it here too at WARN so a
            // partially-loaded queue is visible to fleet monitoring. Proceed with whatever was loaded.
            logger.atWarn().cause(e).log("Disk queue load completed with an error");
        }
    }

    /**
     * Add the MessageId to the front of the spooler queue.
     *
     * @param id MessageId
     */
    public void addId(long id) {
        queueOfMessageId.offerFirst(id);
    }

    /**
     * Spool the given PublishRequest.
     * <p></p>
     * If there is no room for the given PublishRequest, then QoS 0 PublishRequests will be deleted to make room.
     * If there is still no room after deleting QoS 0 PublishRequests, then an exception will be thrown.
     *
     * @param request publish request
     * @return SpoolMessage spool message
     * @throws InterruptedException  result from the queue implementation
     * @throws SpoolerStoreException if the message cannot be inserted into the message spool
     */
    public SpoolMessage addMessage(Publish request) throws InterruptedException,
            SpoolerStoreException {
        try (LockScope ls = LockScope.lock(lock)) {
            queueCapacityCheck(request, true);
            long id = nextId.getAndIncrement();
            SpoolMessage message = SpoolMessage.builder().id(id).request(request).build();
            addMessageToSpooler(id, message);
            queueOfMessageId.putLast(id);
            qos0MessageCheckRequired.set(true);
            return message;
        }
    }

    private void addMessageToSpooler(long id, SpoolMessage message) {
        try {
            spooler.add(id, message);
        } catch (IOException e) {
            // Exception is only thrown if Spooler is not InMemory spooler
            logger.atWarn().log("Disk Spooler failed to add Message, adding message to InMemory Spooler", e);
            inMemorySpooler.add(id, message);
        }
    }

    /**
     * Pop the id of the oldest PublishRequest.
     * Waits for disk-to-memory sync to complete before draining to ensure
     * all persisted messages are available and ordered correctly.
     *
     * @return message id
     * @throws InterruptedException the thread is interrupted while popping the first id from the queue
     */
    public long popId() throws InterruptedException {
        awaitDiskQueueLoaded();
        SpoolMessage message;
        long id;
        while (true) {
            id = queueOfMessageId.takeFirst();
            message = getMessageById(id);
            if (message != null) {
                break;
            }
        }
        return id;
    }

    /**
     * Get message from spooler, based on the given message ID.
     * <p></p>
     * Always try reading from InMemory spooler first as there might be messages put there due to fallback.
     * If not, continue reading from the configured spooler (either "Disk" or "Memory").
     *
     * @param messageId messageID for the messae
     * @return SpoolMessage spool message
     */
    @Nullable
    public SpoolMessage getMessageById(long messageId) {
        SpoolMessage messageFromMemory = inMemorySpooler.getMessageById(messageId);
        if (messageFromMemory != null) {
            return messageFromMemory;
        }
        if (config.getStorageType() == SpoolerStorageType.Disk) {
            return spooler.getMessageById(messageId);
        }
        return null;
    }

    /**
     * Remove the Message from the spooler based on the MessageId.
     *
     * @param messageId message id
     */
    public void removeMessageById(long messageId) {
        SpoolMessage toBeRemovedMessage = getMessageById(messageId);
        if (toBeRemovedMessage != null) {
            // Always remove from InMemory Spooler in case message was added into Memory spooler due to fallback
            inMemorySpooler.removeMessageById(messageId);
            if (config.getStorageType() == SpoolerStorageType.Disk) {
                spooler.removeMessageById(messageId);
            }
            int messageSize = toBeRemovedMessage.getRequest().getPayload().length;
            curMessageQueueSizeInBytes.getAndAdd(-1L * messageSize);
        }
    }

    /**
     * Remove the oldest QoS 0 messages from the spooler to make room for new messages.
     */
    public void removeOldestMessage() {
        removeMessagesWithQosZero(true);
    }

    /**
     * Remove all QoS 0 messages from the spooler queue. Called when the device goes offline and the
     * spooler is configured to not keep QoS 0 messages while disconnected.
     *
     * <p>This is invoked on the MQTT connection-event (CRT event-loop) thread, so it must not block.
     * The prune reads every queued message and may also have to wait for the disk-to-memory load, so
     * it always runs on the spool executor, after the load completes.</p>
     */
    public void popOutMessagesWithQosZero() {
        diskQueueLoaded.thenRunAsync(() -> removeMessagesWithQosZero(false), executorService)
                .whenComplete((unused, t) -> {
                    if (t != null) {
                        logger.atWarn().cause(t).log("QoS 0 offline prune failed");
                    }
                });
    }

    private void removeMessagesWithQosZero(boolean needToCheckCurSpoolerSize) {
        if (!qos0MessageCheckRequired.get()) {
            return;
        }
        // While the disk load runs, the deque holds persisted ids whose sizes are not counted yet, so removing
        // one would corrupt the size counter. Skip rather than wait: this is also reached from addMessage() with
        // the lock held. The offline prune is only scheduled after the load completes.
        if (!diskQueueLoaded.isDone()) {
            return;
        }
        // Not done under the lock: the scan reads every message from disk, and holding the lock for it would
        // block every addMessage() caller for the duration. The deque iterator is weakly consistent and the
        // size counter is atomic, so this is safe to run alongside addMessage(), as it always has been.
        Iterator<Long> messageIdIterator = queueOfMessageId.iterator();
        while (messageIdIterator.hasNext() && addJudgementWithCurrentSpoolerSize(needToCheckCurSpoolerSize)) {
            long id = messageIdIterator.next();
            SpoolMessage message = getMessageById(id);
            if (message != null) {
                Publish request = message.getRequest();
                int qos = request.getQos().getValue();
                if (qos == 0) {
                    removeMessageById(id);
                    logger.atDebug().kv("id", id).kv("topic", request.getTopic()).kv("Qos", qos)
                            .log("The spooler is configured to drop QoS 0 when offline. Dropping message now.");
                }
            }
        }
        qos0MessageCheckRequired.set(false);
    }

    private boolean addJudgementWithCurrentSpoolerSize(boolean needToCheckCurSpoolerSize) {
        if (!needToCheckCurSpoolerSize) {
            return true;
        }
        return curMessageQueueSizeInBytes.get() > getSpoolConfig().getSpoolSizeInBytes();
    }

    /**
     * Get the current number of message ids in the spooler queue.
     *
     * @return current message count
     */
    public int getCurrentMessageCount() {
        return queueOfMessageId.size();
    }

    /**
     * Get the current total size of messages in the spooler queue in bytes.
     *
     * @return current spooler size in bytes
     */
    public long getCurrentSpoolerSize() {
        return curMessageQueueSizeInBytes.get();
    }

    /**
     * Get the current spooler configuration.
     *
     * @return spooler config
     */
    public SpoolerConfig getSpoolConfig() {
        return config;
    }

    /**
     * Account for the size and QoS of persisted messages whose ids are already in queueOfMessageId. Runs on a
     * background thread to avoid blocking kernel startup (issue #1832).
     *
     * <p>If the spool fills up, the remaining (newer) persisted ids are taken back out of the queue, since their
     * sizes are not counted. A message that cannot be read is taken out of the queue on its own and the load
     * continues. Ids whose message is missing stay in the queue: nothing is counted for them and every reader
     * skips them.</p>
     *
     * @param diskQueueOfIds   messageIds to sync, in ascending order
     * @param persistenceSpool instance of CloudMessageSpool
     * @throws SpoolerStoreException thrown if message too large or spooler capacity exceeded
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void persistentQueueSync(List<Long> diskQueueOfIds, CloudMessageSpool persistenceSpool)
            throws SpoolerStoreException {
        for (long id : diskQueueOfIds) {
            try {
                SpoolMessage message = persistenceSpool.getMessageById(id);
                if (message == null) {
                    continue;
                }
                Publish request = message.getRequest();
                // Lock only around the accounting, not the disk read, so addMessage() is never held up for long.
                try (LockScope ls = LockScope.lock(lock)) {
                    queueCapacityCheck(request, false);
                }
                if (QOS.AT_MOST_ONCE.equals(request.getQos())) {
                    qos0MessageCheckRequired.set(true);
                }
            } catch (SpoolerStoreException e) {
                long last = diskQueueOfIds.get(diskQueueOfIds.size() - 1);
                queueOfMessageId.removeIf(queued -> queued >= id && queued <= last);
                throw e;
            } catch (RuntimeException e) {
                // Nothing was counted for this message, so drop only it and keep loading the rest.
                logger.atWarn().kv("id", id).cause(e).log("Failed to load persisted spool message, dropping it");
                queueOfMessageId.remove(id);
            }
        }
    }


    /**
     * This method checks if the max size of the queue will be reached if we add the current request.
     * (This function is extracted from addMessage to avoid unnecessary code duplication)
     *
     * @param request : PublishRequest instance
     * @throws SpoolerStoreException : thrown if message too large or spooler capacity exceeded
     */
    private void queueCapacityCheck(Publish request, boolean shouldReplaceOldMessage) throws SpoolerStoreException {

        int messageSizeInBytes = request.getPayload().length;
        if (messageSizeInBytes > getSpoolConfig().getSpoolSizeInBytes()) {
            throw new SpoolerStoreException("Message is larger than the size of message spool.");
        }

        curMessageQueueSizeInBytes.getAndAdd(messageSizeInBytes);
        if (curMessageQueueSizeInBytes.get() > getSpoolConfig().getSpoolSizeInBytes() && shouldReplaceOldMessage) {
            removeOldestMessage();
        }

        if (curMessageQueueSizeInBytes.get() > getSpoolConfig().getSpoolSizeInBytes()) {
            curMessageQueueSizeInBytes.getAndAdd(-1L * messageSizeInBytes);
            throw new SpoolerStoreException("Message spool is full. Message could not be added.");
        }
    }
}
