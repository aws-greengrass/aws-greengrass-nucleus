/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.ipc.handler;

import com.aws.iot.evergreen.ipc.IPCRouter;
import com.aws.iot.evergreen.ipc.common.FrameReader;
import com.aws.iot.evergreen.ipc.common.ConnectionContext;
import com.aws.iot.evergreen.ipc.exceptions.IPCClientNotAuthorizedException;
import com.aws.iot.evergreen.ipc.exceptions.IPCException;
import com.aws.iot.evergreen.util.Log;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
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

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static com.aws.iot.evergreen.ipc.common.Constants.AUTH_SERVICE;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class IPCChannelHandlerTest {
    public static final String ERROR_MESSAGE = "AAAAAAH!";
    @Mock
    AuthHandler mockAuth;
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
    public void setupMocks() throws Exception {
        router = new IPCChannelHandler(mock(Log.class), mockAuth, ipcRouter);

        when(mockCtx.channel()).thenReturn(mockChannel);
        when(mockChannel.attr(any())).thenReturn((Attribute) mockAttr);
        doAnswer((invocation) -> mockAttrValue = invocation.getArgument(0)).when(mockAttr).set(any());
        lenient().when(mockChannel.remoteAddress()).thenReturn(LocalAddress.ANY);

        router.channelRegistered(mockCtx);

        when(mockCtx.writeAndFlush(frameCaptor.capture())).thenReturn(mockChannelFuture);
    }

    @Test
    public void GIVEN_unauthenticated_client_WHEN_send_auth_request_THEN_server_validates_token_and_authenticates_client() throws Exception {
        // GIVEN
        // done in setupMocks

        // WHEN
        FrameReader.MessageFrame requestFrame = new FrameReader.MessageFrame(AUTH_SERVICE, new FrameReader.Message("MyAuthToken"
                .getBytes(StandardCharsets.UTF_8)), FrameReader.FrameType.REQUEST);

        ConnectionContext requestCtx = new ConnectionContext("ABC");
        when(mockAuth.doAuth(any())).thenReturn(requestCtx);

        router.channelRead(mockCtx, requestFrame);

        // THEN
        verify(mockChannelFuture, times(0)).addListener(any());

        FrameReader.MessageFrame responseFrame = frameCaptor.getValue();
        assertEquals(AUTH_SERVICE, responseFrame.destination);
        assertEquals(requestFrame.sequenceNumber, responseFrame.sequenceNumber);
        assertThat(new String(responseFrame.message.getPayload()), containsString("Success"));
        assertEquals(requestCtx, mockAttrValue);
    }

    @Test
    public void GIVEN_unauthenticated_client_WHEN_send_bad_auth_request_THEN_server_validates_token_and_rejects_client() throws Exception {
        // GIVEN
        // done in setupMocks

        when(mockAuth.doAuth(any())).thenThrow(new IPCClientNotAuthorizedException("No Auth!"));

        // WHEN
        FrameReader.MessageFrame requestFrame = new FrameReader.MessageFrame(AUTH_SERVICE, new FrameReader.Message("MyAuthToken"
                .getBytes(StandardCharsets.UTF_8)), FrameReader.FrameType.REQUEST);
        router.channelRead(mockCtx, requestFrame);

        // THEN
        verify(mockChannelFuture).addListener(eq(ChannelFutureListener.CLOSE));

        FrameReader.MessageFrame responseFrame = frameCaptor.getValue();
        assertEquals(AUTH_SERVICE, responseFrame.destination);
        assertEquals(requestFrame.sequenceNumber, responseFrame.sequenceNumber);
        assertThat(new String(responseFrame.message.getPayload()), containsString("Unauthorized"));
        assertThat(new String(responseFrame.message.getPayload()), containsString("Error while authenticating client"));
    }

    @Test
    public void GIVEN_unauthenticated_client_WHEN_send_any_request_THEN_server_forces_them_to_authenticate_first() throws Exception {
        // GIVEN
        // done in setupMocks

        // WHEN
        FrameReader.MessageFrame requestFrame = new FrameReader.MessageFrame("Destination", new FrameReader.Message(new byte[0]), FrameReader.FrameType.REQUEST);
        router.channelRead(mockCtx, requestFrame);

        // THEN
        verify(mockChannelFuture).addListener(eq(ChannelFutureListener.CLOSE));

        FrameReader.MessageFrame responseFrame = frameCaptor.getValue();
        assertEquals("Destination", responseFrame.destination);
        assertEquals(requestFrame.sequenceNumber, responseFrame.sequenceNumber);
        assertThat(new String(responseFrame.message.getPayload()), containsString("Error while authenticating client"));
    }

    @Test
    public void GIVEN_authenticated_client_WHEN_request_with_unregistered_destination_THEN_respond_with_error() throws Exception {
        // GIVEN
        // done in setupMocks

        // Pretend that we are authenticated
        when(mockAttr.get()).thenReturn(new ConnectionContext("ABC"));

        // WHEN
        FrameReader.MessageFrame requestFrame = new FrameReader.MessageFrame("Destination", new FrameReader.Message(new byte[0]), FrameReader.FrameType.REQUEST);
        router.channelRead(mockCtx, requestFrame);

        // THEN
        verify(mockChannelFuture, times(0)).addListener(any());
        verify(ipcRouter).getCallbackForDestination(eq("Destination"));

        FrameReader.MessageFrame responseFrame = frameCaptor.getValue();
        assertEquals("Destination", responseFrame.destination);
        assertEquals(requestFrame.sequenceNumber, responseFrame.sequenceNumber);
        assertThat(new String(responseFrame.message.getPayload()), containsString("Destination handler not found"));
    }

    @Test
    public void GIVEN_authenticated_client_WHEN_request_with_normal_return_THEN_respond_with_message() throws Exception {
        // GIVEN
        // done in setupMocks

        // Pretend that we are authenticated
        when(mockAttr.get()).thenReturn(new ConnectionContext("ABC"));
        // Setup handler for destination
        when(ipcRouter.getCallbackForDestination(anyString())).thenReturn((message, ctx) -> {
            CompletableFuture<FrameReader.Message> fut = new CompletableFuture<>();
            fut.complete(new FrameReader.Message("Success".getBytes()));
            return fut;
        });

        // WHEN
        FrameReader.MessageFrame requestFrame = new FrameReader.MessageFrame("Destination", new FrameReader.Message(new byte[0]), FrameReader.FrameType.REQUEST);
        router.channelRead(mockCtx, requestFrame);

        // THEN
        verify(mockChannelFuture, times(0)).addListener(any());
        verify(ipcRouter).getCallbackForDestination(eq("Destination"));

        FrameReader.MessageFrame responseFrame = frameCaptor.getValue();
        assertEquals("Destination", responseFrame.destination);
        assertEquals(requestFrame.sequenceNumber, responseFrame.sequenceNumber);
        assertEquals("Success", new String(responseFrame.message.getPayload()));
    }

    @Test
    public void GIVEN_authenticated_client_WHEN_request_with_exceptional_return_THEN_respond_with_error() throws Exception {
        // GIVEN
        // done in setupMocks

        // Pretend that we are authenticated
        when(mockAttr.get()).thenReturn(new ConnectionContext("ABC"));
        // Setup handler for destination
        when(ipcRouter.getCallbackForDestination(anyString())).thenReturn((message, ctx) -> {
            CompletableFuture<FrameReader.Message> fut = new CompletableFuture<>();
            fut.completeExceptionally(new IPCException(ERROR_MESSAGE));
            return fut;
        });

        // WHEN
        FrameReader.MessageFrame requestFrame = new FrameReader.MessageFrame("Destination", new FrameReader.Message(new byte[0]), FrameReader.FrameType.REQUEST);
        router.channelRead(mockCtx, requestFrame);

        // THEN
        verify(mockChannelFuture, times(0)).addListener(any());
        verify(ipcRouter).getCallbackForDestination(eq("Destination"));

        FrameReader.MessageFrame responseFrame = frameCaptor.getValue();
        assertEquals("Destination", responseFrame.destination);
        assertEquals(requestFrame.sequenceNumber, responseFrame.sequenceNumber);
        assertThat(new String(responseFrame.message.getPayload()), containsString("InternalError"));
        assertThat(new String(responseFrame.message.getPayload()), containsString(ERROR_MESSAGE));
    }
}
