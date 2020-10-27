package com.aws.greengrass.mqttclient.spool;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.mqttclient.PublishRequest;
import com.aws.greengrass.util.Coerce;

import java.util.HashSet;
import java.util.Iterator;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;

import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_CERTIFICATE_FILE_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_IOT_DATA_ENDPOINT;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_PRIVATE_KEY_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_ROOT_CA_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_THING_NAME;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_SPOOLER_NAMESPACE;


public class Spool {

    private static final Logger logger = LogManager.getLogger(Spool.class);
    private final DeviceConfiguration deviceConfiguration;
    private CloudMessageSpool spooler;
    private static final String GG_SPOOL_STORAGE_TYPE_KEY = "spoolStorageType";
    private static final String GG_SPOOL_MAX_MESSAGE_QUEUE_SIZE_IN_BYTES_KEY = "spoolMaxMessageQueueSizeInBytes";
    private static final String GG_SPOOL_KEEP_QOS_0_WHEN_OFFLINE_KEY = "keepQos0WhenOffline";

    private static final boolean DEFAULT_KEEP_Q0S_0_WHEN_OFFLINE = false;
    private static final SpoolerStorageType DEFAULT_GG_SPOOL_STORAGE_TYPE
            = SpoolerStorageType.Memory;
    private static final int DEFAULT_GG_SPOOL_MAX_MESSAGE_QUEUE_SIZE_IN_BYTES = (int)(2.5 * 1024 * 1024); // 2.5MB

    private final AtomicLong nextId = new AtomicLong(0);
    private final SpoolerConfig config;
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
        Topics spoolerTopics = this.deviceConfiguration.getSpoolerNamespace();
        config = setSpoolerConfig(spoolerTopics);
        spooler = setupSpooler(config);

        // subscribe the changes on the configuration of Spooler
        this.deviceConfiguration.onAnyChange((what, node) -> {
            if (WhatHappened.childChanged.equals(what) && node != null) {
                if (!(node.childOf(DEVICE_SPOOLER_NAMESPACE))) {
                    return;
                }

                logger.atDebug().log("the spooler has been re-configured");
                // re-set the spoolerConfig
                setSpoolerConfig(this.deviceConfiguration.getSpoolerNamespace());
                // TODO: does this needed? remove the oldest message if the spooler queue should be truncated
                if (curMessageQueueSizeInBytes.get() > maxSpoolerSizeInBytes() ) {
                    removeOldestMessage();
                    logger.atDebug().log("spooler queue is full and will remove the oldest unsent message");
                }

                // TODO: implement the storage type converter after the file-system Spooler is done
                if (spooler.getSpoolerStorageType() != getSpoolConfig().getSpoolStorageType()) {
                    spoolerStorageTypeConverter();
                }
            }
        });
    }

    // For unit test
    public Spool(DeviceConfiguration deviceConfiguration, SpoolerConfig config) {
        this.deviceConfiguration = deviceConfiguration;
        this.config = config;
        spooler = setupSpooler(this.config);
    }

    private SpoolerConfig setSpoolerConfig(Topics spoolerTopics) {
        SpoolerStorageType ggSpoolStorageType = Coerce.toEnum(SpoolerStorageType.class, spoolerTopics
                .findOrDefault(DEFAULT_GG_SPOOL_STORAGE_TYPE, GG_SPOOL_STORAGE_TYPE_KEY));
        Long ggSpoolMaxMessageQueueSizeInBytes = Coerce.toLong(spoolerTopics
                .findOrDefault(DEFAULT_GG_SPOOL_MAX_MESSAGE_QUEUE_SIZE_IN_BYTES,
                        GG_SPOOL_MAX_MESSAGE_QUEUE_SIZE_IN_BYTES_KEY));
        boolean ggSpoolKeepQos0WhenOffline = Coerce.toBoolean(spoolerTopics
                .findOrDefault(DEFAULT_KEEP_Q0S_0_WHEN_OFFLINE, GG_SPOOL_KEEP_QOS_0_WHEN_OFFLINE_KEY));

        return new SpoolerConfig(ggSpoolStorageType, ggSpoolMaxMessageQueueSizeInBytes, ggSpoolKeepQos0WhenOffline);
    }

    private CloudMessageSpool setupSpooler(SpoolerConfig config) {
        if (config.getSpoolStorageType() == SpoolerStorageType.Memory) {
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
    public void addId(Long id) throws InterruptedException {
        queueOfMessageId.putFirst(id);
    }

    /**
     * Add the message to both of the Queue of Id and the spooler.
     * Drop the oldest message if the current queue size is greater than the settings.
     *
     * @param request publish request
     * @throws InterruptedException result from the queue implementation
     */
    public synchronized Long addMessage(PublishRequest request) throws InterruptedException, SpoolerLoadException {
        Long id = nextId.getAndIncrement();
        int messageSizeInBytes = request.getPayload().length;
        if (messageSizeInBytes > maxSpoolerSizeInBytes()) {
            throw new SpoolerLoadException("the size of message has exceeds the maximum size of spooler.");
        }

        curMessageQueueSizeInBytes.getAndAdd(messageSizeInBytes);
        if (curMessageQueueSizeInBytes.get() > maxSpoolerSizeInBytes()) {
            removeOldestMessage();
        }

        // TODO: do we need to add the removed message back if exception is thrown??
        if (curMessageQueueSizeInBytes.get() > maxSpoolerSizeInBytes()) {
            curMessageQueueSizeInBytes.getAndAdd(-1 * messageSizeInBytes);
            throw new SpoolerLoadException("spooler queue is full and new message would not be added into spooler");
        }

        addMessageToSpooler(id, request);
        queueOfMessageId.putLast(id);

        return id;
    }

    public void addMessageToSpooler(Long id, PublishRequest request) {
        spooler.add(id, request);
    }

    public Long maxSpoolerSizeInBytes() {
        return config.getSpoolMaxMessageQueueSizeInBytes();
    }
    
    /**
     * Pop out the id of the oldest message.
     *
     * @return message id
     */
    public Long popId() throws InterruptedException {
        return queueOfMessageId.takeFirst();
    }

    public PublishRequest getMessageById(Long messageId) {
        return spooler.getMessageById(messageId);
    }

    /**
     * Remove the Message from the spooler based on the MessageId.
     *
     * @param messageId  message id
     */
    public void removeMessageById(Long messageId) {
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
        while(messageIdIterator.hasNext() && addJudgementWithCurrentSpoolerSize(needToCheckCurSpoolerSize)) {
            Long idToBeRemoved = messageIdIterator.next();
            if (getMessageById(idToBeRemoved).getQos().getValue() == 0) {
                removeMessageById(idToBeRemoved);
            }
        }
    }

    private boolean addJudgementWithCurrentSpoolerSize(boolean needToCheckCurSpoolerSize) {
        if (!needToCheckCurSpoolerSize) {
            return true;
        }
        return curMessageQueueSizeInBytes.get() > maxSpoolerSizeInBytes();
    }

    public int messageCount() {
        return queueOfMessageId.size();
    }

    public Long getCurrentSpoolerSize() {
        return curMessageQueueSizeInBytes.get();
    }

    public SpoolerConfig getSpoolConfig() {
        return config;
    }

    private void spoolerStorageTypeConverter() {
        return;
    }
}


