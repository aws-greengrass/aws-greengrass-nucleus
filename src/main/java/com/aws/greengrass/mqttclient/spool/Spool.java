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
import com.aws.greengrass.mqttclient.PublishRequest;
import com.aws.greengrass.util.Coerce;

import java.util.Iterator;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;

public class Spool {
    private static final Logger logger = LogManager.getLogger(Spool.class);
    private static final String DEFAULT_PERSISTENCE_SPOOL_SERVICE_NAME = "aws.greengrass.persistence.spooler";
    private static final String GG_PERSISTENCE_SPOOL_SERVICE_NAME_KEY = "persistenceSpoolServiceName";
    private static final String GG_SPOOL_STORAGE_TYPE_KEY = "storageType";
    private static final String GG_SPOOL_MAX_SIZE_IN_BYTES_KEY = "maxSizeInBytes";
    private static final String GG_SPOOL_KEEP_QOS_0_WHEN_OFFLINE_KEY = "keepQos0WhenOffline";
    private static final boolean DEFAULT_KEEP_Q0S_0_WHEN_OFFLINE = false;
    private static final SpoolerStorageType DEFAULT_GG_SPOOL_STORAGE_TYPE = SpoolerStorageType.Memory;
    private static final int DEFAULT_GG_SPOOL_MAX_MESSAGE_QUEUE_SIZE_IN_BYTES = (int) (2.5 * 1024 * 1024); // 2.5MB
    private final DeviceConfiguration deviceConfiguration;
    private final CloudMessageSpool spooler;
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
        spooler = setupSpooler();
        // To subscribe to the topics of spooler configuration
        topics.subscribe((what, node) -> {
            if (WhatHappened.childChanged.equals(what) && node != null) {
                setSpoolerConfigFromDeviceConfig(topics);
            }
        });
    }


    private void setSpoolerConfigFromDeviceConfig(Topics topics) {
        SpoolerStorageType ggSpoolStorageType = Coerce.toEnum(SpoolerStorageType.class, topics
                .findOrDefault(DEFAULT_GG_SPOOL_STORAGE_TYPE, GG_SPOOL_STORAGE_TYPE_KEY));
        long ggSpoolMaxMessageQueueSizeInBytes = Coerce.toLong(topics
                .findOrDefault(DEFAULT_GG_SPOOL_MAX_MESSAGE_QUEUE_SIZE_IN_BYTES,
                        GG_SPOOL_MAX_SIZE_IN_BYTES_KEY));
        boolean ggSpoolKeepQos0WhenOffline = Coerce.toBoolean(topics
                .findOrDefault(DEFAULT_KEEP_Q0S_0_WHEN_OFFLINE, GG_SPOOL_KEEP_QOS_0_WHEN_OFFLINE_KEY));
        String ggPersistenceSpoolerServiceName = Coerce.toString(topics
                .findOrDefault(DEFAULT_PERSISTENCE_SPOOL_SERVICE_NAME, GG_PERSISTENCE_SPOOL_SERVICE_NAME_KEY));

        logger.atInfo().kv(GG_SPOOL_STORAGE_TYPE_KEY, ggSpoolStorageType)
                .kv(GG_SPOOL_MAX_SIZE_IN_BYTES_KEY, ggSpoolMaxMessageQueueSizeInBytes)
                .kv(GG_SPOOL_KEEP_QOS_0_WHEN_OFFLINE_KEY, ggSpoolKeepQos0WhenOffline)
                .log("Spooler has been configured");

        this.config = SpoolerConfig.builder().storageType(ggSpoolStorageType)
                .spoolSizeInBytes(ggSpoolMaxMessageQueueSizeInBytes)
                .keepQos0WhenOffline(ggSpoolKeepQos0WhenOffline)
                .persistenceSpoolServiceName(ggPersistenceSpoolerServiceName).build();
    }

    /**
     * create a spooler instance.
     *
     * @return CloudMessageSpool    spooler instance
     */
    private CloudMessageSpool setupSpooler() {
        if (config.getStorageType() == SpoolerStorageType.Memory) {
            return new InMemorySpool();
        } else if (config.getStorageType() == SpoolerStorageType.Plugin) {
            try {
                return getPersistenceSpoolGGService();
            } catch (ServiceLoadException | SpoolerStoreException e) {
                //log and use InMemorySpool
                logger.atWarn()
                        .kv(GG_PERSISTENCE_SPOOL_SERVICE_NAME_KEY, config.getPersistenceSpoolServiceName())
                        .cause(e).log("Persistence spool set up failed, defaulting to in-memory mode");
                return new InMemorySpool();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return null;
    }

    /**
     * This function looks for the Greengrass service associated with the persistence spooler plugin.
     *
     * @return CloudMessageSpool instance
     * @throws ServiceLoadException thrown if the service cannot be located
     */
    private CloudMessageSpool getPersistenceSpoolGGService()
            throws ServiceLoadException, InterruptedException, SpoolerStoreException {
        GreengrassService locatedService = kernel.locate(config.getPersistenceSpoolServiceName());
        if (locatedService instanceof CloudMessageSpool) {
            CloudMessageSpool persistenceSpool = (CloudMessageSpool) locatedService;
            persistentQueueSync(persistenceSpool.getAllSpoolMessageIds(), persistenceSpool);
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
    public synchronized SpoolMessage addMessage(PublishRequest request) throws InterruptedException,
            SpoolerStoreException {
        queueCapacityCheck(request);
        long id = nextId.getAndIncrement();
        SpoolMessage message = SpoolMessage.builder().id(id).request(request).build();
        addMessageToSpooler(id, message);
        queueOfMessageId.putLast(id);

        return message;
    }

    private void addMessageToSpooler(long id, SpoolMessage message) {
        spooler.add(id, message);
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

    public SpoolMessage getMessageById(long messageId) {
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
            PublishRequest request = getMessageById(id).getRequest();
            int qos = request.getQos().getValue();
            if (qos == 0) {
                removeMessageById(id);
                logger.atDebug().kv("id", id).kv("topic", request.getTopic()).kv("Qos", qos)
                        .log("The spooler is configured to drop QoS 0 when offline. "
                                + "Dropping message now");
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
     * ids into queueOfMessageId, this function is only used in FileSystem storage mode.
     *
     * @param diskQueueOfIds   list of messageIds to sync
     * @param persistenceSpool instance of CloudMessageSpool
     */
    private void persistentQueueSync(Iterable<Long> diskQueueOfIds, CloudMessageSpool persistenceSpool)
            throws InterruptedException, SpoolerStoreException {
        if (diskQueueOfIds == null) {
            return;
        }
        long highestId = -1;
        int numMessages = 0;
        int queueOfMessageIdInitSize = queueOfMessageId.size();
        for (long currentId : diskQueueOfIds) {
            numMessages++;
            //Check for queue space and remove if necessary
            SpoolMessage message = persistenceSpool.getMessageById(currentId);
            PublishRequest request = message.getRequest();
            queueCapacityCheck(request);

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
    private void queueCapacityCheck(PublishRequest request) throws SpoolerStoreException {

        int messageSizeInBytes = request.getPayload().length;
        if (messageSizeInBytes > getSpoolConfig().getSpoolSizeInBytes()) {
            throw new SpoolerStoreException("Message is larger than the size of message spool.");
        }

        curMessageQueueSizeInBytes.getAndAdd(messageSizeInBytes);
        if (curMessageQueueSizeInBytes.get() > getSpoolConfig().getSpoolSizeInBytes()) {
            removeOldestMessage();
        }

        if (curMessageQueueSizeInBytes.get() > getSpoolConfig().getSpoolSizeInBytes()) {
            curMessageQueueSizeInBytes.getAndAdd(-1L * messageSizeInBytes);
            throw new SpoolerStoreException("Message spool is full. Message could not be added.");
        }
    }
}
