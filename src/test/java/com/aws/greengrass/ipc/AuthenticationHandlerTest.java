/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.ipc.common.BuiltInServiceDestinationCode;
import com.aws.greengrass.ipc.common.FrameReader;
import com.aws.greengrass.ipc.exceptions.UnauthenticatedException;
import com.aws.greengrass.ipc.services.authentication.AuthenticationRequest;
import com.aws.greengrass.ipc.services.authentication.AuthenticationResponse;
import com.aws.greengrass.ipc.services.common.ApplicationMessage;
import com.aws.greengrass.ipc.services.common.IPCUtil;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
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

import static com.aws.greengrass.ipc.AuthenticationHandler.AUTHENTICATION_API_VERSION;
import static com.aws.greengrass.ipc.AuthenticationHandler.AUTHENTICATION_TOKEN_LOOKUP_KEY;
import static com.aws.greengrass.ipc.AuthenticationHandler.SERVICE_UNIQUE_ID_KEY;
import static com.aws.greengrass.ipc.modules.CLIService.CLI_SERVICE;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreException;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith({MockitoExtension.class, GGExtension.class})
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
    void GIVEN_service_WHEN_register_auth_token_THEN_client_can_be_authenticated_with_token() throws Exception {
        context = new Context();
        Configuration config = new Configuration(context);
        config.context.put(ExecutorService.class, mock(ExecutorService.class));

        GreengrassService testService = new GreengrassService(
                config.lookupTopics(GreengrassService.SERVICES_NAMESPACE_TOPIC, SERVICE_NAME));
        AuthenticationHandler.registerAuthenticationToken(testService);
        Object authToken = testService.getPrivateConfig().find(SERVICE_UNIQUE_ID_KEY).getOnce();

        assertNotNull(authToken);
        assertEquals(SERVICE_NAME, config.find(GreengrassService.SERVICES_NAMESPACE_TOPIC, AUTHENTICATION_TOKEN_LOOKUP_KEY, (String) authToken)
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
    void GIVEN_cli_service_WHEN_register_auth_token_for_external_client_THEN_client_can_be_authenticated_with_token_WHEN_revoke_token_THEN_client_rejected() throws Exception {
        context = new Context();
        Configuration config = new Configuration(context);
        config.context.put(ExecutorService.class, mock(ExecutorService.class));

        GreengrassService testCliService = new GreengrassService(
                config.lookupTopics(GreengrassService.SERVICES_NAMESPACE_TOPIC, CLI_SERVICE));
        AuthenticationHandler.registerAuthenticationToken(testCliService);
        Object authToken = testCliService.getPrivateConfig().find(SERVICE_UNIQUE_ID_KEY).getOnce();

        assertNotNull(authToken);
        assertEquals(CLI_SERVICE, config.find(GreengrassService.SERVICES_NAMESPACE_TOPIC, AUTHENTICATION_TOKEN_LOOKUP_KEY, (String) authToken)
                .getOnce());

        AuthenticationHandler authenticationHandler = new AuthenticationHandler(config, mock(IPCRouter.class));
        String externalAuthToken =
                authenticationHandler.registerAuthenticationTokenForExternalClient(authToken.toString(),
                "externalClient");
        AuthenticationRequest authRequest = new AuthenticationRequest((String) externalAuthToken);
        ApplicationMessage applicationMessage =
                ApplicationMessage.builder().payload(IPCUtil.encode(authRequest)).version(AUTHENTICATION_API_VERSION).build();

        ConnectionContext authContext =
                authenticationHandler.doAuthentication(new FrameReader.Message(applicationMessage.toByteArray()), mock(SocketAddress.class));

        assertNotNull(authContext);
        assertEquals("externalClient", authContext.getServiceName());

        // WHEN CLI service revokes an auth token
        assertTrue(authenticationHandler.revokeAuthenticationTokenForExternalClient(authToken.toString(),
                externalAuthToken));
        // THEN rejected
        ApplicationMessage secondAuthRequest =
                ApplicationMessage.builder().payload(IPCUtil.encode(authRequest)).version(AUTHENTICATION_API_VERSION).build();
        assertThrows(UnauthenticatedException.class, () -> authenticationHandler
                .doAuthentication(new FrameReader.Message(secondAuthRequest.toByteArray()), mock(SocketAddress.class)));
    }

    @Test
    void GIVEN_non_cli_service_WHEN_register_or_revoke_auth_token_for_external_client_THEN_UnauthenticatedException() throws Exception {
        context = new Context();
        Configuration config = new Configuration(context);
        config.context.put(ExecutorService.class, mock(ExecutorService.class));

        GreengrassService testService = new GreengrassService(
                config.lookupTopics(GreengrassService.SERVICES_NAMESPACE_TOPIC, SERVICE_NAME));
        AuthenticationHandler.registerAuthenticationToken(testService);
        Object authToken = testService.getPrivateConfig().find(SERVICE_UNIQUE_ID_KEY).getOnce();

        assertNotNull(authToken);
        assertEquals(SERVICE_NAME, config.find(GreengrassService.SERVICES_NAMESPACE_TOPIC, AUTHENTICATION_TOKEN_LOOKUP_KEY,
                (String) authToken)
                .getOnce());

        AuthenticationHandler authenticationHandler = new AuthenticationHandler(config, mock(IPCRouter.class));
        assertThrows(UnauthenticatedException.class,
                ()->{authenticationHandler.registerAuthenticationTokenForExternalClient(authToken.toString(),
                        "externalClient");});

        assertThrows(UnauthenticatedException.class,
                ()->{authenticationHandler.revokeAuthenticationTokenForExternalClient(authToken.toString(),
                        "anyTokenToRevoke");});
    }

    @Test
    void GIVEN_service_WHEN_try_to_authenticate_with_bad_token_THEN_is_rejected() throws Exception {
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
    void GIVEN_unauthenticated_client_WHEN_send_auth_request_THEN_server_validates_token_and_authenticates_client()
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
    void GIVEN_unauthenticated_client_WHEN_send_bad_auth_request_THEN_server_validates_token_and_rejects_client(
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
    void GIVEN_unauthenticated_client_WHEN_send_any_request_THEN_server_forces_them_to_authenticate_first()
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
