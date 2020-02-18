package com.aws.iot.evergreen.ipc;

import com.aws.iot.evergreen.ipc.common.FrameReader;

import java.util.concurrent.Future;

@FunctionalInterface
public interface ConnectionHandle {
    Future<FrameReader.Message> sendAndReceive(int destination, FrameReader.Message message);
}
