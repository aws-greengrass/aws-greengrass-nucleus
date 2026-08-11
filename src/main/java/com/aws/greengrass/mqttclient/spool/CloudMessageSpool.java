/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient.spool;

import java.io.IOException;
import java.util.List;

public interface CloudMessageSpool {

    SpoolMessage getMessageById(long id);

    void removeMessageById(long id);

    void add(long id, SpoolMessage message) throws IOException;

    Iterable<Long> getAllMessageIds() throws IOException;

    /**
     * Get the maximum message ID currently stored in the spool.
     * This is used to set the next ID for new messages without iterating all messages.
     *
     * <p>Default returns -1 (not supported) for backward compatibility with older plugin versions.
     * When -1 is returned, the nucleus falls back to iterating all message IDs.</p>
     *
     * @return the maximum message ID, or -1 if the spool is empty or not supported
     * @throws IOException if an I/O error occurs
     */
    default long getMaxMessageId() throws IOException {
        return -1;
    }

    /**
     * Get all message IDs with their payload sizes, ordered by ID ascending.
     * Returns a list of [messageId, payloadSizeInBytes] pairs without reading full payloads.
     * This enables fast capacity tracking during startup without per-message I/O.
     *
     * <p>Default returns null (not supported) for backward compatibility with older plugin versions.
     * When null is returned, the nucleus falls back to reading each message individually.</p>
     *
     * @return ordered list of (id, size) pairs, or null if not supported
     * @throws IOException if an I/O error occurs
     */
    default List<long[]> getAllMessageIdsWithSizes() throws IOException {
        return null;
    }

    void initializeSpooler() throws IOException;
}
