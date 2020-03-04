/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.ipc.common;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

import java.io.IOException;

public class ResponseHelper {
    /**
     * Send a message in response over the channel.
     *
     * @param msg           message to be sent
     * @param requestId     request id to respond with
     * @param destination   destination of the response
     * @param ctx           netty channel context used to send the response
     * @param closeWhenDone true if the channel should be shutdown
     * @throws IOException if anything goes wrong
     */
    public static void sendResponse(FrameReader.Message msg, int requestId, int destination, ChannelHandlerContext ctx,
                                    boolean closeWhenDone) throws IOException {
        // TODO: Validate frame data length

        FrameReader.MessageFrame response =
                new FrameReader.MessageFrame(requestId, destination, msg, FrameReader.FrameType.RESPONSE);
        ChannelFuture future = ctx.writeAndFlush(response);
        if (closeWhenDone) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }
}
