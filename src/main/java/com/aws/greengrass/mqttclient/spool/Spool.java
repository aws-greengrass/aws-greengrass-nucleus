package com.aws.greengrass.mqttclient.spool;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.mqttclient.PublishRequest;
import com.aws.greengrass.util.Coerce;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;


public class Spool {

    private static final Logger logger = LogManager.getLogger(Spool.class);
    private CloudMessageSpool spooler;
    private static final String GG_SPOOL_STORAGE_TYPE_KEY = "spoolStorageType";
    private static final String GG_SPOOL_MAX_MESSAGE_QUEUE_SIZE_IN_BYTES_KEY = "spoolMaxMessageQueueSizeInBytes";
    private static final String GG_SPOOL_KEPP_QOS_0_WHEN_OFFLINE_KEY = "keepQos0WhenOffline";
    private static final String GG_SPOOL_MAX_RETRIED_LEY = "maxRetried";

    private static final boolean DEFAULT_KEEP_Q0S_0_WHEN_OFFINE = false;
    private static final SpoolerStorageType DEFAULT_GG_SPOOL_STORAGE_TYPE
            = SpoolerStorageType.memory;
    private static final int DEFAULT_GG_SPOOL_MAX_MESSAGE_QUEUE_SIZE_IN_BYTES = (int)(2.5 * 1024 * 1024); // 2.5MB
    private static final int DEFAULT_GG_SPOOL_MAX_RETRIED = 3;

    private final AtomicLong nextId = new AtomicLong(0);
    private final SpoolerConfig config;
    private final AtomicLong curMessageCount = new AtomicLong(0);

    private final BlockingDeque<Long> messageQueueOfQos0 = new LinkedBlockingDeque<>();
    private final BlockingDeque<Long> messageQueueOfQos1And2 = new LinkedBlockingDeque<>();

    private AtomicLong curMessageQueueSizeInBytes = new AtomicLong(0);

    public Spool(Topics mqttTopics) {
        config = setSpoolerConfig(mqttTopics);
        spooler = setupSpooler(config);
    }

    private SpoolerConfig setSpoolerConfig(Topics mqttTopics) {
        SpoolerStorageType ggSpoolStorageType = Coerce.toEnum(SpoolerStorageType.class, mqttTopics
                .findOrDefault(DEFAULT_GG_SPOOL_STORAGE_TYPE, GG_SPOOL_STORAGE_TYPE_KEY));
        Long ggSpoolMaxMessageQueueSizeInBytes = Coerce.toLong(mqttTopics
                .findOrDefault(DEFAULT_GG_SPOOL_MAX_MESSAGE_QUEUE_SIZE_IN_BYTES,
                        GG_SPOOL_MAX_MESSAGE_QUEUE_SIZE_IN_BYTES_KEY));
        boolean ggSpoolKeepQos0WhenOffine = Coerce.toBoolean(mqttTopics
                .findOrDefault(DEFAULT_KEEP_Q0S_0_WHEN_OFFINE, GG_SPOOL_KEPP_QOS_0_WHEN_OFFLINE_KEY));
        int ggSpoolMaxRetried = Coerce.toInt(mqttTopics
                .findOrDefault(DEFAULT_GG_SPOOL_MAX_RETRIED, GG_SPOOL_MAX_RETRIED_LEY));

        return new SpoolerConfig(ggSpoolStorageType, ggSpoolMaxMessageQueueSizeInBytes,
                ggSpoolKeepQos0WhenOffine, ggSpoolMaxRetried);
    }

    private CloudMessageSpool setupSpooler(SpoolerConfig config) {
        if (config.getSpoolStorageType() == SpoolerStorageType.memory) {
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
    public void addId(Long id) {
        int qos = getMessageById(id).getPublishRequest().getQos().getValue();
        if (qos == 0) {
            messageQueueOfQos0.addFirst(id);
        } else {
            messageQueueOfQos1And2.addFirst(id);
        }
    }

    /**
     * Add the message to both of the Queue of Id and the spooler.
     * Drop the oldest message if the current queue size is greater than the settings.
     *
     * @param request publish request
     * @throws InterruptedException result from the queue implementation
     */
    public Long addMessage(PublishRequest request) throws InterruptedException {
        Long id = nextId.getAndIncrement();

        SpoolMessage message = new SpoolMessage(request);
        int qos = request.getQos().getValue();
        int messageSizeInBytes = request.getPayload().length;

        while (messageSizeInBytes + curMessageQueueSizeInBytes.get() > maxSpoolerSizeInBytes()) {
            Long toBeRemovedID = popId();
            removeMessageById(toBeRemovedID);
            logger.atInfo().log("Spooler Queue is Full. Will remove the oldest unsent message");
        }
        if (qos == 0) {
            messageQueueOfQos0.putLast(id);
        } else {
            messageQueueOfQos1And2.putLast(id);
        }
        spooler.add(id, message);
        curMessageQueueSizeInBytes.getAndAdd(messageSizeInBytes);
        curMessageCount.getAndIncrement();

        return id;
    }

    public Long maxSpoolerSizeInBytes() {
        return config.getSpoolMaxMessageQueueSizeInBytes();
    }

    private List<Long> getHeadOfAllTheQueues() {
        Long messageIdFromQueueOfQos0 = null;
        Long messageIdFromQueueOfQos1And2 = null;

        if (!messageQueueOfQos0.isEmpty()) {
            messageIdFromQueueOfQos0 = messageQueueOfQos0.getFirst();
        }
        if (!messageQueueOfQos1And2.isEmpty()) {
            messageIdFromQueueOfQos1And2 = messageQueueOfQos1And2.getFirst();
        }
        return Arrays.asList(messageIdFromQueueOfQos0, messageIdFromQueueOfQos1And2);
    }

    private Long peekId() {
        if (messageCount() == 0) {
            return null;
        }
        Long smallestMessageId = null;
        List<Long> headsOfQueues = getHeadOfAllTheQueues();
        Long messageIdFromQueueOfQos0 = headsOfQueues.get(0);
        Long messageIdFromQueueOfQos1And2 = headsOfQueues.get(1);

        if (messageIdFromQueueOfQos0 != null && messageIdFromQueueOfQos1And2 != null) {
            if (messageIdFromQueueOfQos0 < messageIdFromQueueOfQos1And2) {
                smallestMessageId = messageIdFromQueueOfQos0;
            } else {
                smallestMessageId = messageIdFromQueueOfQos1And2;
            }
        } else if (messageIdFromQueueOfQos0 != null) {
            smallestMessageId = messageIdFromQueueOfQos0;
        } else if (messageIdFromQueueOfQos1And2 != null) {
            smallestMessageId = messageIdFromQueueOfQos1And2;
        }
        return smallestMessageId;
    }

    public SpoolMessage getMessageById(Long messageId) {
        return spooler.getMessageById(messageId);
    }

    /**
     * Remove the Message from the spooler based on the MessageId.
     *
     * @param messageId  message id
     */
    public void removeMessageById(Long messageId) {
        SpoolMessage toBeRemovedMessage = getMessageById(messageId);
        if (toBeRemovedMessage != null) {
            spooler.removeMessageById(messageId);
            int messageSize = toBeRemovedMessage.getPublishRequest().getPayload().length;
            curMessageQueueSizeInBytes.getAndAdd(-1 * messageSize);
        }
    }

    /**
     * Pop out the id of the oldest message.
     * 
     * @return message id
     */
    public Long popId() {
        // if both of the queues are empty, do nothing
        if (curMessageCount.get() == 0) {
            return null;
        }

        List<Long> headsOfQueues = getHeadOfAllTheQueues();
        Long messageIdFromQueueOfQos0 = headsOfQueues.get(0);
        Long messageIdFromQueueOfQos1And2 = headsOfQueues.get(1);
        Long removedMessageId = null;

        if (messageIdFromQueueOfQos0 != null && messageIdFromQueueOfQos1And2 != null) {
            if (messageIdFromQueueOfQos0 < messageIdFromQueueOfQos1And2) {
                removedMessageId = messageIdFromQueueOfQos0;
            } else {
                removedMessageId = messageIdFromQueueOfQos1And2;
            }
        } else if (messageIdFromQueueOfQos0 != null) {
            removedMessageId = messageIdFromQueueOfQos0;
        } else if (messageIdFromQueueOfQos1And2 != null) {
            removedMessageId = messageIdFromQueueOfQos1And2;
        }
        curMessageCount.getAndDecrement();
        return removedMessageId;
    }

    public Long messageCount() {
        return curMessageCount.get();
    }

    public SpoolerConfig getSpoolConfig() {
        return config;
    }

}

