/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.ipc.handler;

import com.aws.iot.evergreen.ipc.IPCCallback;
import com.aws.iot.evergreen.ipc.IPCRouter;
import com.aws.iot.evergreen.ipc.common.FrameReader;
import com.aws.iot.evergreen.ipc.common.GenericErrorCodes;
import com.aws.iot.evergreen.ipc.common.RequestContext;
import com.aws.iot.evergreen.ipc.services.common.GeneralResponse;
import com.aws.iot.evergreen.ipc.services.common.SendAndReceiveIPCUtil;
import com.aws.iot.evergreen.util.Log;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.io.IOException;
import javax.inject.Inject;

import static com.aws.iot.evergreen.ipc.common.Constants.AUTH_SERVICE;
import static com.aws.iot.evergreen.ipc.common.ResponseHelper.sendResponse;
import static com.aws.iot.evergreen.util.Utils.getUltimateMessage;

/**
 * Handles all incoming messages to the IPC server and authorizes, then appropriately
 * routes the message to the correct handler.
 */
@ChannelHandler.Sharable
@AllArgsConstructor
@NoArgsConstructor
public class MessageRouter extends ChannelInboundHandlerAdapter {
    public static final AttributeKey<RequestContext> CONNECTION_CONTEXT_KEY = AttributeKey.newInstance("ctx");
    private static final String DEST_NOT_FOUND_ERROR = "Destination handler not found";

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
        // TODO: Handle de-registration of any listeners such as Lifecycle https://issues.amazon.com/issues/P32808717
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        final FrameReader.MessageFrame message = (FrameReader.MessageFrame) msg;

        // When there isn't context yet, we expect a call to be authorized first
        if (ctx.channel().attr(CONNECTION_CONTEXT_KEY).get() == null) {
            handleAuth(ctx, message);
            return;
        }

        IPCCallback cb = router.getCallbackForDestination(message.destination);
        if (cb == null) {
            log.warn(DEST_NOT_FOUND_ERROR,
                    ctx.channel().remoteAddress(), message.destination);
            sendResponse(new FrameReader.Message(
                            SendAndReceiveIPCUtil.encode(GeneralResponse.builder()
                                    .error(GenericErrorCodes.Unknown)
                                    .errorMessage(DEST_NOT_FOUND_ERROR)
                                    .build())),
                    message.sequenceNumber,
                    message.destination, ctx, false);
            return;
        }

        try {
            // TODO: Be smart about timeouts? https://issues.amazon.com/issues/86453f7c-c94e-4a3c-b8ff-679767e7443c
            FrameReader.Message responseMessage = cb.onMessage(message.message,
                    ctx.channel().attr(CONNECTION_CONTEXT_KEY).get())
                    // This .get() blocks forever waiting for the response to the request
                    .get();
            sendResponse(responseMessage,
                    message.sequenceNumber,
                    message.destination, ctx, false);
        } catch (Throwable throwable) {
            // This is just a catch-all. Any service specific errors should be handled by the service code.
            // Ideally this never gets executed.
            sendResponse(new FrameReader.Message(
                            SendAndReceiveIPCUtil.encode(GeneralResponse.builder()
                                    .error(GenericErrorCodes.Unknown)
                                    .errorMessage(getUltimateMessage(throwable))
                                    .build())),
                    message.sequenceNumber,
                    message.destination, ctx, false);
        }
    }

    private void handleAuth(ChannelHandlerContext ctx, FrameReader.MessageFrame message) throws IOException {
        if (message.destination.equals(AUTH_SERVICE)) {
            try {
                RequestContext context = auth.doAuth(message.message);
                ctx.channel().attr(CONNECTION_CONTEXT_KEY).set(context);
                log.note("Successfully authenticated client", ctx.channel().remoteAddress(), context);
                sendResponse(new FrameReader.Message(
                                SendAndReceiveIPCUtil.encode(GeneralResponse.builder()
                                        .error(GenericErrorCodes.Success)
                                        .build())),
                        message.sequenceNumber,
                        message.destination, ctx, false);
            } catch (Throwable t) {
                log.warn("Error while authenticating client", ctx.channel().remoteAddress(), t);
                sendResponse(new FrameReader.Message(
                                SendAndReceiveIPCUtil.encode(GeneralResponse.builder()
                                        .errorMessage("Error while authenticating client")
                                        .error(GenericErrorCodes.Unauthorized)
                                        .build())),
                        message.sequenceNumber,
                        message.destination, ctx, true);
            }
        } else {
            log.warn("Wrong destination for first packet from client", ctx.channel().remoteAddress());
            sendResponse(new FrameReader.Message(
                            SendAndReceiveIPCUtil.encode(GeneralResponse.builder()
                                    .errorMessage("Error while authenticating client")
                                    .error(GenericErrorCodes.Unauthorized)
                                    .build())),
                    message.sequenceNumber,
                    message.destination, ctx, true);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        // TODO: Proper exception handling https://issues.amazon.com/issues/P32787597
        log.warn("Error in IPC server", cause);
    }
}
