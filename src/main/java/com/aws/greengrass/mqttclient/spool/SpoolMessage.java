package com.aws.greengrass.mqttclient.spool;

import com.aws.greengrass.mqttclient.PublishRequest;
import lombok.Builder;
import lombok.Getter;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.validation.constraints.NotNull;

@Getter
@Builder
public class SpoolMessage {
    @NotNull
    public  PublishRequest publishRequest;
    @Builder.Default
    public AtomicBoolean inService = new AtomicBoolean(false);
    @Builder.Default
    public AtomicInteger retried = new AtomicInteger(0);

    public SpoolMessage(PublishRequest request) {
        publishRequest = request;
    }
}
