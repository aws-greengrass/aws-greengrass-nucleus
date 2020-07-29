/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.tes;

import com.aws.iot.evergreen.auth.AuthorizationHandler;
import com.aws.iot.evergreen.auth.exceptions.AuthorizationException;
import com.aws.iot.evergreen.deployment.exceptions.AWSIotException;
import com.aws.iot.evergreen.iot.IotCloudHelper;
import com.aws.iot.evergreen.iot.IotConnectionManager;
import com.aws.iot.evergreen.iot.model.IotCloudResponse;
import com.aws.iot.evergreen.ipc.AuthenticationHandler;
import com.aws.iot.evergreen.ipc.exceptions.UnauthenticatedException;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.auth.credentials.AwsCredentials;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static com.aws.iot.evergreen.tes.CredentialRequestHandler.CLOUD_4XX_ERROR_CACHE_IN_MIN;
import static com.aws.iot.evergreen.tes.CredentialRequestHandler.CLOUD_5XX_ERROR_CACHE_IN_MIN;
import static com.aws.iot.evergreen.tes.CredentialRequestHandler.UNKNOWN_ERROR_CACHE_IN_MIN;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.iot.evergreen.tes.CredentialRequestHandler.TIME_BEFORE_CACHE_EXPIRE_IN_MIN;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    private static final String SECRET_ACCESS_KEY = "FC8OGbRnCl1";
    private static final String SESSION_TOKEN = "ABCDEFGHI";
    private static final String EXPIRATION = "2020-08-19T07:35:15Z";
    private static final String RESPONSE_STR =
            "{\"credentials\":" + "{\"accessKeyId\":\"" + ACCESS_KEY_ID + "\"," + "\"secretAccessKey\":\""
                    + SECRET_ACCESS_KEY + "\"," + "\"sessionToken\":\"" + SESSION_TOKEN + "\"," + "\"expiration\":\""
                    + "%s" + "\"}}";
    private static final String ROLE_ALIAS = "ROLE_ALIAS";
    private static final IotCloudResponse CLOUD_RESPONSE =
            new IotCloudResponse(String.format(RESPONSE_STR, EXPIRATION).getBytes(StandardCharsets.UTF_8), 200);
    @Mock
    IotConnectionManager mockConnectionManager;

    @Mock
    IotCloudHelper mockCloudHelper;

    @Mock
    AuthenticationHandler mockAuthNHandler;

    @Mock
    AuthorizationHandler mockAuthZHandler;

    private byte[] getExpectedResponse() throws Exception {
        Map<String, String> expectedResponse = new HashMap<>();
        expectedResponse.put("AccessKeyId", ACCESS_KEY_ID);
        expectedResponse.put("SecretAccessKey", SECRET_ACCESS_KEY);
        expectedResponse.put("Token", SESSION_TOKEN);
        expectedResponse.put("Expiration", EXPIRATION);
        return OBJECT_MAPPER.writeValueAsBytes(expectedResponse);
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    public void GIVEN_credential_handler_WHEN_called_handle_THEN_returns_creds() throws Exception {
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any())).thenReturn(CLOUD_RESPONSE);
        when(mockAuthNHandler.doAuthentication(anyString())).thenReturn("ServiceA");
        when(mockAuthZHandler.isAuthorized(any(), any())).thenReturn(true);
        CredentialRequestHandler handler = new CredentialRequestHandler(
                mockCloudHelper,
                mockConnectionManager,
                mockAuthNHandler,
                mockAuthZHandler);
        handler.setIotCredentialsPath(ROLE_ALIAS);
        when(mockAuthNHandler.doAuthentication(anyString())).thenReturn("ServiceA");
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
    public void GIVEN_credential_handler_WHEN_called_handle_with_unknown_error_THEN_5xx_returned(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, NullPointerException.class);
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
        when(mockAuthNHandler.doAuthentication(AUTHN_TOKEN)).thenThrow(NullPointerException.class);

        handler.handle(mockExchange);

        int expectedStatus = 500;
        int expectedResponseLength = -1;
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedResponseLength);
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
        when(mockAuthNHandler.doAuthentication(AUTHN_TOKEN)).thenReturn("ComponentA");

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
        when(mockAuthNHandler.doAuthentication(AUTHN_TOKEN)).thenThrow(UnauthenticatedException.class);

        handler.handle(mockExchange);
        int expectedStatus = 403;
        int expectedResponseLength = -1;
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedResponseLength);
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    public void GIVEN_credential_handler_WHEN_called_get_credentials_THEN_returns_success() throws Exception {
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any())).thenReturn(CLOUD_RESPONSE);
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
        Map<String, String> resp = OBJECT_MAPPER.readValue(creds, new TypeReference<Map<String, String>>() {
        });
        assertThat(ACCESS_KEY_ID, is(resp.get("AccessKeyId")));
        assertThat(SECRET_ACCESS_KEY, is(resp.get("SecretAccessKey")));
        assertThat(SESSION_TOKEN, is(resp.get("Token")));
        assertThat(EXPIRATION, is(resp.get("Expiration")));
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    public void GIVEN_credential_handler_WHEN_called_handle_THEN_caches_creds() throws Exception {
        // Expiry time in the past will give 500 error, no caching
        Instant expirationTime = Instant.now().minus(Duration.ofMinutes(1));
        String responseStr = String.format(RESPONSE_STR, expirationTime.toString());
        IotCloudResponse mockResponse = new IotCloudResponse(responseStr.getBytes(StandardCharsets.UTF_8), 200);
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any())).thenReturn(mockResponse);
        when(mockAuthNHandler.doAuthentication(anyString())).thenReturn("ServiceA");
        when(mockAuthZHandler.isAuthorized(any(), any())).thenReturn(true);
        CredentialRequestHandler handler = new CredentialRequestHandler(
                mockCloudHelper,
                mockConnectionManager,
                mockAuthNHandler,
                mockAuthZHandler);
        handler.setIotCredentialsPath(ROLE_ALIAS);
        HttpExchange mockExchange = mock(HttpExchange.class);
        OutputStream mockStream = mock(OutputStream.class);
        when(mockExchange.getResponseBody()).thenReturn(mockStream);
        when(mockExchange.getResponseBody()).thenReturn(mockStream);
        Headers mockHeader = mock(Headers.class);
        when(mockExchange.getRequestHeaders()).thenReturn(mockHeader);
        when(mockHeader.getFirst(anyString())).thenReturn("auth token");
        handler.handle(mockExchange);
        byte[] expectedResponse = ("TES responded with expired credentials: " + responseStr).getBytes();
        int expectedStatus = 500;
        verify(mockCloudHelper, times(1)).sendHttpRequest(any(), any(), any(), any());
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedResponse.length);
        verify(mockStream, times(1)).write(expectedResponse);

        // Expiry time in recent future won't give error but there wil be no caching
        expirationTime = Instant.now().plus(Duration.ofMinutes(TIME_BEFORE_CACHE_EXPIRE_IN_MIN - 1));
        responseStr = String.format(RESPONSE_STR, expirationTime.toString());
        mockResponse = new IotCloudResponse(responseStr.getBytes(StandardCharsets.UTF_8), 200);
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any())).thenReturn(mockResponse);
        handler.handle(mockExchange);
        verify(mockCloudHelper, times(2)).sendHttpRequest(any(), any(), any(), any());

        // Expiry time in future will result in credentials being cached
        expirationTime = Instant.now().plus(Duration.ofMinutes(TIME_BEFORE_CACHE_EXPIRE_IN_MIN + 1));
        responseStr = String.format(RESPONSE_STR, expirationTime.toString());
        mockResponse = new IotCloudResponse(responseStr.getBytes(StandardCharsets.UTF_8), 200);
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any())).thenReturn(mockResponse);
        handler.handle(mockExchange);
        verify(mockCloudHelper, times(3)).sendHttpRequest(any(), any(), any(), any());

        // Credentials were cached
        handler.handle(mockExchange);
        verify(mockCloudHelper, times(3)).sendHttpRequest(any(), any(), any(), any());

        // Cached credentials expired
        Clock mockClock = Clock.fixed(expirationTime, ZoneId.of("UTC"));
        handler.setClock(mockClock);
        handler.handle(mockExchange);
        verify(mockCloudHelper, times(4)).sendHttpRequest(any(), any(), any(), any());

        mockStream.close();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    public void GIVEN_unparsable_response_WHEN_called_handle_THEN_returns_error(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, AWSIotException.class);
        ignoreExceptionOfType(context, JsonParseException.class);

        String responseStr = "invalid_response_body";
        IotCloudResponse mockResponse = new IotCloudResponse(responseStr.getBytes(StandardCharsets.UTF_8), 200);
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any())).thenReturn(mockResponse);
        when(mockAuthNHandler.doAuthentication(anyString())).thenReturn("ServiceA");
        when(mockAuthZHandler.isAuthorized(any(), any())).thenReturn(true);
        CredentialRequestHandler handler = new CredentialRequestHandler(
                mockCloudHelper,
                mockConnectionManager,
                mockAuthNHandler,
                mockAuthZHandler);
        handler.setIotCredentialsPath(ROLE_ALIAS);
        HttpExchange mockExchange = mock(HttpExchange.class);
        OutputStream mockStream = mock(OutputStream.class);
        when(mockExchange.getResponseBody()).thenReturn(mockStream);
        when(mockExchange.getResponseBody()).thenReturn(mockStream);
        Headers mockHeader = mock(Headers.class);
        when(mockExchange.getRequestHeaders()).thenReturn(mockHeader);
        when(mockHeader.getFirst(anyString())).thenReturn("auth token");
        handler.handle(mockExchange);
        byte[] expectedReponse = ("Bad TES response: " + responseStr).getBytes();
        int expectedStatus = 500;
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedReponse.length);
        verify(mockStream, times(1)).write(expectedReponse);
        mockStream.close();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    public void GIVEN_no_response_code_WHEN_called_handle_THEN_expire_immediately() throws Exception {
        String responseStr = "";
        IotCloudResponse mockResponse = new IotCloudResponse(responseStr.getBytes(StandardCharsets.UTF_8), 0);
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any())).thenReturn(mockResponse);
        when(mockAuthNHandler.doAuthentication(anyString())).thenReturn("ServiceA");
        when(mockAuthZHandler.isAuthorized(any(), any())).thenReturn(true);
        CredentialRequestHandler handler = new CredentialRequestHandler(
                mockCloudHelper,
                mockConnectionManager,
                mockAuthNHandler,
                mockAuthZHandler);
        handler.setIotCredentialsPath(ROLE_ALIAS);
        HttpExchange mockExchange = mock(HttpExchange.class);
        OutputStream mockStream = mock(OutputStream.class);
        when(mockExchange.getResponseBody()).thenReturn(mockStream);
        when(mockExchange.getResponseBody()).thenReturn(mockStream);
        Headers mockHeader = mock(Headers.class);
        when(mockExchange.getRequestHeaders()).thenReturn(mockHeader);
        when(mockHeader.getFirst(anyString())).thenReturn("auth token");
        handler.handle(mockExchange);
        byte[] expectedResponse = "Failed to get credentials from TES".getBytes();
        int expectedStatus = 500;
        // expire immediately
        assertFalse(handler.areCredentialsValid());
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedResponse.length);
        verify(mockStream, times(1)).write(expectedResponse);
        mockStream.close();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    public void GIVEN_4xx_response_code_WHEN_called_handle_THEN_expire_in_2_minutes() throws Exception {
        byte[] response = {};
        IotCloudResponse mockResponse = new IotCloudResponse(response, 400);
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any())).thenReturn(mockResponse);
        when(mockAuthNHandler.doAuthentication(anyString())).thenReturn("ServiceA");
        when(mockAuthZHandler.isAuthorized(any(), any())).thenReturn(true);
        CredentialRequestHandler handler = new CredentialRequestHandler(
                mockCloudHelper,
                mockConnectionManager,
                mockAuthNHandler,
                mockAuthZHandler);
        handler.setIotCredentialsPath(ROLE_ALIAS);
        HttpExchange mockExchange = mock(HttpExchange.class);
        OutputStream mockStream = mock(OutputStream.class);
        when(mockExchange.getResponseBody()).thenReturn(mockStream);
        when(mockExchange.getResponseBody()).thenReturn(mockStream);
        Headers mockHeader = mock(Headers.class);
        when(mockExchange.getRequestHeaders()).thenReturn(mockHeader);
        when(mockHeader.getFirst(anyString())).thenReturn("auth token");
        handler.handle(mockExchange);
        int expectedStatus = 400;
        byte[] expectedResponse = String.format("TES responded with status code: %d", expectedStatus).getBytes();
        // expire in 2 minutes
        assertTrue(handler.areCredentialsValid());
        Instant expirationTime = Instant.now().plus(Duration.ofMinutes(CLOUD_4XX_ERROR_CACHE_IN_MIN));
        Clock mockClock = Clock.fixed(expirationTime, ZoneId.of("UTC"));
        handler.setClock(mockClock);
        assertFalse(handler.areCredentialsValid());
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedResponse.length);
        verify(mockStream, times(1)).write(expectedResponse);
        mockStream.close();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    public void GIVEN_5xx_response_code_WHEN_called_handle_THEN_expire_in_1_minute() throws Exception {
        byte[] response = {};
        IotCloudResponse mockResponse = new IotCloudResponse(response, 500);
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any())).thenReturn(mockResponse);
        when(mockAuthNHandler.doAuthentication(anyString())).thenReturn("ServiceA");
        when(mockAuthZHandler.isAuthorized(any(), any())).thenReturn(true);
        CredentialRequestHandler handler = new CredentialRequestHandler(
                mockCloudHelper,
                mockConnectionManager,
                mockAuthNHandler,
                mockAuthZHandler);
        handler.setIotCredentialsPath(ROLE_ALIAS);
        HttpExchange mockExchange = mock(HttpExchange.class);
        OutputStream mockStream = mock(OutputStream.class);
        when(mockExchange.getResponseBody()).thenReturn(mockStream);
        when(mockExchange.getResponseBody()).thenReturn(mockStream);
        Headers mockHeader = mock(Headers.class);
        when(mockExchange.getRequestHeaders()).thenReturn(mockHeader);
        when(mockHeader.getFirst(anyString())).thenReturn("auth token");
        handler.handle(mockExchange);
        int expectedStatus = 500;
        byte[] expectedResponse = String.format("TES responded with status code: %d", expectedStatus).getBytes();
        // expire in 1 minute
        assertTrue(handler.areCredentialsValid());
        Instant expirationTime = Instant.now().plus(Duration.ofMinutes(CLOUD_5XX_ERROR_CACHE_IN_MIN));
        Clock mockClock = Clock.fixed(expirationTime, ZoneId.of("UTC"));
        handler.setClock(mockClock);
        assertFalse(handler.areCredentialsValid());
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedResponse.length);
        verify(mockStream, times(1)).write(expectedResponse);
        mockStream.close();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    public void GIVEN_unknown_error_response_code_WHEN_called_handle_THEN_expire_in_5_minutes() throws Exception {
        byte[] response = {};
        IotCloudResponse mockResponse = new IotCloudResponse(response, 300);
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any())).thenReturn(mockResponse);
        when(mockAuthNHandler.doAuthentication(anyString())).thenReturn("ServiceA");
        when(mockAuthZHandler.isAuthorized(any(), any())).thenReturn(true);
        CredentialRequestHandler handler = new CredentialRequestHandler(
                mockCloudHelper,
                mockConnectionManager,
                mockAuthNHandler,
                mockAuthZHandler);
        handler.setIotCredentialsPath(ROLE_ALIAS);
        HttpExchange mockExchange = mock(HttpExchange.class);
        OutputStream mockStream = mock(OutputStream.class);
        when(mockExchange.getResponseBody()).thenReturn(mockStream);
        Headers mockHeader = mock(Headers.class);
        when(mockExchange.getRequestHeaders()).thenReturn(mockHeader);
        when(mockHeader.getFirst(anyString())).thenReturn("auth token");
        handler.handle(mockExchange);
        int expectedStatus = 300;
        byte[] expectedResponse = String.format("TES responded with status code: %d", expectedStatus).getBytes();
        // expire in 5 minutes
        assertTrue(handler.areCredentialsValid());
        Instant expirationTime = Instant.now().plus(Duration.ofMinutes(UNKNOWN_ERROR_CACHE_IN_MIN));
        Clock mockClock = Clock.fixed(expirationTime, ZoneId.of("UTC"));
        handler.setClock(mockClock);
        assertFalse(handler.areCredentialsValid());
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedResponse.length);
        verify(mockStream, times(1)).write(expectedResponse);
        mockStream.close();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    public void GIVEN_connection_error_WHEN_called_handle_THEN_expire_immediately() throws Exception {
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any())).thenThrow(AWSIotException.class);
        when(mockAuthNHandler.doAuthentication(anyString())).thenReturn("ServiceA");
        when(mockAuthZHandler.isAuthorized(any(), any())).thenReturn(true);

        CredentialRequestHandler handler = new CredentialRequestHandler(
                mockCloudHelper,
                mockConnectionManager,
                mockAuthNHandler,
                mockAuthZHandler);
        handler.setIotCredentialsPath(ROLE_ALIAS);
        HttpExchange mockExchange = mock(HttpExchange.class);
        OutputStream mockStream = mock(OutputStream.class);
        when(mockExchange.getResponseBody()).thenReturn(mockStream);
        Headers mockHeader = mock(Headers.class);
        when(mockExchange.getRequestHeaders()).thenReturn(mockHeader);
        when(mockHeader.getFirst(anyString())).thenReturn("auth token");
        handler.handle(mockExchange);
        byte[] expectedResponse = "Failed to get connection".getBytes();
        int expectedStatus = 500;
        // expire immediately
        assertFalse(handler.areCredentialsValid());
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedResponse.length);
        verify(mockStream, times(1)).write(expectedResponse);
        mockStream.close();
    }

    @Test
    public void GIVEN_credential_handler_WHEN_called_get_credentials_provider_THEN_returns_success() throws Exception {
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any())).thenReturn(CLOUD_RESPONSE);
        CredentialRequestHandler handler =
                new CredentialRequestHandler(ROLE_ALIAS, mockCloudHelper, mockConnectionManager);
        final AwsCredentials creds = handler.getAwsCredentials();
        final String expectedPath = "/role-aliases/" + ROLE_ALIAS + "/credentials";
        final String expectedVerb = "GET";
        verify(mockCloudHelper).sendHttpRequest(mockConnectionManager, expectedPath, expectedVerb, null);
        assertThat(ACCESS_KEY_ID, is(creds.accessKeyId()));
        assertThat(SECRET_ACCESS_KEY, is(creds.secretAccessKey()));
    }
}
