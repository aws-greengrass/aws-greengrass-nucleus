/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc;

import com.aws.greengrass.ipc.common.BuiltInServiceDestinationCode;
import com.aws.greengrass.ipc.common.FrameReader;
import com.aws.greengrass.ipc.exceptions.IPCException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.local.LocalAddress;
import io.netty.util.Attribute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class IPCChannelHandlerTest {
    static final String ERROR_MESSAGE = "AAAAAAH!";
    @Mock
    AuthenticationHandler mockAuth;
    @Mock
    IPCRouter ipcRouter;
    @Mock
    ChannelHandlerContext mockCtx;
    @Mock
    Channel mockChannel;
    @Mock
    Attribute<ConnectionContext> mockAttr;
    ConnectionContext mockAttrValue = null;
    @Mock
    ChannelFuture mockChannelFuture;

    @Captor
    ArgumentCaptor<FrameReader.MessageFrame> frameCaptor;

    private IPCChannelHandler router;

    @BeforeEach
    void setupMocks() throws Exception {
        router = new IPCChannelHandler(mockAuth, ipcRouter);

        when(mockCtx.channel()).thenReturn(mockChannel);
        when(mockChannel.attr(any())).thenReturn((Attribute) mockAttr);
        doAnswer((invocation) -> mockAttrValue = invocation.getArgument(0)).when(mockAttr).set(any());
        lenient().when(mockChannel.remoteAddress()).thenReturn(LocalAddress.ANY);

        router.channelRegistered(mockCtx);

        when(mockCtx.writeAndFlush(frameCaptor.capture())).thenReturn(mockChannelFuture);
    }

    @Test
    void GIVEN_authenticated_client_WHEN_request_with_unregistered_destination_THEN_respond_with_error()
            throws Exception {
        // GIVEN
        // done in setupMocks

        // Pretend that we are authenticated
        when(mockAttr.get()).thenReturn(new ConnectionContext("ABC", mock(SocketAddress.class), mock(IPCRouter.class)));

        // WHEN
        FrameReader.MessageFrame requestFrame =
                new FrameReader.MessageFrame(200, new FrameReader.Message(new byte[0]), FrameReader.FrameType.REQUEST);
        router.channelRead(mockCtx, requestFrame);

        // THEN
        verify(mockChannelFuture, times(0)).addListener(any());
        verify(ipcRouter).getCallbackForDestination(eq(200));

        FrameReader.MessageFrame responseFrame = frameCaptor.getValue();
        assertEquals(BuiltInServiceDestinationCode.ERROR.getValue(), responseFrame.destination);
        assertEquals(requestFrame.requestId, responseFrame.requestId);
        assertThat(new String(responseFrame.message.getPayload()), containsString("Destination handler not found"));
    }

    @Test
    void GIVEN_authenticated_client_WHEN_request_with_normal_return_THEN_respond_with_message()
            throws Exception {
        // GIVEN
        // done in setupMocks

        // Pretend that we are authenticated
        when(mockAttr.get()).thenReturn(new ConnectionContext("ABC", mock(SocketAddress.class), mock(IPCRouter.class)));
        // Setup handler for destination
        when(ipcRouter.getCallbackForDestination(anyInt())).thenReturn((message, ctx) -> {
            CompletableFuture<FrameReader.Message> fut = new CompletableFuture<>();
            fut.complete(new FrameReader.Message("Success".getBytes()));
            return fut;
        });

        // WHEN
        FrameReader.MessageFrame requestFrame =
                new FrameReader.MessageFrame(200, new FrameReader.Message(new byte[0]), FrameReader.FrameType.REQUEST);
        router.channelRead(mockCtx, requestFrame);

        // THEN
        verify(mockChannelFuture, times(0)).addListener(any());
        verify(ipcRouter).getCallbackForDestination(eq(200));

        FrameReader.MessageFrame responseFrame = frameCaptor.getValue();
        assertEquals(200, responseFrame.destination);
        assertEquals(requestFrame.requestId, responseFrame.requestId);
        assertEquals("Success", new String(responseFrame.message.getPayload()));
    }

    @Test
    void GIVEN_authenticated_client_WHEN_request_with_exceptional_return_THEN_respond_with_error()
            throws Exception {
        // GIVEN
        // done in setupMocks

        // Pretend that we are authenticated
        when(mockAttr.get()).thenReturn(new ConnectionContext("ABC", mock(SocketAddress.class), mock(IPCRouter.class)));
        // Setup handler for destination
        when(ipcRouter.getCallbackForDestination(anyInt())).thenReturn((message, ctx) -> {
            CompletableFuture<FrameReader.Message> fut = new CompletableFuture<>();
            fut.completeExceptionally(new IPCException(ERROR_MESSAGE));
            return fut;
        });

        // WHEN
        FrameReader.MessageFrame requestFrame =
                new FrameReader.MessageFrame(200, new FrameReader.Message(new byte[0]), FrameReader.FrameType.REQUEST);
        router.channelRead(mockCtx, requestFrame);

        // THEN
        verify(mockChannelFuture, times(0)).addListener(any());
        verify(ipcRouter).getCallbackForDestination(eq(200));

        FrameReader.MessageFrame responseFrame = frameCaptor.getValue();
        assertEquals(200, responseFrame.destination);
        assertEquals(requestFrame.requestId, responseFrame.requestId);
        assertThat(new String(responseFrame.message.getPayload()), containsString("InternalError"));
        assertThat(new String(responseFrame.message.getPayload()), containsString(ERROR_MESSAGE));
    }
}
