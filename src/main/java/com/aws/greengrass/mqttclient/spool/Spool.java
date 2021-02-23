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

    private static final String GG_SPOOL_STORAGE_TYPE_KEY = "storageType";
    private static final String GG_SPOOL_MAX_SIZE_IN_BYTES_KEY = "maxSizeInBytes";
    private static final String GG_SPOOL_KEEP_QOS_0_WHEN_OFFLINE_KEY = "keepQos0WhenOffline";

    private static final boolean DEFAULT_KEEP_Q0S_0_WHEN_OFFLINE = false;
    private static final SpoolerStorageType DEFAULT_GG_SPOOL_STORAGE_TYPE = SpoolerStorageType.Memory;
    private static final int DEFAULT_GG_SPOOL_MAX_MESSAGE_QUEUE_SIZE_IN_BYTES = (int)(2.5 * 1024 * 1024); // 2.5MB

    private final AtomicLong nextId = new AtomicLong(0);
    private SpoolerConfig config;
    private final BlockingDeque<Long> queueOfMessageId = new LinkedBlockingDeque<>();
    private final AtomicLong curMessageQueueSizeInBytes = new AtomicLong(0);


    /**
     * Constructor.
     * @param deviceConfiguration the device configuration
     * @throws InterruptedException if interrupted
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
                        GG_SPOOL_MAX_SIZE_IN_BYTES_KEY));
        boolean ggSpoolKeepQos0WhenOffline = Coerce.toBoolean(topics
                .findOrDefault(DEFAULT_KEEP_Q0S_0_WHEN_OFFLINE, GG_SPOOL_KEEP_QOS_0_WHEN_OFFLINE_KEY));

        logger.atInfo().kv(GG_SPOOL_STORAGE_TYPE_KEY, ggSpoolStorageType)
                .kv(GG_SPOOL_MAX_SIZE_IN_BYTES_KEY, ggSpoolMaxMessageQueueSizeInBytes)
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
        // Only in memory spool is supported
        return null;
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
     * @throws InterruptedException result from the queue implementation
     * @throws SpoolerStoreException  if the message cannot be inserted into the message spool
     */
    public synchronized SpoolMessage addMessage(PublishRequest request) throws InterruptedException,
            SpoolerStoreException {
        int messageSizeInBytes = request.getPayload().length;
        if (messageSizeInBytes > getSpoolConfig().getSpoolSizeInBytes()) {
            throw new SpoolerStoreException("Message is larger than the size of message spool.");
        }

        curMessageQueueSizeInBytes.getAndAdd(messageSizeInBytes);
        if (curMessageQueueSizeInBytes.get() > getSpoolConfig().getSpoolSizeInBytes()) {
            removeOldestMessage();
        }

        if (curMessageQueueSizeInBytes.get() > getSpoolConfig().getSpoolSizeInBytes()) {
            curMessageQueueSizeInBytes.getAndAdd(-1 * messageSizeInBytes);
            throw new SpoolerStoreException("Message spool is full. Message could not be added.");
        }

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
     * @param messageId  message id
     */
    public void removeMessageById(long messageId) {
        SpoolMessage toBeRemovedMessage = getMessageById(messageId);
        if (toBeRemovedMessage != null) {
            spooler.removeMessageById(messageId);
            int messageSize = toBeRemovedMessage.getRequest().getPayload().length;
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
            long id = messageIdIterator.next();
            PublishRequest request = getMessageById(id).getRequest();
            int qos = request.getQos().getValue();
            if (qos == 0) {
                removeMessageById(id);
                logger.atDebug().kv("id", id).kv("topic", request.getTopic()).kv("Qos", qos)
                        .log("The spooler is configured to drop QoS 0 when offline. "
                                + "Dropping message now.");
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


