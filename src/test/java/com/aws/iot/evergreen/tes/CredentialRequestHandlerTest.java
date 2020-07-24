/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.tes;

import com.aws.iot.evergreen.auth.AuthorizationHandler;
import com.aws.iot.evergreen.auth.exceptions.AuthorizationException;
import com.aws.iot.evergreen.iot.IotCloudHelper;
import com.aws.iot.evergreen.iot.IotConnectionManager;
import com.aws.iot.evergreen.ipc.AuthNHandler;
import com.aws.iot.evergreen.ipc.exceptions.UnauthenticatedException;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class CredentialRequestHandlerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String AUTHN_TOKEN = "random authN token";
    private static final String ACCESS_KEY_ID = "ASIA";
    private static final String SECRET_ACCESS_KEY = "rLJt$$%RNDom";
    private static final String SESSION_TOKEN = "ABCDEFGHI";
    private static final String EXPIRATION = "2020-05-19T07:35:15Z";
    private static final String RESPONSE_STR = "{\"credentials\":" +
            "{\"accessKeyId\":\"" + ACCESS_KEY_ID + "\"," +
            "\"secretAccessKey\":\"" + SECRET_ACCESS_KEY + "\"," +
            "\"sessionToken\":\"" + SESSION_TOKEN + "\"," +
            "\"expiration\":\"" + EXPIRATION + "\"}}";
    private static final String ROLE_ALIAS = "ROLE_ALIAS";
    @Mock
    IotConnectionManager mockConnectionManager;

    @Mock
    IotCloudHelper mockCloudHelper;

    @Mock
    AuthNHandler mockAuthNHandler;

    @Mock
    AuthorizationHandler mockAuthZHandler;

    private byte[] getExpectedResponse() throws Exception {
        Map<String, String> expectedReponse = new HashMap<>();
        expectedReponse.put("AccessKeyId", ACCESS_KEY_ID);
        expectedReponse.put("SecretAccessKey", SECRET_ACCESS_KEY);
        expectedReponse.put("Token", SESSION_TOKEN);
        expectedReponse.put("Expiration", EXPIRATION);
        return OBJECT_MAPPER.writeValueAsBytes(expectedReponse);
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    public void GIVEN_credential_handler_WHEN_called_handle_THEN_returns_creds() throws Exception {
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any())).thenReturn(RESPONSE_STR);
        CredentialRequestHandler handler = new CredentialRequestHandler(
                mockCloudHelper,
                mockConnectionManager,
                mockAuthNHandler,
                mockAuthZHandler);
        handler.setIotCredentialsPath(ROLE_ALIAS);
        when(mockAuthNHandler.doAuthN(anyString())).thenReturn("ServiceA");
        HttpExchange mockExchange = mock(HttpExchange.class);
        OutputStream mockStream = mock(OutputStream.class);
        Headers mockHeaders = mock(Headers.class);
        when(mockHeaders.getFirst(any())).thenReturn(AUTHN_TOKEN);
        when(mockExchange.getResponseBody()).thenReturn(mockStream);
        when(mockExchange.getRequestHeaders()).thenReturn(mockHeaders);
        handler.handle(mockExchange);
        int expectedStatus = 200;
        byte[] serializedResponse = getExpectedResponse();
        int expectedResponseLength = serializedResponse.length;
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedResponseLength);
        verify(mockStream, times(1)).write(serializedResponse);
        mockStream.close();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    public void GIVEN_credential_handler_WHEN_unauthorized_request_THEN_return_403() throws Exception {
        CredentialRequestHandler handler = new CredentialRequestHandler(
                mockCloudHelper,
                mockConnectionManager,
                mockAuthNHandler,
                mockAuthZHandler);
        handler.setIotCredentialsPath(ROLE_ALIAS);
        HttpExchange mockExchange = mock(HttpExchange.class);
        Headers mockheaders = mock(Headers.class);
        when(mockheaders.getFirst(any())).thenReturn(AUTHN_TOKEN);
        when(mockExchange.getRequestHeaders()).thenReturn(mockheaders);
        when(mockAuthNHandler.doAuthN(AUTHN_TOKEN)).thenReturn("ComponentA");

        when(mockAuthZHandler.isAuthorized(any(), any())).thenThrow(AuthorizationException.class);
        handler.handle(mockExchange);

        int expectedStatus = 403;
        int expectedResponseLength = -1;
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedResponseLength);
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    public void GIVEN_credential_handler_WHEN_request_without_authN_THEN_return_403() throws Exception {
        CredentialRequestHandler handler = new CredentialRequestHandler(
                mockCloudHelper,
                mockConnectionManager,
                mockAuthNHandler,
                mockAuthZHandler);
        handler.setIotCredentialsPath(ROLE_ALIAS);
        HttpExchange mockExchange = mock(HttpExchange.class);
        Headers mockheaders = mock(Headers.class);
        when(mockheaders.getFirst(any())).thenReturn(AUTHN_TOKEN);
        when(mockExchange.getRequestHeaders()).thenReturn(mockheaders);
        when(mockAuthNHandler.doAuthN(AUTHN_TOKEN)).thenThrow(UnauthenticatedException.class);

        handler.handle(mockExchange);
        int expectedStatus = 403;
        int expectedResponseLength = -1;
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedResponseLength);
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    public void GIVEN_credential_handler_WHEN_called_get_credentials_THEN_returns_success() throws Exception {
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any())).thenReturn(RESPONSE_STR);
        CredentialRequestHandler handler = new CredentialRequestHandler(
                mockCloudHelper,
                mockConnectionManager,
                mockAuthNHandler,
                mockAuthZHandler);
        handler.setIotCredentialsPath(ROLE_ALIAS);
        final byte[] creds = handler.getCredentials();
        final String expectedPath = "/role-aliases/" + ROLE_ALIAS + "/credentials";
        final String expectedVerb = "GET";
        verify(mockCloudHelper).sendHttpRequest(mockConnectionManager, expectedPath, expectedVerb, null);
        Map<String, String> resp = OBJECT_MAPPER.readValue(creds, new TypeReference<Map<String,String>>(){});
        assertThat(ACCESS_KEY_ID, is(resp.get("AccessKeyId")));
        assertThat(SECRET_ACCESS_KEY, is(resp.get("SecretAccessKey")));
        assertThat(SESSION_TOKEN, is(resp.get("Token")));
        assertThat(EXPIRATION, is(resp.get("Expiration")));
    }
}
