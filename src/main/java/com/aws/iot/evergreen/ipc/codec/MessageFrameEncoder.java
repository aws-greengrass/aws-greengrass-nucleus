/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.ipc.codec;

import com.aws.iot.evergreen.ipc.common.FrameReader;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.io.DataOutputStream;

public class MessageFrameEncoder extends MessageToByteEncoder<FrameReader.MessageFrame> {
    @Override
    protected void encode(ChannelHandlerContext channelHandlerContext, FrameReader.MessageFrame messageFrame, ByteBuf byteBuf) throws Exception {
        FrameReader.writeFrame(messageFrame, new DataOutputStream(new ByteBufOutputStream(byteBuf)));
    }
}
