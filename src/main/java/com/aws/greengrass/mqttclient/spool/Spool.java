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
import java.util.Iterator;
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
     * Signals when the disk-to-memory queue sync is complete. Methods that consume from or iterate
     * the deque (popId, popOutMessagesWithQosZero) must call {@link #awaitDiskQueueLoaded()} first.
     * Methods that only append (addMessage) do not need to wait.
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
        // Run the per-message sync on a background thread to avoid blocking kernel startup
        // (issue #1832). popId() will wait for this to complete before draining messages.
        diskQueueLoaded = CompletableFuture.runAsync(() -> {
            try {
                persistentQueueSync(persistenceSpool.getAllMessageIds(), persistenceSpool);
            } catch (IOException e) {
                logger.atWarn()
                        .kv(PERSISTENCE_SPOOL_SERVICE_NAME_KEY, config.getPersistenceSpoolServiceName())
                        .cause(e).log("Failed to get message IDs from persistent spooler during"
                                + " background sync, continuing with Persistent Spooler anyways");
            } catch (SpoolerStoreException e) {
                logger.atWarn()
                        .kv(PERSISTENCE_SPOOL_SERVICE_NAME_KEY, config.getPersistenceSpoolServiceName())
                        .cause(e).log("Persistence spool queue sync was not completed, continuing with"
                                + " Persistent Spooler anyways");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.atWarn()
                        .kv(PERSISTENCE_SPOOL_SERVICE_NAME_KEY, config.getPersistenceSpoolServiceName())
                        .log("Persistence spool queue sync was interrupted, continuing with"
                                + " Persistent Spooler anyways");
            }
        }, executorService);

        logger.atInfo().log("Persistent Spooler has been set up");
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
            // Background load already logged its own failure; proceed with whatever was loaded.
            logger.atDebug().cause(e).log("Disk queue load completed with an error");
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
        // Wait for the disk-to-memory sync to complete before adding to guarantee that the nextId picked is higher
        // than all the persisted ids on disk.
        awaitDiskQueueLoaded();
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
     * <p>Waits for the disk-to-memory sync to complete before iterating the queue, so that all
     * persisted QoS 0 messages are visible and can be removed. If interrupted while waiting,
     * restores the interrupt flag and returns without removing any messages.</p>
     */
    public void popOutMessagesWithQosZero() {
        try {
            awaitDiskQueueLoaded();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        removeMessagesWithQosZero(false);
    }

    private void removeMessagesWithQosZero(boolean needToCheckCurSpoolerSize) {
        if (!qos0MessageCheckRequired.get()) {
            return;
        }
        try {
            awaitDiskQueueLoaded();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
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
     * Extract message ids from the persistenceSpool plugin's disk database and insert the message
     * ids into queueOfMessageId. Runs on a background thread to avoid blocking kernel startup.
     *
     * <p>Does not set {@link #nextId}: that is established synchronously in {@link #setupDiskSpooler}
     * from the full disk id list, before this method runs, so it stays correct even if this load stops
     * early (e.g. the persisted queue exceeds the configured spool size).</p>
     *
     * @param diskQueueOfIds   list of messageIds to sync
     * @param persistenceSpool instance of CloudMessageSpool
     * @throws InterruptedException  If interrupted
     * @throws SpoolerStoreException thrown if message too large or spooler capacity exceeded
     */
    public void persistentQueueSync(Iterable<Long> diskQueueOfIds, CloudMessageSpool persistenceSpool)
            throws InterruptedException, SpoolerStoreException {
        if (!diskQueueOfIds.iterator().hasNext()) {
            return;
        }

        // compute the highest persisted id from the full id list (cheap, no message reads).
        // nextId must clear every id on disk, not just the ones that fit in the queue, so a later
        // addMessage never collides with an id still on disk.
        long highestId = -1;
        for (long id : diskQueueOfIds) {
            highestId = Math.max(highestId, id);
        }
        nextId.set(highestId + 1);

        // load message bodies into the runtime queue up to capacity.
        int numMessages = 0;
        SpoolerStoreException e = null;
        int queueOfMessageIdInitSize = queueOfMessageId.size();
        for (long currentId : diskQueueOfIds) {
            numMessages++;
            SpoolMessage message = persistenceSpool.getMessageById(currentId);
            Publish request = message.getRequest();
            try {
                queueCapacityCheck(request, false);
                queueOfMessageId.putLast(currentId);
                if (QOS.AT_MOST_ONCE.equals(request.getQos())) {
                    qos0MessageCheckRequired.set(true);
                }
            } catch (SpoolerStoreException spoolerStoreException) {
                e = spoolerStoreException;
                break;
            }
        }
        logger.atInfo()
                .kv("numSpoolerMessages", numMessages)
                .kv("numMessagesAdded", queueOfMessageId.size() - queueOfMessageIdInitSize)
                .log("Messages added to spool runtime queue");
        if (e != null) {
            throw e;
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
