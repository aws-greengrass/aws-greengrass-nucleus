package com.aws.greengrass.mqttclient.spool;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.mqttclient.PublishRequest;
import com.aws.greengrass.util.Coerce;

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
    private static final String GG_SPOOL_KEPP_QOS_0_WHEN_OFFLINE_KEY = "keepQos0WhenOffline";
    private static final String GG_SPOOL_MAX_RETRIED_LEY = "maxRetried";

    private static final boolean DEFAULT_KEEP_Q0S_0_WHEN_OFFLINE = false;
    private static final SpoolerStorageType DEFAULT_GG_SPOOL_STORAGE_TYPE
            = SpoolerStorageType.Memory;
    private static final int DEFAULT_GG_SPOOL_MAX_MESSAGE_QUEUE_SIZE_IN_BYTES = (int)(2.5 * 1024 * 1024); // 2.5MB
    private static final int DEFAULT_GG_SPOOL_MAX_RETRIED = 3;

    private final AtomicLong nextId = new AtomicLong(0);
    private final SpoolerConfig config;
    private final AtomicLong curMessageCount = new AtomicLong(0);

    private final BlockingDeque<Long> messageQueueOfQos0;
    private final BlockingDeque<Long> messageQueueOfQos1And2;

    private final AtomicLong curMessageQueueSizeInBytes = new AtomicLong(0);

    /**
     * The constructor of Spool.
     * @param deviceConfiguration the device configuration
     * @throws InterruptedException if aaa else bbb
     *
     */
    public Spool(DeviceConfiguration deviceConfiguration) {
        messageQueueOfQos0 = new LinkedBlockingDeque<>();
        messageQueueOfQos1And2 = new LinkedBlockingDeque<>();
        this.deviceConfiguration = deviceConfiguration;
        Topics spoolerTopics = this.deviceConfiguration.getSpoolerNamespace();
        config = setSpoolerConfig(spoolerTopics);
        spooler = setupSpooler(config);

        // subscribe the changes on the configuration of Spooler
        this.deviceConfiguration.onAnyChange((what, node) -> {
            if (WhatHappened.childChanged.equals(what) && node != null) {
                // List of configuration nodes that we need to reconfigure for if they change

                if (!(node.childOf(DEVICE_SPOOLER_NAMESPACE) || node.childOf(DEVICE_PARAM_THING_NAME) || node
                        .childOf(DEVICE_PARAM_IOT_DATA_ENDPOINT) || node.childOf(DEVICE_PARAM_PRIVATE_KEY_PATH) || node
                        .childOf(DEVICE_PARAM_CERTIFICATE_FILE_PATH) || node.childOf(DEVICE_PARAM_ROOT_CA_PATH))) {
                    return;
                }

                logger.atDebug().log("the spooler has been re-configured");

                // re-set the spoolerConfig
                setSpoolerConfig(this.deviceConfiguration.getSpoolerNamespace());
                // remove the oldest message if the spooler queue should be truncated
                while (curMessageQueueSizeInBytes.get() > maxSpoolerSizeInBytes()) {
                    Long toBeRemovedID = popId();
                    removeMessageById(toBeRemovedID);
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
    public Spool(DeviceConfiguration deviceConfiguration, SpoolerConfig config,
                 BlockingDeque<Long> messageQueueOfQos0, BlockingDeque<Long> messageQueueOfQos1And2) {
        this.deviceConfiguration = deviceConfiguration;
        this.config = config;
        this.messageQueueOfQos0 = messageQueueOfQos0;
        this.messageQueueOfQos1And2 = messageQueueOfQos1And2;
        spooler = setupSpooler(this.config);
    }

    private SpoolerConfig setSpoolerConfig(Topics spoolerTopics) {
        SpoolerStorageType ggSpoolStorageType = Coerce.toEnum(SpoolerStorageType.class, spoolerTopics
                .findOrDefault(DEFAULT_GG_SPOOL_STORAGE_TYPE, GG_SPOOL_STORAGE_TYPE_KEY));
        Long ggSpoolMaxMessageQueueSizeInBytes = Coerce.toLong(spoolerTopics
                .findOrDefault(DEFAULT_GG_SPOOL_MAX_MESSAGE_QUEUE_SIZE_IN_BYTES,
                        GG_SPOOL_MAX_MESSAGE_QUEUE_SIZE_IN_BYTES_KEY));
        boolean ggSpoolKeepQos0WhenOffine = Coerce.toBoolean(spoolerTopics
                .findOrDefault(DEFAULT_KEEP_Q0S_0_WHEN_OFFLINE, GG_SPOOL_KEPP_QOS_0_WHEN_OFFLINE_KEY));
        int ggSpoolMaxRetried = Coerce.toInt(spoolerTopics
                .findOrDefault(DEFAULT_GG_SPOOL_MAX_RETRIED, GG_SPOOL_MAX_RETRIED_LEY));

        return new SpoolerConfig(ggSpoolStorageType, ggSpoolMaxMessageQueueSizeInBytes,
                ggSpoolKeepQos0WhenOffine, ggSpoolMaxRetried);
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
    public synchronized Long addMessage(PublishRequest request) throws InterruptedException {
        Long id = nextId.getAndIncrement();
        int messageSizeInBytes = request.getPayload().length;
        curMessageQueueSizeInBytes.getAndAdd(messageSizeInBytes);
        curMessageCount.getAndIncrement();

        System.out.println("messageSizeInBytes: " + messageSizeInBytes);
        System.out.println("curMessageQueueSizeInBytes: " + curMessageQueueSizeInBytes.get());
        System.out.println("curMessageCount: " + curMessageCount.get());

        while (curMessageQueueSizeInBytes.get() > maxSpoolerSizeInBytes()) {
            System.out.println("to removed oldest message");
            // TODO: conner case: if curMessageQueueSizeInBytes > maxSpoolerSizeInBytes();
            // Do we need to drop the message? Add the test if has been determined
            removeOldestMessage();
            logger.atInfo().log("spooler queue is full and will remove the oldest unsent message");
        }

        int qos = request.getQos().getValue();
        if (qos == 0) {
            messageQueueOfQos0.putLast(id);
        } else {
            messageQueueOfQos1And2.putLast(id);
        }

        SpoolMessage message = SpoolMessage.builder().publishRequest(request).build();
        addMessageToSpooler(id, message);

        return id;
    }

    public void addMessageToSpooler(Long id, SpoolMessage message) {
        spooler.add(id, message);
    }

    public Long maxSpoolerSizeInBytes() {
        return config.getSpoolMaxMessageQueueSizeInBytes();
    }

    public BlockingDeque<Long> getQueueWithSmallestMessageId() {
        Long messageIdFromQueueOfQos0 = null;
        Long messageIdFromQueueOfQos1And2 = null;

        if (!messageQueueOfQos0.isEmpty()) {
            messageIdFromQueueOfQos0 = messageQueueOfQos0.getFirst();
        }
        if (!messageQueueOfQos1And2.isEmpty()) {
            messageIdFromQueueOfQos1And2 = messageQueueOfQos1And2.getFirst();
        }

        if (messageIdFromQueueOfQos0 != null && messageIdFromQueueOfQos1And2 != null) {
            if (messageIdFromQueueOfQos0 < messageIdFromQueueOfQos1And2) {
                return messageQueueOfQos0;
            } else {
                return messageQueueOfQos1And2;
            }
        } else if (messageIdFromQueueOfQos0 != null) {
            return messageQueueOfQos0;
        } else if (messageIdFromQueueOfQos1And2 != null) {
            return messageQueueOfQos1And2;
        }
        return null;
    }

    public Long peekId() {
        if (messageCount() == 0) {
            return null;
        }
        BlockingDeque<Long> messageQueueWithSmallestMessageId = getQueueWithSmallestMessageId();
        Long smallestMessageId = null;
        if (messageQueueWithSmallestMessageId != null) {
            smallestMessageId = messageQueueWithSmallestMessageId.getFirst();
        }
        return smallestMessageId;
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

        BlockingDeque<Long> messageQueueWithSmallestMessageId = getQueueWithSmallestMessageId();
        Long removedMessageId = null;
        if (messageQueueWithSmallestMessageId != null) {
            try {
                removedMessageId = messageQueueWithSmallestMessageId.takeFirst();
            } catch (InterruptedException e) {
                logger.atError().log("failed to pop out the message Id from the spooler queue");
            }
        }
        curMessageCount.getAndDecrement();
        return removedMessageId;
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
        System.out.println("**** removeMessageById ****");
        SpoolMessage toBeRemovedMessage = getMessageById(messageId);
        if (toBeRemovedMessage != null) {
            spooler.removeMessageById(messageId);
            int messageSize = toBeRemovedMessage.getPublishRequest().getPayload().length;
            curMessageQueueSizeInBytes.getAndAdd(-1 * messageSize);
        }
    }

    public Long removeOldestMessage() throws InterruptedException {
        Long id = null;
        if (!messageQueueOfQos0.isEmpty()) {
            id = messageQueueOfQos0.takeFirst();
            removeMessageById(id);
        } else if (!messageQueueOfQos1And2.isEmpty()) {
            id = messageQueueOfQos1And2.takeFirst();
            removeMessageById(id);
        }
        return id;
    }

    public Long messageCount() {
        return curMessageCount.get();
    }

    public SpoolerConfig getSpoolConfig() {
        return config;
    }

    public void spoolerStorageTypeConverter() {
        return;
    }

    public int getMessageCountWithQos0() {
        return messageQueueOfQos0.size();
    }

    public int getMessageCountWithQos1And2() {
        return messageQueueOfQos1And2.size();
    }

}

