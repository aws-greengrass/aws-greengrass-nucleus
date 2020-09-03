/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.ipc;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.ipc.common.BuiltInServiceDestinationCode;
import com.aws.iot.evergreen.ipc.common.FrameReader;
import com.aws.iot.evergreen.ipc.exceptions.UnauthenticatedException;
import com.aws.iot.evergreen.ipc.services.authentication.AuthenticationRequest;
import com.aws.iot.evergreen.ipc.services.authentication.AuthenticationResponse;
import com.aws.iot.evergreen.ipc.services.common.ApplicationMessage;
import com.aws.iot.evergreen.ipc.services.common.IPCUtil;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.local.LocalAddress;
import io.netty.util.Attribute;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;

import static com.aws.iot.evergreen.ipc.AuthenticationHandler.AUTHENTICATION_API_VERSION;
import static com.aws.iot.evergreen.ipc.AuthenticationHandler.AUTHENTICATION_TOKEN_LOOKUP_KEY;
import static com.aws.iot.evergreen.ipc.AuthenticationHandler.SERVICE_UNIQUE_ID_KEY;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreException;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith({MockitoExtension.class, EGExtension.class})
class AuthenticationHandlerTest {
    private static final String SERVICE_NAME = "ServiceName";

    AuthenticationHandler mockAuthenticationHandler;
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

    private Context context;

    @BeforeEach
    public void setupMocks() {
        lenient().when(mockCtx.channel()).thenReturn(mockChannel);
        lenient().when(mockChannel.attr(any())).thenReturn((Attribute) mockAttr);
        lenient().doAnswer((invocation) -> mockAttrValue = invocation.getArgument(0)).when(mockAttr).set(any());
        lenient().when(mockChannel.remoteAddress()).thenReturn(LocalAddress.ANY);
        lenient().when(mockCtx.writeAndFlush(frameCaptor.capture())).thenReturn(mockChannelFuture);
        mockAuthenticationHandler = spy(new AuthenticationHandler(mock(Configuration.class), mock(IPCRouter.class)));
    }

    @AfterEach
    void afterEach() throws IOException {
        if (context != null) {
            context.close();
        }
    }

    @Test
    public void GIVEN_service_WHEN_register_auth_token_THEN_client_can_be_authenticated_with_token() throws Exception {
        context = new Context();
        Configuration config = new Configuration(context);
        config.context.put(ExecutorService.class, mock(ExecutorService.class));

        EvergreenService testService = new EvergreenService(
                config.lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC, SERVICE_NAME));
        AuthenticationHandler.registerAuthenticationToken(testService);
        Object authToken = testService.getPrivateConfig().find(SERVICE_UNIQUE_ID_KEY).getOnce();

        assertNotNull(authToken);
        assertEquals(SERVICE_NAME, config.find(EvergreenService.SERVICES_NAMESPACE_TOPIC, AUTHENTICATION_TOKEN_LOOKUP_KEY, (String) authToken)
                .getOnce());

        AuthenticationHandler auth = new AuthenticationHandler(config, mock(IPCRouter.class));

        AuthenticationRequest authRequest = new AuthenticationRequest((String) authToken);
        ApplicationMessage applicationMessage =
                ApplicationMessage.builder().payload(IPCUtil.encode(authRequest)).version(AUTHENTICATION_API_VERSION).build();

        ConnectionContext authContext =
                auth.doAuthentication(new FrameReader.Message(applicationMessage.toByteArray()), mock(SocketAddress.class));

        assertNotNull(authContext);
        assertEquals(SERVICE_NAME, authContext.getServiceName());
    }

    @Test
    public void GIVEN_service_WHEN_try_to_authenticate_with_bad_token_THEN_is_rejected() throws Exception {
        context = new Context();
        Configuration config = new Configuration(context);

        AuthenticationHandler auth = new AuthenticationHandler(config, mock(IPCRouter.class));
        AuthenticationRequest authenticationRequest = new AuthenticationRequest("MyAuthToken");
        ApplicationMessage applicationMessage =
                ApplicationMessage.builder().payload(IPCUtil.encode(authenticationRequest)).version(AUTHENTICATION_API_VERSION).build();

        assertThrows(UnauthenticatedException.class, () -> auth
                .doAuthentication(new FrameReader.Message(applicationMessage.toByteArray()), mock(SocketAddress.class)));
    }

    @Test
    public void GIVEN_unauthenticated_client_WHEN_send_auth_request_THEN_server_validates_token_and_authenticates_client()
            throws Exception {
        // GIVEN
        // done in setupMocks

        // WHEN
        AuthenticationRequest authRequest = new AuthenticationRequest("MyAuthToken");
        ApplicationMessage applicationMessage =
                ApplicationMessage.builder().payload(IPCUtil.encode(authRequest)).version(AUTHENTICATION_API_VERSION).build();

        FrameReader.MessageFrame requestFrame =
                new FrameReader.MessageFrame(BuiltInServiceDestinationCode.AUTHENTICATION.getValue(),
                        new FrameReader.Message(applicationMessage.toByteArray()), FrameReader.FrameType.REQUEST);

        ConnectionContext requestCtx = new ConnectionContext("ABC", mock(SocketAddress.class), mock(IPCRouter.class));
        doReturn(requestCtx).when(mockAuthenticationHandler).doAuthentication(any(), any());

        mockAuthenticationHandler.handleAuthentication(mockCtx, requestFrame);

        // THEN
        verify(mockChannelFuture, times(0)).addListener(any());

        FrameReader.MessageFrame responseFrame = frameCaptor.getValue();
        assertEquals(BuiltInServiceDestinationCode.AUTHENTICATION.getValue(), responseFrame.destination);
        assertEquals(requestFrame.requestId, responseFrame.requestId);

        AuthenticationResponse authResponse =
                IPCUtil.decode(ApplicationMessage.fromBytes(responseFrame.message.getPayload()).getPayload(),
                        AuthenticationResponse.class);
        assertEquals("ABC", authResponse.getServiceName());
        assertNotNull(authResponse.getClientId());
        assertEquals(requestCtx, mockAttrValue);
    }

    @Test
    public void GIVEN_unauthenticated_client_WHEN_send_bad_auth_request_THEN_server_validates_token_and_rejects_client(
            ExtensionContext context)
            throws Exception {
        // GIVEN
        // done in setupMocks

        UnauthenticatedException ex = new UnauthenticatedException("No Auth!");
        ignoreException(context, ex);
        doThrow(ex).when(mockAuthenticationHandler).doAuthentication(any(), any());

        // WHEN
        FrameReader.MessageFrame requestFrame =
                new FrameReader.MessageFrame(BuiltInServiceDestinationCode.AUTHENTICATION.getValue(),
                        new FrameReader.Message("MyAuthToken".getBytes(StandardCharsets.UTF_8)),
                        FrameReader.FrameType.REQUEST);
        mockAuthenticationHandler.handleAuthentication(mockCtx, requestFrame);

        // THEN
        verify(mockChannelFuture).addListener(eq(ChannelFutureListener.CLOSE));

        FrameReader.MessageFrame responseFrame = frameCaptor.getValue();
        assertEquals(BuiltInServiceDestinationCode.AUTHENTICATION.getValue(), responseFrame.destination);
        assertEquals(requestFrame.requestId, responseFrame.requestId);
        AuthenticationResponse authResponse =
                IPCUtil.decode(ApplicationMessage.fromBytes(responseFrame.message.getPayload()).getPayload(),
                        AuthenticationResponse.class);
        assertThat(authResponse.getErrorMessage(), containsString("Error while authenticating client"));
    }

    @Test
    public void GIVEN_unauthenticated_client_WHEN_send_any_request_THEN_server_forces_them_to_authenticate_first()
            throws Exception {
        // GIVEN
        // done in setupMocks

        // WHEN
        FrameReader.MessageFrame requestFrame =
                new FrameReader.MessageFrame(255, new FrameReader.Message(new byte[0]), FrameReader.FrameType.REQUEST);
        mockAuthenticationHandler.handleAuthentication(mockCtx, requestFrame);

        // THEN
        verify(mockChannelFuture).addListener(eq(ChannelFutureListener.CLOSE));

        FrameReader.MessageFrame responseFrame = frameCaptor.getValue();
        assertEquals(255, responseFrame.destination);
        assertEquals(requestFrame.requestId, responseFrame.requestId);
        assertThat(new String(responseFrame.message.getPayload()), containsString("Error while authenticating client"));
    }
}