package com.aws.greengrass.mqttclient.spool;

import com.aws.greengrass.mqttclient.PublishRequest;

public interface CloudMessageSpool {

    PublishRequest getMessageById(Long id);

    void removeMessageById(Long id);

    void add(Long id, PublishRequest request);

    SpoolerStorageType getSpoolerStorageType();
}
