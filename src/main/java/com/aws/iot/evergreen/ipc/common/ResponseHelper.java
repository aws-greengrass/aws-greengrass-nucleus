/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.ipc.common;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

import java.io.IOException;

public class ResponseHelper {
    /**
     * Send a message in response over the channel.
     *
     * @param msg message to be sent
     * @param sequenceNumber sequence number to respond with
     * @param destination destination of the response
     * @param ctx netty channel context used to send the response
     * @param closeWhenDone true if the channel should be shutdown
     * @throws IOException if anything goes wrong
     */
    public static void sendResponse(FrameReader.Message msg, int sequenceNumber, String destination,
                                       ChannelHandlerContext ctx, boolean closeWhenDone) throws IOException {
        // TODO: Validate frame data length

        FrameReader.MessageFrame response =
                new FrameReader.MessageFrame(sequenceNumber, destination, msg, FrameReader.FrameType.RESPONSE);
        ChannelFuture future = ctx.writeAndFlush(response);
        if (closeWhenDone) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }
}
