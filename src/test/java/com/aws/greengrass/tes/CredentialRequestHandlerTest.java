/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.tes;

import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.config.Subscriber;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.AWSIotException;
import com.aws.greengrass.iot.IotCloudHelper;
import com.aws.greengrass.iot.IotConnectionManager;
import com.aws.greengrass.iot.model.IotCloudResponse;
import com.aws.greengrass.ipc.AuthenticationHandler;
import com.aws.greengrass.ipc.exceptions.UnauthenticatedException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.auth.credentials.AwsCredentials;

import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.aws.greengrass.tes.CredentialRequestHandler.CLOUD_4XX_ERROR_CACHE_IN_SEC;
import static com.aws.greengrass.tes.CredentialRequestHandler.CLOUD_5XX_ERROR_CACHE_IN_SEC;
import static com.aws.greengrass.tes.CredentialRequestHandler.TIME_BEFORE_CACHE_EXPIRE_IN_SEC;
import static com.aws.greengrass.tes.CredentialRequestHandler.UNKNOWN_ERROR_CACHE_IN_SEC;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class CredentialRequestHandlerTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String REQUEST_METHOD = "GET";
    private static final URI TES_URI = URI.create(HttpServerImpl.URL);
    private static final String AUTHN_TOKEN = "random authN token";
    private static final String ACCESS_KEY_ID = "ASIA";
    private static final String SECRET_ACCESS_KEY = "FC8OGbRnCl1";
    private static final String SESSION_TOKEN = "ABCDEFGHI";
    // Set in the far future so it doesn't expire
    private static final String EXPIRATION = "2030-08-19T07:35:15Z";
    private static final String RESPONSE_STR =
            "{\"credentials\":" + "{\"accessKeyId\":\"" + ACCESS_KEY_ID + "\"," + "\"secretAccessKey\":\""
                    + SECRET_ACCESS_KEY + "\"," + "\"sessionToken\":\"" + SESSION_TOKEN + "\"," + "\"expiration\":\""
                    + "%s" + "\"}}";
    private static final String ROLE_ALIAS = "ROLE_ALIAS";
    private static final String THING_NAME = "thing_name";
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

    @Mock
    HttpExchange mockExchange;

    @Mock
    OutputStream mockStream;

    @Mock(answer = RETURNS_DEEP_STUBS)
    DeviceConfiguration mockDeviceConfig;

    private byte[] getExpectedResponse() throws Exception {
        Map<String, String> expectedResponse = new HashMap<>();
        expectedResponse.put("AccessKeyId", ACCESS_KEY_ID);
        expectedResponse.put("SecretAccessKey", SECRET_ACCESS_KEY);
        expectedResponse.put("Token", SESSION_TOKEN);
        expectedResponse.put("Expiration", EXPIRATION);
        return OBJECT_MAPPER.writeValueAsBytes(expectedResponse);
    }

    private CredentialRequestHandler setupHandler() {
        CredentialRequestHandler handler =
                new CredentialRequestHandler(mockCloudHelper, mockConnectionManager, mockAuthNHandler,
                        mockAuthZHandler, mockDeviceConfig);
        handler.setIotCredentialsPath(ROLE_ALIAS);
        when(mockExchange.getResponseBody()).thenReturn(mockStream);
        Headers mockHeader = mock(Headers.class);
        when(mockExchange.getRequestHeaders()).thenReturn(mockHeader);
        when(mockExchange.getRequestURI()).thenReturn(TES_URI);
        when(mockExchange.getRequestMethod()).thenReturn(REQUEST_METHOD);
        when(mockHeader.getFirst(anyString())).thenReturn("auth token");
        return handler;
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void GIVEN_credential_handler_WHEN_called_handle_THEN_returns_creds() throws Exception {
        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        when(mockCloudHelper.sendHttpRequest(any(), any(), pathCaptor.capture(), any(), any())).thenReturn(CLOUD_RESPONSE);
        when(mockAuthNHandler.doAuthentication(anyString())).thenReturn("ServiceA");
        when(mockAuthZHandler.isAuthorized(any(), any())).thenReturn(true);
        ArgumentCaptor<Subscriber> subscriberArgumentCaptor = ArgumentCaptor.forClass(Subscriber.class);
        when(mockDeviceConfig.getIotRoleAlias().subscribe(subscriberArgumentCaptor.capture())).thenReturn(null);
        CredentialRequestHandler handler =
                new CredentialRequestHandler(mockCloudHelper, mockConnectionManager, mockAuthNHandler,
                        mockAuthZHandler, mockDeviceConfig);
        handler.setIotCredentialsPath(ROLE_ALIAS);
        when(mockAuthNHandler.doAuthentication(anyString())).thenReturn("ServiceA");
        Headers mockHeaders = mock(Headers.class);
        when(mockHeaders.getFirst(any())).thenReturn(AUTHN_TOKEN);
        when(mockExchange.getResponseBody()).thenReturn(mockStream);
        when(mockExchange.getRequestHeaders()).thenReturn(mockHeaders);
        when(mockExchange.getRequestURI()).thenReturn(TES_URI);
        when(mockExchange.getRequestMethod()).thenReturn(REQUEST_METHOD);
        handler.handle(mockExchange);
        int expectedStatus = 200;
        byte[] serializedResponse = getExpectedResponse();
        int expectedResponseLength = serializedResponse.length;
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedResponseLength);
        verify(mockStream, times(1)).write(serializedResponse);
        mockStream.close();

        subscriberArgumentCaptor.getValue().published(WhatHappened.childChanged,
                Topic.of(mock(Context.class), "role", "role"));
        handler.getAwsCredentialsBypassCache();
        assertThat(pathCaptor.getValue(), containsString("role"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "POST", "DELETE", "PATCH"})
    @SuppressWarnings("PMD.CloseResource")
    void GIVEN_credential_handler_WHEN_unsupported_request_method_THEN_return_405(String verb) throws Exception {
        CredentialRequestHandler handler =
                new CredentialRequestHandler(mockCloudHelper, mockConnectionManager, mockAuthNHandler,
                        mockAuthZHandler, mockDeviceConfig);
        handler.setIotCredentialsPath(ROLE_ALIAS);
        HttpExchange mockExchange = mock(HttpExchange.class);
        when(mockExchange.getRequestMethod()).thenReturn(verb);
        handler.handle(mockExchange);

        int expectedStatus = 405;
        int expectedResponseLength = -1;
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedResponseLength);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/prefix" + HttpServerImpl.URL, HttpServerImpl.URL + "suffix/", "badUri"})
    @SuppressWarnings("PMD.CloseResource")
    void GIVEN_credential_handler_WHEN_unsupported_uri_THEN_return_400(String uri) throws Exception {
        CredentialRequestHandler handler =
                new CredentialRequestHandler(mockCloudHelper, mockConnectionManager, mockAuthNHandler,
                        mockAuthZHandler, mockDeviceConfig);
        handler.setIotCredentialsPath(ROLE_ALIAS);
        when(mockExchange.getRequestMethod()).thenReturn(REQUEST_METHOD);
        when(mockExchange.getRequestURI()).thenReturn(URI.create(uri));
        handler.handle(mockExchange);

        int expectedStatus = 400;
        int expectedResponseLength = -1;
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedResponseLength);
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void GIVEN_credential_handler_WHEN_called_handle_with_unknown_error_THEN_5xx_returned(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, NullPointerException.class);
        CredentialRequestHandler handler =
                new CredentialRequestHandler(mockCloudHelper, mockConnectionManager, mockAuthNHandler,
                        mockAuthZHandler, mockDeviceConfig);
        handler.setIotCredentialsPath(ROLE_ALIAS);
        Headers mockheaders = mock(Headers.class);
        when(mockheaders.getFirst(any())).thenReturn(AUTHN_TOKEN);
        when(mockExchange.getRequestHeaders()).thenReturn(mockheaders);
        when(mockExchange.getRequestURI()).thenReturn(TES_URI);
        when(mockExchange.getRequestMethod()).thenReturn(REQUEST_METHOD);
        when(mockAuthNHandler.doAuthentication(AUTHN_TOKEN)).thenThrow(NullPointerException.class);

        handler.handle(mockExchange);

        int expectedStatus = 500;
        int expectedResponseLength = -1;
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedResponseLength);
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void GIVEN_credential_handler_WHEN_unauthorized_request_THEN_return_403() throws Exception {
        CredentialRequestHandler handler =
                new CredentialRequestHandler(mockCloudHelper, mockConnectionManager, mockAuthNHandler,
                        mockAuthZHandler, mockDeviceConfig);
        handler.setIotCredentialsPath(ROLE_ALIAS);
        Headers mockheaders = mock(Headers.class);
        when(mockheaders.getFirst(any())).thenReturn(AUTHN_TOKEN);
        when(mockExchange.getRequestHeaders()).thenReturn(mockheaders);
        when(mockExchange.getRequestURI()).thenReturn(TES_URI);
        when(mockExchange.getRequestMethod()).thenReturn(REQUEST_METHOD);
        when(mockAuthNHandler.doAuthentication(AUTHN_TOKEN)).thenReturn("ComponentA");

        when(mockAuthZHandler.isAuthorized(any(), any())).thenThrow(AuthorizationException.class);
        handler.handle(mockExchange);

        int expectedStatus = 403;
        int expectedResponseLength = -1;
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedResponseLength);
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void GIVEN_credential_handler_WHEN_request_without_authN_THEN_return_403() throws Exception {
        CredentialRequestHandler handler =
                new CredentialRequestHandler(mockCloudHelper, mockConnectionManager, mockAuthNHandler,
                        mockAuthZHandler, mockDeviceConfig);
        handler.setIotCredentialsPath(ROLE_ALIAS);
        Headers mockheaders = mock(Headers.class);
        when(mockheaders.getFirst(any())).thenReturn(AUTHN_TOKEN);
        when(mockExchange.getRequestHeaders()).thenReturn(mockheaders);
        when(mockExchange.getRequestURI()).thenReturn(TES_URI);
        when(mockExchange.getRequestMethod()).thenReturn(REQUEST_METHOD);
        when(mockAuthNHandler.doAuthentication(AUTHN_TOKEN)).thenThrow(UnauthenticatedException.class);

        handler.handle(mockExchange);
        int expectedStatus = 403;
        int expectedResponseLength = -1;
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedResponseLength);
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void GIVEN_credential_handler_WHEN_called_get_credentials_THEN_returns_success() throws Exception {
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any(), any())).thenReturn(CLOUD_RESPONSE);
        CredentialRequestHandler handler =
                new CredentialRequestHandler(mockCloudHelper, mockConnectionManager, mockAuthNHandler,
                        mockAuthZHandler, mockDeviceConfig);
        handler.setIotCredentialsPath(ROLE_ALIAS);
        handler.setThingName(THING_NAME);
        final byte[] creds = handler.getCredentials();
        final String expectedPath = "/role-aliases/" + ROLE_ALIAS + "/credentials";
        final String expectedVerb = "GET";
        verify(mockCloudHelper).sendHttpRequest(mockConnectionManager, THING_NAME, expectedPath, expectedVerb, null);
        Map<String, String> resp = OBJECT_MAPPER.readValue(creds, new TypeReference<Map<String, String>>() {
        });
        assertThat(ACCESS_KEY_ID, is(resp.get("AccessKeyId")));
        assertThat(SECRET_ACCESS_KEY, is(resp.get("SecretAccessKey")));
        assertThat(SESSION_TOKEN, is(resp.get("Token")));
        assertThat(EXPIRATION, is(resp.get("Expiration")));

        // Cache will be returned if called again
        reset(mockCloudHelper);
        final byte[] cached_creds = handler.getCredentials();
        verify(mockCloudHelper, times(0)).sendHttpRequest(any(),any(),any(),any(),any());
        resp = OBJECT_MAPPER.readValue(cached_creds, new TypeReference<Map<String, String>>() {
        });
        assertThat(ACCESS_KEY_ID, is(resp.get("AccessKeyId")));
        assertThat(SECRET_ACCESS_KEY, is(resp.get("SecretAccessKey")));
        assertThat(SESSION_TOKEN, is(resp.get("Token")));
        assertThat(EXPIRATION, is(resp.get("Expiration")));

        // Clear cache then new request will be sent
        reset(mockCloudHelper);
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any(), any())).thenReturn(CLOUD_RESPONSE);
        handler.clearCache();
        handler.getCredentials();
        verify(mockCloudHelper).sendHttpRequest(any(),any(),any(),any(),any());
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void GIVEN_credential_handler_WHEN_called_handle_THEN_caches_creds() throws Exception {
        // Expiry time in the past will give 500 error, no caching
        Instant expirationTime = Instant.now().minus(Duration.ofMinutes(1));
        String responseStr = String.format(RESPONSE_STR, expirationTime.toString());
        IotCloudResponse mockResponse = new IotCloudResponse(responseStr.getBytes(StandardCharsets.UTF_8), 200);
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any(), any())).thenReturn(mockResponse);
        when(mockAuthNHandler.doAuthentication(anyString())).thenReturn("ServiceA");
        when(mockAuthZHandler.isAuthorized(any(), any())).thenReturn(true);
        CredentialRequestHandler handler = setupHandler();
        handler.handle(mockExchange);
        byte[] expectedResponse = ("TES responded with credentials that expired at " + expirationTime).getBytes();
        int expectedStatus = 500;
        verify(mockCloudHelper, times(1)).sendHttpRequest(any(), any(), any(), any(), any());
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedResponse.length);
        verify(mockStream, times(1)).write(expectedResponse);

        // Expiry time in recent future won't give error but there wil be no caching
        expirationTime = Instant.now().plus(Duration.ofSeconds(TIME_BEFORE_CACHE_EXPIRE_IN_SEC - 60));
        responseStr = String.format(RESPONSE_STR, expirationTime.toString());
        mockResponse = new IotCloudResponse(responseStr.getBytes(StandardCharsets.UTF_8), 200);
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any(), any())).thenReturn(mockResponse);
        handler.handle(mockExchange);
        verify(mockCloudHelper, times(2)).sendHttpRequest(any(), any(), any(), any(), any());

        // Expiry time in future will result in credentials being cached
        expirationTime = Instant.now().plus(Duration.ofSeconds(TIME_BEFORE_CACHE_EXPIRE_IN_SEC + 60));
        responseStr = String.format(RESPONSE_STR, expirationTime.toString());
        mockResponse = new IotCloudResponse(responseStr.getBytes(StandardCharsets.UTF_8), 200);
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any(), any())).thenReturn(mockResponse);
        handler.handle(mockExchange);
        verify(mockCloudHelper, times(3)).sendHttpRequest(any(), any(), any(), any(), any());

        // Credentials were cached
        handler.handle(mockExchange);
        verify(mockCloudHelper, times(3)).sendHttpRequest(any(), any(), any(), any(), any());

        // Cached credentials expired
        Clock mockClock = Clock.fixed(expirationTime, ZoneId.of("UTC"));
        handler.setClock(mockClock);
        handler.handle(mockExchange);
        verify(mockCloudHelper, times(4)).sendHttpRequest(any(), any(), any(), any(), any());

        mockStream.close();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void GIVEN_unparsable_response_WHEN_called_handle_THEN_returns_error(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, AWSIotException.class);
        ignoreExceptionOfType(context, JsonParseException.class);

        String responseStr = "invalid_response_body";
        IotCloudResponse mockResponse = new IotCloudResponse(responseStr.getBytes(StandardCharsets.UTF_8), 200);
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any(), any())).thenReturn(mockResponse);
        when(mockAuthNHandler.doAuthentication(anyString())).thenReturn("ServiceA");
        when(mockAuthZHandler.isAuthorized(any(), any())).thenReturn(true);
        CredentialRequestHandler handler = setupHandler();
        handler.handle(mockExchange);
        byte[] expectedReponse = ("Bad TES response: " + responseStr).getBytes();
        int expectedStatus = 500;
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedReponse.length);
        verify(mockStream, times(1)).write(expectedReponse);
        mockStream.close();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void GIVEN_no_response_code_WHEN_called_handle_THEN_expire_immediately() throws Exception {
        String responseStr = "";
        IotCloudResponse mockResponse = new IotCloudResponse(responseStr.getBytes(StandardCharsets.UTF_8), 0);
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any(), any())).thenReturn(mockResponse);
        when(mockAuthNHandler.doAuthentication(anyString())).thenReturn("ServiceA");
        when(mockAuthZHandler.isAuthorized(any(), any())).thenReturn(true);
        CredentialRequestHandler handler = setupHandler();
        handler.handle(mockExchange);
        byte[] expectedResponse = "Failed to get credentials from TES".getBytes();
        int expectedStatus = 500;
        handler.getAwsCredentials();
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedResponse.length);
        verify(mockStream, times(1)).write(expectedResponse);
        mockStream.close();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void GIVEN_4xx_response_code_WHEN_called_handle_THEN_expire_in_2_minutes() throws Exception {
        byte[] response = {};
        IotCloudResponse mockResponse = new IotCloudResponse(response, 400);
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any(), any())).thenReturn(mockResponse);
        when(mockAuthNHandler.doAuthentication(anyString())).thenReturn("ServiceA");
        when(mockAuthZHandler.isAuthorized(any(), any())).thenReturn(true);
        CredentialRequestHandler handler = setupHandler();
        handler.handle(mockExchange);
        int expectedStatus = 400;
        byte[] expectedResponse =
                String.format("TES responded with status code: %d. Caching response. ", expectedStatus).getBytes();
        // expire in 2 minutes
        handler.getAwsCredentials();
        Instant expirationTime = Instant.now().plus(Duration.ofSeconds(CLOUD_4XX_ERROR_CACHE_IN_SEC));
        Clock mockClock = Clock.fixed(expirationTime, ZoneId.of("UTC"));
        handler.setClock(mockClock);
        handler.getAwsCredentials();
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedResponse.length);
        verify(mockStream, times(1)).write(expectedResponse);
        mockStream.close();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void GIVEN_5xx_response_code_WHEN_called_handle_THEN_expire_in_1_minute() throws Exception {
        byte[] response = {};
        IotCloudResponse mockResponse = new IotCloudResponse(response, 500);
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any(), any())).thenReturn(mockResponse);
        when(mockAuthNHandler.doAuthentication(anyString())).thenReturn("ServiceA");
        when(mockAuthZHandler.isAuthorized(any(), any())).thenReturn(true);
        CredentialRequestHandler handler = setupHandler();
        handler.handle(mockExchange);
        int expectedStatus = 500;
        byte[] expectedResponse =
                String.format("TES responded with status code: %d. Caching response. ", expectedStatus).getBytes();
        // expire in 1 minute
        handler.getAwsCredentials();
        Instant expirationTime = Instant.now().plus(Duration.ofSeconds(CLOUD_5XX_ERROR_CACHE_IN_SEC));
        Clock mockClock = Clock.fixed(expirationTime, ZoneId.of("UTC"));
        handler.setClock(mockClock);
        handler.getAwsCredentials();
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedResponse.length);
        verify(mockStream, times(1)).write(expectedResponse);
        mockStream.close();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void GIVEN_unknown_error_response_code_WHEN_called_handle_THEN_expire_in_5_minutes() throws Exception {
        byte[] response = {};
        IotCloudResponse mockResponse = new IotCloudResponse(response, 300);
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any(), any())).thenReturn(mockResponse);
        when(mockAuthNHandler.doAuthentication(anyString())).thenReturn("ServiceA");
        when(mockAuthZHandler.isAuthorized(any(), any())).thenReturn(true);
        CredentialRequestHandler handler = setupHandler();
        handler.handle(mockExchange);
        int expectedStatus = 300;
        byte[] expectedResponse =
                String.format("TES responded with status code: %d. Caching response. ", expectedStatus).getBytes();
        // expire in 5 minutes
        handler.getAwsCredentials();
        Instant expirationTime = Instant.now().plus(Duration.ofSeconds(UNKNOWN_ERROR_CACHE_IN_SEC));
        Clock mockClock = Clock.fixed(expirationTime, ZoneId.of("UTC"));
        handler.setClock(mockClock);
        handler.getAwsCredentials();
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedResponse.length);
        verify(mockStream, times(1)).write(expectedResponse);
        mockStream.close();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void GIVEN_connection_error_WHEN_called_handle_THEN_expire_immediately(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, AWSIotException.class);
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any(), any())).thenThrow(AWSIotException.class);
        when(mockAuthNHandler.doAuthentication(anyString())).thenReturn("ServiceA");
        when(mockAuthZHandler.isAuthorized(any(), any())).thenReturn(true);
        CredentialRequestHandler handler = setupHandler();
        handler.handle(mockExchange);
        byte[] expectedResponse = "Failed to get connection".getBytes();
        int expectedStatus = 500;
        // expire immediately
        handler.getAwsCredentials();
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedResponse.length);
        verify(mockStream, times(1)).write(expectedResponse);
        mockStream.close();
    }

    @Test
    void GIVEN_credential_handler_WHEN_called_get_credentials_provider_THEN_returns_success() throws Exception {
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any(), any())).thenReturn(CLOUD_RESPONSE);
        CredentialRequestHandler handler =
                new CredentialRequestHandler(mockCloudHelper, mockConnectionManager, mockAuthNHandler,
                        mockAuthZHandler, mockDeviceConfig);
        handler.setIotCredentialsPath(ROLE_ALIAS);
        handler.setThingName(THING_NAME);
        final AwsCredentials creds = handler.getAwsCredentials();
        final String expectedPath = "/role-aliases/" + ROLE_ALIAS + "/credentials";
        final String expectedVerb = "GET";
        verify(mockCloudHelper).sendHttpRequest(mockConnectionManager, THING_NAME, expectedPath, expectedVerb, null);
        assertThat(ACCESS_KEY_ID, is(creds.accessKeyId()));
        assertThat(SECRET_ACCESS_KEY, is(creds.secretAccessKey()));
    }

    @Test
    void GIVEN_credential_handler_WHEN_called_multithreaded_get_credentials_provider_THEN_only_one_call_made() throws Exception {
        when(mockCloudHelper.sendHttpRequest(any(), any(), any(), any(), any())).thenAnswer((a) -> {
            // slow down so multiple requests stack up
            Thread.sleep(500);
            return CLOUD_RESPONSE;
        });
        CredentialRequestHandler handler =
                new CredentialRequestHandler(mockCloudHelper, mockConnectionManager, mockAuthNHandler,
                        mockAuthZHandler, mockDeviceConfig);
        handler.setIotCredentialsPath(ROLE_ALIAS);
        handler.setThingName(THING_NAME);
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            Future<AwsCredentials> futureA = executor.submit(handler::getAwsCredentials);
            Future<AwsCredentials> futureB = executor.submit(handler::getAwsCredentials);
            Future<AwsCredentials> futureC = executor.submit(handler::getAwsCredentials);

            AwsCredentials a = futureA.get();
            AwsCredentials b = futureB.get();
            AwsCredentials c = futureC.get();

            final String expectedPath = "/role-aliases/" + ROLE_ALIAS + "/credentials";
            final String expectedVerb = "GET";
            // Ensure that even though we made 3 requests at the same time, only one actually went to the cloud
            verify(mockCloudHelper, timeout(1000).times(1)).sendHttpRequest(mockConnectionManager, THING_NAME,
                    expectedPath, expectedVerb, null);
            assertEquals(a, b);
            assertEquals(a, c);
        } finally {
            executor.shutdownNow();
        }
    }
}
