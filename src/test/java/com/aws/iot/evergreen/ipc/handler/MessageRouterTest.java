/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.ipc.handler;

import com.aws.iot.evergreen.ipc.IPCRouter;
import com.aws.iot.evergreen.ipc.common.FrameReader;
import com.aws.iot.evergreen.ipc.common.RequestContext;
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
public class MessageRouterTest {
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
    Attribute<RequestContext> mockAttr;
    RequestContext mockAttrValue = null;

    private MessageRouter router;

    @BeforeEach
    public void setupMocks() throws Exception {
        router = new MessageRouter(mock(Log.class), mockAuth, ipcRouter);

        when(mockCtx.channel()).thenReturn(mockChannel);
        when(mockChannel.attr(any())).thenReturn((Attribute) mockAttr);
        doAnswer((invocation) -> mockAttrValue = invocation.getArgument(0)).when(mockAttr).set(any());
        lenient().when(mockChannel.remoteAddress()).thenReturn(LocalAddress.ANY);

        router.channelRegistered(mockCtx);
    }

    @Test
    public void GIVEN_unauthenticated_client_WHEN_send_auth_request_THEN_server_validates_token_and_authenticates_client() throws Exception {
        ArgumentCaptor<FrameReader.MessageFrame> frameCaptor = ArgumentCaptor.forClass(FrameReader.MessageFrame.class);
        ChannelFuture mockChannelFuture = mock(ChannelFuture.class);

        when(mockCtx.writeAndFlush(frameCaptor.capture())).thenReturn(mockChannelFuture);

        FrameReader.MessageFrame requestFrame = new FrameReader.MessageFrame(AUTH_SERVICE, new FrameReader.Message("MyAuthToken"
                .getBytes(StandardCharsets.UTF_8)), FrameReader.FrameType.REQUEST);

        RequestContext requestCtx = new RequestContext();
        requestCtx.serviceName = "ABC";
        when(mockAuth.doAuth(any())).thenReturn(requestCtx);

        router.channelRead(mockCtx, requestFrame);

        verify(mockChannelFuture, times(0)).addListener(any());

        FrameReader.MessageFrame responseFrame = frameCaptor.getValue();
        assertEquals(AUTH_SERVICE, responseFrame.destination);
        assertEquals(requestFrame.sequenceNumber, responseFrame.sequenceNumber);
        assertThat(new String(responseFrame.message.getPayload()), containsString("Success"));
        assertEquals(requestCtx, mockAttrValue);
    }

    @Test
    public void GIVEN_unauthenticated_client_WHEN_send_bad_auth_request_THEN_server_validates_token_and_rejects_client() throws Exception {
        ArgumentCaptor<FrameReader.MessageFrame> frameCaptor = ArgumentCaptor.forClass(FrameReader.MessageFrame.class);
        ChannelFuture mockChannelFuture = mock(ChannelFuture.class);

        when(mockCtx.writeAndFlush(frameCaptor.capture())).thenReturn(mockChannelFuture);

        FrameReader.MessageFrame requestFrame = new FrameReader.MessageFrame(AUTH_SERVICE, new FrameReader.Message("MyAuthToken"
                .getBytes(StandardCharsets.UTF_8)), FrameReader.FrameType.REQUEST);

        when(mockAuth.doAuth(any())).thenThrow(new IPCClientNotAuthorizedException("No Auth!"));

        router.channelRead(mockCtx, requestFrame);

        verify(mockChannelFuture, times(1)).addListener(eq(ChannelFutureListener.CLOSE));

        FrameReader.MessageFrame responseFrame = frameCaptor.getValue();
        assertEquals(AUTH_SERVICE, responseFrame.destination);
        assertEquals(requestFrame.sequenceNumber, responseFrame.sequenceNumber);
        assertThat(new String(responseFrame.message.getPayload()), containsString("Unauthorized"));
        assertThat(new String(responseFrame.message.getPayload()), containsString("Error while authenticating client"));
    }

    @Test
    public void GIVEN_unauthenticated_client_WHEN_send_any_request_THEN_server_forces_them_to_authenticate_first() throws Exception {
        ArgumentCaptor<FrameReader.MessageFrame> frameCaptor = ArgumentCaptor.forClass(FrameReader.MessageFrame.class);
        ChannelFuture mockChannelFuture = mock(ChannelFuture.class);

        when(mockCtx.writeAndFlush(frameCaptor.capture())).thenReturn(mockChannelFuture);

        FrameReader.MessageFrame requestFrame = new FrameReader.MessageFrame("Destination", new FrameReader.Message(new byte[0]), FrameReader.FrameType.REQUEST);
        router.channelRead(mockCtx, requestFrame);

        verify(mockChannelFuture, times(1)).addListener(eq(ChannelFutureListener.CLOSE));

        FrameReader.MessageFrame responseFrame = frameCaptor.getValue();
        assertEquals("Destination", responseFrame.destination);
        assertEquals(requestFrame.sequenceNumber, responseFrame.sequenceNumber);
        assertThat(new String(responseFrame.message.getPayload()), containsString("Error while authenticating client"));
    }

    @Test
    public void GIVEN_authenticated_client_WHEN_request_with_unregistered_destination_THEN_respond_with_error() throws Exception {
        ArgumentCaptor<FrameReader.MessageFrame> frameCaptor = ArgumentCaptor.forClass(FrameReader.MessageFrame.class);
        ChannelFuture mockChannelFuture = mock(ChannelFuture.class);
        when(mockCtx.writeAndFlush(frameCaptor.capture())).thenReturn(mockChannelFuture);

        // Pretend that we are authenticated
        when(mockAttr.get()).thenReturn(new RequestContext());

        FrameReader.MessageFrame requestFrame = new FrameReader.MessageFrame("Destination", new FrameReader.Message(new byte[0]), FrameReader.FrameType.REQUEST);
        router.channelRead(mockCtx, requestFrame);

        verify(mockChannelFuture, times(0)).addListener(any());
        verify(ipcRouter, times(1)).getCallbackForDestination(eq("Destination"));

        FrameReader.MessageFrame responseFrame = frameCaptor.getValue();
        assertEquals("Destination", responseFrame.destination);
        assertEquals(requestFrame.sequenceNumber, responseFrame.sequenceNumber);
        assertThat(new String(responseFrame.message.getPayload()), containsString("Destination handler not found"));
    }

    @Test
    public void GIVEN_authenticated_client_WHEN_request_with_normal_return_THEN_respond_with_message() throws Exception {
        ArgumentCaptor<FrameReader.MessageFrame> frameCaptor = ArgumentCaptor.forClass(FrameReader.MessageFrame.class);
        ChannelFuture mockChannelFuture = mock(ChannelFuture.class);
        when(mockCtx.writeAndFlush(frameCaptor.capture())).thenReturn(mockChannelFuture);

        // Pretend that we are authenticated
        when(mockAttr.get()).thenReturn(new RequestContext());
        // Setup handler for destination
        when(ipcRouter.getCallbackForDestination(anyString())).thenReturn((message, ctx) -> {
            CompletableFuture<FrameReader.Message> fut = new CompletableFuture<>();
            fut.complete(new FrameReader.Message("Success".getBytes()));
            return fut;
        });

        FrameReader.MessageFrame requestFrame = new FrameReader.MessageFrame("Destination", new FrameReader.Message(new byte[0]), FrameReader.FrameType.REQUEST);
        router.channelRead(mockCtx, requestFrame);

        verify(mockChannelFuture, times(0)).addListener(any());
        verify(ipcRouter, times(1)).getCallbackForDestination(eq("Destination"));

        FrameReader.MessageFrame responseFrame = frameCaptor.getValue();
        assertEquals("Destination", responseFrame.destination);
        assertEquals(requestFrame.sequenceNumber, responseFrame.sequenceNumber);
        assertEquals("Success", new String(responseFrame.message.getPayload()));
    }

    @Test
    public void GIVEN_authenticated_client_WHEN_request_with_exceptional_return_THEN_respond_with_error() throws Exception {
        ArgumentCaptor<FrameReader.MessageFrame> frameCaptor = ArgumentCaptor.forClass(FrameReader.MessageFrame.class);
        ChannelFuture mockChannelFuture = mock(ChannelFuture.class);
        when(mockCtx.writeAndFlush(frameCaptor.capture())).thenReturn(mockChannelFuture);

        // Pretend that we are authenticated
        when(mockAttr.get()).thenReturn(new RequestContext());
        // Setup handler for destination
        when(ipcRouter.getCallbackForDestination(anyString())).thenReturn((message, ctx) -> {
            CompletableFuture<FrameReader.Message> fut = new CompletableFuture<>();
            fut.completeExceptionally(new IPCException(ERROR_MESSAGE));
            return fut;
        });

        FrameReader.MessageFrame requestFrame = new FrameReader.MessageFrame("Destination", new FrameReader.Message(new byte[0]), FrameReader.FrameType.REQUEST);
        router.channelRead(mockCtx, requestFrame);

        verify(mockChannelFuture, times(0)).addListener(any());
        verify(ipcRouter, times(1)).getCallbackForDestination(eq("Destination"));

        FrameReader.MessageFrame responseFrame = frameCaptor.getValue();
        assertEquals("Destination", responseFrame.destination);
        assertEquals(requestFrame.sequenceNumber, responseFrame.sequenceNumber);
        assertThat(new String(responseFrame.message.getPayload()), containsString("InternalError"));
        assertThat(new String(responseFrame.message.getPayload()), containsString(ERROR_MESSAGE));
    }
}
