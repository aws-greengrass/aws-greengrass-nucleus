/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.ipc.codec;

import com.aws.iot.evergreen.ipc.common.FrameReader;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ReplayingDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class MessageFrameDecoder extends ReplayingDecoder<Void> {
    private static final int BYTE_MASK = 0xff;
    private static final int IS_RESPONSE_MASK = 0x01;
    public static final int SEQ_NUM_AND_PAYLOAD_LENGTH_LENGTH = 6;
    public static final int VERSION_AND_DEST_LENGTH_LENGTH = 2;

    /**
     * Decode the input message.
     * Message format is
     *
     * +------------------+----------------------+---------------+---------------+------------------+------------+
     * | Version + Type   |  Destination Length  |  Destination  |  Seq. Number  |  Payload Length  |  Payload   |
     * |     1 byte       |         1 byte       |    x bytes    |    4 bytes    |      2 bytes     |  y bytes   |
     * +------------------+----------------------+---------------+---------------+------------------+------------+
     *
     * @param channelHandlerContext
     * @param byteBuf
     * @param list
     * @throws Exception
     */
    @Override
    protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf, List<Object> list) throws Exception {
        byteBuf.markReaderIndex();

        if (actualReadableBytes() < VERSION_AND_DEST_LENGTH_LENGTH) {
            byteBuf.resetReaderIndex();
            return;
        }

        int firstByte = ((int) byteBuf.readByte()) & BYTE_MASK;
        int version = firstByte >> 1;
        FrameReader.FrameType type = FrameReader.FrameType.fromOrdinal(firstByte & IS_RESPONSE_MASK);

        int destinationNameLength = byteBuf.readByte();

        if (actualReadableBytes() < destinationNameLength) {
            byteBuf.resetReaderIndex();
            return;
        }

        byte[] destinationNameByte = new byte[destinationNameLength];
        byteBuf.readBytes(destinationNameByte);

        if (actualReadableBytes() < SEQ_NUM_AND_PAYLOAD_LENGTH_LENGTH) {
            byteBuf.resetReaderIndex();
            return;
        }

        int sequenceNumber = byteBuf.readInt();
        int payloadLength = byteBuf.readShort();

        if (actualReadableBytes() < payloadLength) {
            byteBuf.resetReaderIndex();
            return;
        }

        byte[] payload = new byte[payloadLength];
        byteBuf.readBytes(payload);

        list.add(new FrameReader.MessageFrame(sequenceNumber, version,
                new String(destinationNameByte, StandardCharsets.UTF_8), new FrameReader.Message(payload),
                type));
    }
}
