/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient.spool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemorySpool implements CloudMessageSpool {

    private final Map<Long, SpoolMessage> messages = new ConcurrentHashMap<>();

    @Override
    public SpoolMessage getMessageById(long messageId) {
        return messages.get(messageId);
    }

    @Override
    public void removeMessageById(long messageId) {
        messages.remove(messageId);
    }

    @Override
    public void add(long id, SpoolMessage message) {
        messages.put(id, message);
    }

    @Override
    public List<Long> getAllMessageIds() {
        return new ArrayList<>(messages.keySet());
    }

    @Override
    public void initializeSpooler() throws IOException {
    }

}
