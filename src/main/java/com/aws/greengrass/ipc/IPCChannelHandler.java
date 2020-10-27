/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc;

import com.aws.greengrass.ipc.common.BuiltInServiceDestinationCode;
import com.aws.greengrass.ipc.common.FrameReader;
import com.aws.greengrass.ipc.common.GenericErrorCodes;
import com.aws.greengrass.ipc.services.common.GeneralResponse;
import com.aws.greengrass.ipc.services.common.IPCUtil;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import javax.inject.Inject;

import static com.aws.greengrass.ipc.common.ResponseHelper.sendResponse;
import static com.aws.greengrass.util.Utils.getUltimateMessage;

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
    private static final Logger logger = LogManager.getLogger(IPCChannelHandler.class);

    @Inject
    private AuthenticationHandler authenticationHandler;

    @Inject
    private IPCRouter router;

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        ctx.channel().attr(CONNECTION_CONTEXT_KEY).set(null);
        super.channelRegistered(ctx);
        // GG_NEEDS_REVIEW: TODO: Possibly have timeout to drop connection if it stays unauthenticated.
        // https://issues.amazon.com/issues/P32808886
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        super.channelUnregistered(ctx);
        ConnectionContext context = ctx.channel().attr(CONNECTION_CONTEXT_KEY).get();
        if (context != null) {
            logger.atInfo().log("IPC client {} disconnected", context);
            context.clientDisconnected();
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        final FrameReader.MessageFrame message = (FrameReader.MessageFrame) msg;

        // Match up the version, if it doesn't match then return an error and close the connection
        if (message.version != FrameReader.VERSION) {
            logger.atWarn().setEventType("request-version-mismatch")
                    .addKeyValue("clientAddress", ctx.channel().remoteAddress())
                    .addKeyValue("clientMessageVersion", message.version)
                    .addKeyValue("supportedVersion", FrameReader.VERSION).log(UNSUPPORTED_PROTOCOL_VERSION);
            sendResponse(new FrameReader.Message(UNSUPPORTED_PROTOCOL_VERSION.getBytes(StandardCharsets.UTF_8)),
                    message.requestId, BuiltInServiceDestinationCode.ERROR.getValue(), ctx, true);
            return;
        }

        // When there isn't context yet, we expect a call to be authorized first
        if (ctx.channel().attr(CONNECTION_CONTEXT_KEY).get() == null) {
            authenticationHandler.handleAuthentication(ctx, message);
            return;
        }

        if (FrameReader.FrameType.RESPONSE.equals(message.type)) {
            router.handleResponseMessage(ctx.channel().attr(CONNECTION_CONTEXT_KEY).get(), message);
            return;
        }

        // Get the callback for a destination. If it doesn't exist, then send back an error message.
        IPCCallback cb = router.getCallbackForDestination(message.destination);
        if (cb == null) {
            logger.atWarn().setEventType("request-destination-not-found")
                    .addKeyValue("clientAddress", ctx.channel().remoteAddress())
                    .addKeyValue("destination", message.destination).log(DEST_NOT_FOUND_ERROR);
            sendResponse(new FrameReader.Message(DEST_NOT_FOUND_ERROR.getBytes(StandardCharsets.UTF_8)),
                    message.requestId, BuiltInServiceDestinationCode.ERROR.getValue(), ctx, false);
            return;
        }

        try {
            // GG_NEEDS_REVIEW: TODO: Be smart about timeouts? https://issues.amazon.com/issues/86453f7c-c94e-4a3c-b8ff-679767e7443c
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
        // GG_NEEDS_REVIEW: TODO: Proper exception handling https://issues.amazon.com/issues/P32787597
        logger.atError("ipc-server-error", cause).log();
        // Close out the connection since we don't know what went wrong.
        ctx.close();
    }
}
