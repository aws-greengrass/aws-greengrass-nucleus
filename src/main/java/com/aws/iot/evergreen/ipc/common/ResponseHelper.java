/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.ipc.common;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

import java.io.IOException;

public class ResponseHelper {
    public static boolean sendResponse(FrameReader.Message msg,
                                       int sequenceNumber,
                                       String destination,
                                       ChannelHandlerContext ctx,
                                       boolean closeWhenDone) throws IOException {
        // TODO: Validate frame data length

        FrameReader.MessageFrame response = new FrameReader.MessageFrame(sequenceNumber, destination,
                msg, FrameReader.FrameType.RESPONSE);
        ChannelFuture future = ctx.writeAndFlush(response);
        if (closeWhenDone) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
        return true;
    }
}
