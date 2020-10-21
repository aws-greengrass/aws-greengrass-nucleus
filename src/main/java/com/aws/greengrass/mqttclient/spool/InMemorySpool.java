package com.aws.greengrass.mqttclient.spool;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemorySpool implements CloudMessageSpool {

    private final Map<Long, SpoolMessage> messages = new ConcurrentHashMap<>();

    @Override
    public SpoolMessage getMessageById(Long messageId) {
        return messages.get(messageId);
    }

    @Override
    public void removeMessageById(Long messageId) {
        if (messages.get(messageId) != null) {
            messages.remove(messageId);
        }
    }

    @Override
    public void add(Long id, SpoolMessage message) {
        messages.put(id, message);
    }

}
