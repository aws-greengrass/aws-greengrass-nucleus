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
     * Get the metadata (id, payload size and QoS) of all persisted messages, ordered by id ascending, without
     * reading the full payloads. This enables fast capacity tracking during startup without per-message I/O.
     *
     * <p>Default returns null (not supported) for backward compatibility with older plugin versions.
     * When null is returned, the nucleus falls back to {@link #getAllMessageIds()} and reads each message
     * individually with {@link #getMessageById(long)}.</p>
     *
     * <p>Implementations must return one non-null element per persisted message, ordered by id ascending. The
     * nucleus relies on this order to dispatch messages oldest first and reads the highest persisted id from the
     * last element, so that new messages never reuse a persisted id. If the last element is null, the nucleus
     * does not trust the metadata and uses the per-message fallback instead.</p>
     *
     * @return list of message metadata ordered by id ascending, or null if not supported
     * @throws IOException if an I/O error occurs
     */
    default List<SpoolMessageMetadata> getAllMessageMetadata() throws IOException {
        return null;
    }

    void initializeSpooler() throws IOException;
}
