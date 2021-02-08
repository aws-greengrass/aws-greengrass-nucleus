/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient.spool;

import com.aws.greengrass.mqttclient.PublishRequest;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.atomic.AtomicInteger;

@Builder
@Getter
public class SpoolMessage {
    private long id;
    @Builder.Default @Setter
    private AtomicInteger retried = new AtomicInteger(0);
    private PublishRequest request;
}
