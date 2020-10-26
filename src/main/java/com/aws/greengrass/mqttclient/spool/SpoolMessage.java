package com.aws.greengrass.mqttclient.spool;

import com.aws.greengrass.mqttclient.PublishRequest;
import lombok.Builder;
import lombok.Getter;

import java.util.concurrent.atomic.AtomicInteger;
import lombok.NonNull;
import lombok.Value;

@Builder
@Value
@Getter
public class SpoolMessage {
    @NonNull
    public   PublishRequest publishRequest;
    @Builder.Default
    public AtomicInteger retried = new AtomicInteger(0);
}
