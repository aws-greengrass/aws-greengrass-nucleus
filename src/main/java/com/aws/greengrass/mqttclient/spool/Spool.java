/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient.spool;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.deployment.DeviceConfiguration;
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
    private final DeviceConfiguration deviceConfiguration;
    private final CloudMessageSpool spooler;

    private static final String GG_SPOOL_STORAGE_TYPE_KEY = "spoolStorageType";
    private static final String GG_SPOOL_MAX_MESSAGE_QUEUE_SIZE_IN_BYTES_KEY = "spoolMaxMessageQueueSizeInBytes";
    private static final String GG_SPOOL_KEEP_QOS_0_WHEN_OFFLINE_KEY = "keepQos0WhenOffline";

    private static final boolean DEFAULT_KEEP_Q0S_0_WHEN_OFFLINE = false;
    private static final SpoolerStorageType DEFAULT_GG_SPOOL_STORAGE_TYPE
            = SpoolerStorageType.Memory;
    private static final int DEFAULT_GG_SPOOL_MAX_MESSAGE_QUEUE_SIZE_IN_BYTES = (int)(2.5 * 1024 * 1024); // 2.5MB

    private final AtomicLong nextId = new AtomicLong(0);
    private SpoolerConfig config;
    private final BlockingDeque<Long> queueOfMessageId = new LinkedBlockingDeque<>();
    private final AtomicLong curMessageQueueSizeInBytes = new AtomicLong(0);


    /**
     * The constructor of Spool.
     * @param deviceConfiguration the device configuration
     * @throws InterruptedException if aaa else bbb
     *
     */
    public Spool(DeviceConfiguration deviceConfiguration) {
        this.deviceConfiguration = deviceConfiguration;
        Topics topics = this.deviceConfiguration.getSpoolerNamespace();
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
                        GG_SPOOL_MAX_MESSAGE_QUEUE_SIZE_IN_BYTES_KEY));
        boolean ggSpoolKeepQos0WhenOffline = Coerce.toBoolean(topics
                .findOrDefault(DEFAULT_KEEP_Q0S_0_WHEN_OFFLINE, GG_SPOOL_KEEP_QOS_0_WHEN_OFFLINE_KEY));

        logger.atInfo().kv(GG_SPOOL_STORAGE_TYPE_KEY, ggSpoolStorageType)
                .kv(GG_SPOOL_MAX_MESSAGE_QUEUE_SIZE_IN_BYTES_KEY, ggSpoolMaxMessageQueueSizeInBytes)
                .kv(GG_SPOOL_KEEP_QOS_0_WHEN_OFFLINE_KEY, ggSpoolKeepQos0WhenOffline)
                .log("Spooler has been configured");

        this.config = SpoolerConfig.builder().storageType(ggSpoolStorageType)
                .spoolSizeInBytes(ggSpoolMaxMessageQueueSizeInBytes)
                .keepQos0WhenOffline(ggSpoolKeepQos0WhenOffline).build();
    }

    /**
     * create a spooler instance.
     * @return CloudMessageSpool    spooler instance
     */
    private CloudMessageSpool setupSpooler() {
        if (config.getStorageType() == SpoolerStorageType.Memory) {
            return new InMemorySpool();
        }
        return null;
        //return new PersistentSpool();
    }

    /**
     * Add the MessageId to the front of the queue of Id based on the Qos.
     *
     * @param id MessageId
     */
    public void addId(long id) {
        queueOfMessageId.offerFirst(id);
    }

    /**
     * Add the message to both of the Queue of Id and the spooler.
     * Drop the oldest message if the current queue size is greater than the settings.
     *
     * @param request publish request
     * @throws InterruptedException result from the queue implementation
     * @throws SpoolerLoadException  leads to the failure to insert the message to the spooler
     */
    public synchronized long addMessage(PublishRequest request) throws InterruptedException, SpoolerLoadException {
        // TODO: revisit the thread safety and whether the "synchronized" could be removed
        int messageSizeInBytes = request.getPayload().length;
        if (messageSizeInBytes > getSpoolConfig().getSpoolSizeInBytes()) {
            throw new SpoolerLoadException("The size of message has exceeds the maximum size of spooler.");
        }

        curMessageQueueSizeInBytes.getAndAdd(messageSizeInBytes);
        if (curMessageQueueSizeInBytes.get() > getSpoolConfig().getSpoolSizeInBytes()) {
            removeOldestMessage();
        }

        if (curMessageQueueSizeInBytes.get() > getSpoolConfig().getSpoolSizeInBytes()) {
            curMessageQueueSizeInBytes.getAndAdd(-1 * messageSizeInBytes);
            throw new SpoolerLoadException("Spooler queue is full and new message would not be added into spooler");
        }

        long id = nextId.getAndIncrement();
        addMessageToSpooler(id, request);
        queueOfMessageId.putLast(id);

        return id;
    }

    private void addMessageToSpooler(long id, PublishRequest request) {
        spooler.add(id, request);
    }

    /**
     * Pop out the id of the oldest message.
     *
     * @return message id
     * @throws InterruptedException the thread is interrupted while popping the first id from the queue
     */
    public long popId() throws InterruptedException {
        return queueOfMessageId.takeFirst();
    }

    public PublishRequest getMessageById(long messageId) {
        return spooler.getMessageById(messageId);
    }

    /**
     * Remove the Message from the spooler based on the MessageId.
     *
     * @param messageId  message id
     */
    public void removeMessageById(long messageId) {
        PublishRequest toBeRemovedRequest = getMessageById(messageId);
        if (toBeRemovedRequest != null) {
            spooler.removeMessageById(messageId);
            int messageSize = toBeRemovedRequest.getPayload().length;
            curMessageQueueSizeInBytes.getAndAdd(-1 * messageSize);
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
            long idToBeRemoved = messageIdIterator.next();
            if (getMessageById(idToBeRemoved).getQos().getValue() == 0) {
                removeMessageById(idToBeRemoved);
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
}


