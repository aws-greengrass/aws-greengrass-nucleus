package com.aws.greengrass.mqttclient.spool;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class SpoolerConfig {
    private SpoolerStorageType spoolStorageType;
    private Long spoolMaxMessageQueueSizeInBytes;
    public boolean keepQos0WhenOffline;
}
