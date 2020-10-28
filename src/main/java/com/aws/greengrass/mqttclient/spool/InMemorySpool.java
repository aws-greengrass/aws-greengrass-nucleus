/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient.spool;

import com.aws.greengrass.mqttclient.PublishRequest;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemorySpool implements CloudMessageSpool {

    private final Map<Long, PublishRequest> messages = new ConcurrentHashMap<>();

    @Override
    public PublishRequest getMessageById(Long messageId) {
        return messages.get(messageId);
    }

    @Override
    public void removeMessageById(Long messageId) {
        messages.remove(messageId);
    }

    @Override
    public void add(Long id, PublishRequest request) {
        messages.put(id, request);
    }

    @Override
    public SpoolerStorageType getSpoolerStorageType() {
        return SpoolerStorageType.Memory;
    }

}
