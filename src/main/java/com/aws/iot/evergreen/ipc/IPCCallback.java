/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.ipc;

import com.aws.iot.evergreen.ipc.common.FrameReader;

import java.util.concurrent.Future;

/**
 * Interface for IPC message handlers.
 */
@FunctionalInterface
public interface IPCCallback {
    /**
     * Callback used to receive messages from a client and then send a response back.
     *
     * @param m   incoming message
     * @param ctx request context
     * @return future containing the response message
     * @throws Throwable if anything goes wrong
     */
    Future<FrameReader.Message> onMessage(FrameReader.Message m, ConnectionContext ctx) throws Throwable;
}
