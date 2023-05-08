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
import com.aws.greengrass.util.Coerce;

import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

public class Spool {
    private static final Logger logger = LogManager.getLogger(Spool.class);
    private static final String DEFAULT_GG_PERSISTENCE_SPOOL_SERVICE_NAME = "aws.greengrass.DiskSpooler";
    private static final String PERSISTENCE_SPOOL_SERVICE_NAME_KEY = "pluginName";
    private static final String SPOOL_STORAGE_TYPE_KEY = "storageType";
    private static final String SPOOL_MAX_SIZE_IN_BYTES_KEY = "maxSizeInBytes";
    private static final String SPOOL_KEEP_QOS_0_WHEN_OFFLINE_KEY = "keepQos0WhenOffline";
    private static final boolean DEFAULT_KEEP_Q0S_0_WHEN_OFFLINE = false;
    private static final SpoolerStorageType DEFAULT_SPOOL_STORAGE_TYPE = SpoolerStorageType.Memory;
    private static final int DEFAULT_SPOOL_MAX_MESSAGE_QUEUE_SIZE_IN_BYTES = (int) (2.5 * 1024 * 1024); // 2.5MB
    private final DeviceConfiguration deviceConfiguration;
    private final CloudMessageSpool spooler;
    private final InMemorySpool inMemorySpooler;
    private final Kernel kernel;
    private final AtomicLong nextId = new AtomicLong(0);
    private final BlockingDeque<Long> queueOfMessageId = new LinkedBlockingDeque<>();
    private final AtomicLong curMessageQueueSizeInBytes = new AtomicLong(0);
    private SpoolerConfig config;

    /**
     * Constructor.
     *
     * @param deviceConfiguration the device configuration
     * @param kernel              a kernel instance
     */
    public Spool(DeviceConfiguration deviceConfiguration, Kernel kernel) {
        this.deviceConfiguration = deviceConfiguration;
        Topics topics = this.deviceConfiguration.getSpoolerNamespace();
        this.kernel = kernel;
        setSpoolerConfigFromDeviceConfig(topics);
        inMemorySpooler = new InMemorySpool();
        spooler = setupSpooler();
        // To subscribe to the topics of spooler configuration
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
    private CloudMessageSpool setupSpooler() {
        if (config.getStorageType() == SpoolerStorageType.Disk) {
            try {
                return getPersistenceSpoolGGService();
            } catch (ServiceLoadException | IOException e) {
                //log and use InMemorySpool
                logger.atWarn()
                        .kv(PERSISTENCE_SPOOL_SERVICE_NAME_KEY, config.getPersistenceSpoolServiceName())
                        .cause(e).log("Persistence spool set up failed, defaulting to InMemory Spooler");
            }
        }
        return inMemorySpooler;
    }

    /**
     * This function looks for the Greengrass service associated with the persistence spooler plugin.
     *
     * @return CloudMessageSpool instance
     * @throws ServiceLoadException thrown if the service cannot be located
     */
    private CloudMessageSpool getPersistenceSpoolGGService()
            throws ServiceLoadException, IOException {
        GreengrassService locatedService = kernel.locate(config.getPersistenceSpoolServiceName());
        if (locatedService instanceof CloudMessageSpool) {
            CloudMessageSpool persistenceSpool = (CloudMessageSpool) locatedService;
            try {
                persistentQueueSync(persistenceSpool.getAllMessageIds(), persistenceSpool);
            } catch (SpoolerStoreException e) {
                logger.atWarn()
                        .kv(PERSISTENCE_SPOOL_SERVICE_NAME_KEY, config.getPersistenceSpoolServiceName())
                        .cause(e).log("Persistence spool queue sync was not completed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.atWarn()
                        .kv(PERSISTENCE_SPOOL_SERVICE_NAME_KEY, config.getPersistenceSpoolServiceName())
                        .cause(e).log("Persistence spool queue sync was not completed");
            }
            logger.atInfo().log("Persistent Spooler has been set up");
            return persistenceSpool;
        } else {
            throw new ServiceLoadException(
                    "The Greengrass service located was not an instance of CloudMessageSpool"
            );
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
    public synchronized SpoolMessage addMessage(Publish request) throws InterruptedException,
            SpoolerStoreException {
        queueCapacityCheck(request, true);
        long id = nextId.getAndIncrement();
        SpoolMessage message = SpoolMessage.builder().id(id).request(request).build();
        addMessageToSpooler(id, message);
        queueOfMessageId.putLast(id);

        return message;
    }

    private void addMessageToSpooler(long id, SpoolMessage message) {
        try {
            spooler.add(id, message);
        } catch (IOException e) {
            logger.atWarn().log("Disk Spooler failed to add Message, adding message to InMemory Spooler", e);
            inMemorySpooler.add(id, message);
        }
    }

    /**
     * Pop the id of the oldest PublishRequest.
     *
     * @return message id
     * @throws InterruptedException the thread is interrupted while popping the first id from the queue
     */
    public long popId() throws InterruptedException {
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
        return spooler.getMessageById(messageId);
    }

    /**
     * Remove the Message from the spooler based on the MessageId.
     *
     * @param messageId message id
     */
    public void removeMessageById(long messageId) {
        SpoolMessage toBeRemovedMessage = getMessageById(messageId);
        if (toBeRemovedMessage != null) {
            spooler.removeMessageById(messageId);
            int messageSize = toBeRemovedMessage.getRequest().getPayload().length;
            curMessageQueueSizeInBytes.getAndAdd(-1L * messageSize);
        }
    }

    public void removeOldestMessage() {
        removeMessagesWithQosZero(true);
    }

    public void popOutMessagesWithQosZero() {
        removeMessagesWithQosZero(false);
    }

    private void removeMessagesWithQosZero(boolean needToCheckCurSpoolerSize) {
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
    }

    private boolean addJudgementWithCurrentSpoolerSize(boolean needToCheckCurSpoolerSize) {
        if (!needToCheckCurSpoolerSize) {
            return true;
        }
        return curMessageQueueSizeInBytes.get() > getSpoolConfig().getSpoolSizeInBytes();
    }

    public int getCurrentMessageCount() {
        return queueOfMessageId.size();
    }

    public long getCurrentSpoolerSize() {
        return curMessageQueueSizeInBytes.get();
    }

    public SpoolerConfig getSpoolConfig() {
        return config;
    }

    /**
     * Extract message ids from the persistenceSpool plugin's on disk database and insert the message \
     * ids into queueOfMessageId, this function is only used in Disk storage mode.
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
        long highestId = -1;
        int numMessages = 0;
        int queueOfMessageIdInitSize = queueOfMessageId.size();
        for (long currentId : diskQueueOfIds) {
            numMessages++;
            //Check for queue space and remove if necessary
            SpoolMessage message = persistenceSpool.getMessageById(currentId);
            Publish request = message.getRequest();
            queueCapacityCheck(request, false);

            queueOfMessageId.putLast(currentId);
            if (currentId > highestId) {
                highestId = currentId;
            }
        }
        logger.atInfo()
                .kv("numSpoolerMessages", numMessages)
                .kv("numMessagesAdded", queueOfMessageId.size() - queueOfMessageIdInitSize)
                .log("Messages added to spool runtime queue");
        nextId.set(highestId + 1);
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
