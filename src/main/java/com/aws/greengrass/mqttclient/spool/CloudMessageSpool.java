package com.aws.greengrass.mqttclient.spool;

public interface CloudMessageSpool {

    SpoolMessage getMessageById(Long smallestMessageId);

    void removeMessageById(Long messageId);

    void add(Long id, SpoolMessage message);

    SpoolerStorageType getSpoolerStorageType();
}
