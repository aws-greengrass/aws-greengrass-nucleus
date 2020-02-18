/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.ipc;

import com.aws.iot.evergreen.ipc.common.BuiltInServiceDestinationCode;
import com.aws.iot.evergreen.ipc.common.FrameReader;
import com.aws.iot.evergreen.ipc.common.GenericErrorCodes;
import com.aws.iot.evergreen.ipc.services.common.GeneralResponse;
import com.aws.iot.evergreen.ipc.services.common.IPCUtil;
import com.aws.iot.evergreen.util.Log;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import javax.inject.Inject;

import static com.aws.iot.evergreen.ipc.common.ResponseHelper.sendResponse;
import static com.aws.iot.evergreen.util.Utils.getUltimateMessage;

/**
 * Handles all incoming messages to the IPC server and authorizes, then appropriately
 * routes the message to the correct handler.
 */
@ChannelHandler.Sharable
@AllArgsConstructor
@NoArgsConstructor
public class IPCChannelHandler extends ChannelInboundHandlerAdapter {
    public static final AttributeKey<ConnectionContext> CONNECTION_CONTEXT_KEY = AttributeKey.newInstance("ctx");
    private static final String DEST_NOT_FOUND_ERROR = "Destination handler not found";
    private static final String UNSUPPORTED_PROTOCOL_VERSION = "Unsupported protocol version";

    @Inject
    private Log log;

    @Inject
    private AuthHandler auth;

    @Inject
    private IPCRouter router;

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        ctx.channel().attr(CONNECTION_CONTEXT_KEY).set(null);
        super.channelRegistered(ctx);
        // TODO: Possibly have timeout to drop connection if it stays unauthenticated.
        // https://issues.amazon.com/issues/P32808886
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        super.channelUnregistered(ctx);
        ConnectionContext context = ctx.channel().attr(CONNECTION_CONTEXT_KEY).get();
        if (context != null) {
            context.clientDisconnected();
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        final FrameReader.MessageFrame message = (FrameReader.MessageFrame) msg;

        // Match up the version, if it doesn't match then return an error and close the connection
        if (message.version != FrameReader.VERSION) {
            log.warn(UNSUPPORTED_PROTOCOL_VERSION, ctx.channel().remoteAddress());
            sendResponse(new FrameReader.Message(UNSUPPORTED_PROTOCOL_VERSION.getBytes(StandardCharsets.UTF_8)),
                    message.requestId, BuiltInServiceDestinationCode.ERROR.getValue(), ctx, true);
            return;
        }

        // When there isn't context yet, we expect a call to be authorized first
        if (ctx.channel().attr(CONNECTION_CONTEXT_KEY).get() == null) {
            auth.handleAuth(ctx, message);
            return;
        }

        if (FrameReader.FrameType.RESPONSE.equals(message.type)) {
            router.handleResponseMessage(ctx.channel().attr(CONNECTION_CONTEXT_KEY).get(), message);
            return;
        }

        // Get the callback for a destination. If it doesn't exist, then send back an error message.
        IPCCallback cb = router.getCallbackForDestination(message.destination);
        if (cb == null) {
            log.warn(DEST_NOT_FOUND_ERROR, ctx.channel().remoteAddress(), message.destination);
            sendResponse(new FrameReader.Message(DEST_NOT_FOUND_ERROR.getBytes(StandardCharsets.UTF_8)),
                    message.requestId, BuiltInServiceDestinationCode.ERROR.getValue(), ctx, false);
            return;
        }

        try {
            // TODO: Be smart about timeouts? https://issues.amazon.com/issues/86453f7c-c94e-4a3c-b8ff-679767e7443c
            FrameReader.Message responseMessage =
                    cb.onMessage(message.message, ctx.channel().attr(CONNECTION_CONTEXT_KEY).get())
                            // This .get() blocks forever waiting for the response to the request
                            .get();
            sendResponse(responseMessage, message.requestId, message.destination, ctx, false);
        } catch (Throwable throwable) {
            // This is just a catch-all. Any service specific errors should be handled by the service code.
            // Ideally this never gets executed.
            sendResponse(new FrameReader.Message(IPCUtil.encode(
                    GeneralResponse.builder().error(GenericErrorCodes.InternalError)
                            .errorMessage(getUltimateMessage(throwable)).build())), message.requestId,
                    message.destination, ctx, false);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        // TODO: Proper exception handling https://issues.amazon.com/issues/P32787597
        log.warn("Caught error in IPC server", cause);
        // Close out the connection since we don't know what went wrong.
        ctx.close();
    }
}
