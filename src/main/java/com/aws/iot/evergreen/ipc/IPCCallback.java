/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.ipc;

import com.aws.iot.evergreen.ipc.common.FrameReader;
import com.aws.iot.evergreen.ipc.common.RequestContext;

import java.util.concurrent.Future;

/**
 * Interface for IPC message handlers
 */
@FunctionalInterface
public interface IPCCallback {
    Future<FrameReader.Message> onMessage(FrameReader.Message m, RequestContext ctx) throws Throwable;
}
