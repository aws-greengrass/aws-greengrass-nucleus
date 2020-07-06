/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.tes;

import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.auth.credentials.Credentials;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class CredentialRequestHandlerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String ACCESS_KEY_ID = "ASIA";
    private static final String SECRET_ACCESS_KEY = "rLJt$$%RNDom";
    private static final String SESSION_TOKEN = "ABCDEFGHI";
    private static final String EXPIRATION = "2020-05-19T07:35:15Z";
    private static final String ROLE_ALIAS = "ROLE_ALIAS";
    private static final Credentials CREDENTIALS = new Credentials(
            ACCESS_KEY_ID.getBytes(StandardCharsets.UTF_8),
            SECRET_ACCESS_KEY.getBytes(StandardCharsets.UTF_8),
            SESSION_TOKEN.getBytes(StandardCharsets.UTF_8));
    private static final Date EXPIRATION_DATE;

    static {
        Date expiration_date = null;
        try {
            expiration_date = new SimpleDateFormat(CredentialRequestHandler.DATE_PATTERN, Locale.getDefault()).parse(EXPIRATION);
        } catch (ParseException e) {
        }
        EXPIRATION_DATE = expiration_date;
    }

    @Mock
    CredentialsProviderBuilder mockCredentialsProviderBuilder;

    @Test
    @SuppressWarnings("PMD.CloseResource")
    public void GIVEN_credential_handler_WHEN_called_handle_THEN_returns_creds() throws Exception {
        when(mockCredentialsProviderBuilder.getCredentials()).thenReturn(CREDENTIALS);
        when(mockCredentialsProviderBuilder.getCredentialsExpiration()).thenReturn(EXPIRATION_DATE);
        CredentialRequestHandler handler = new CredentialRequestHandler(ROLE_ALIAS, mockCredentialsProviderBuilder);
        HttpExchange mockExchange = mock(HttpExchange.class);
        OutputStream mockStream = mock(OutputStream.class);
        when(mockExchange.getResponseBody()).thenReturn(mockStream);
        handler.handle(mockExchange);
        int expectedStatus = 200;
        Map<String, String> expectedReponse = new HashMap<>();
        expectedReponse.put("AccessKeyId", ACCESS_KEY_ID);
        expectedReponse.put("SecretAccessKey", SECRET_ACCESS_KEY);
        expectedReponse.put("Token", SESSION_TOKEN);
        expectedReponse.put("Expiration", EXPIRATION);
        byte[] serializedResponse = OBJECT_MAPPER.writeValueAsBytes(expectedReponse);
        int expectedResponseLength = serializedResponse.length;
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedResponseLength);
        verify(mockStream, times(1)).write(serializedResponse);
        mockStream.close();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    public void GIVEN_credential_handler_WHEN_called_get_credentials_THEN_returns_success() throws Exception {
        when(mockCredentialsProviderBuilder.getCredentials()).thenReturn(CREDENTIALS);
        when(mockCredentialsProviderBuilder.getCredentialsExpiration()).thenReturn(EXPIRATION_DATE);
        CredentialRequestHandler handler = new CredentialRequestHandler(ROLE_ALIAS, mockCredentialsProviderBuilder);
        final byte[] creds = handler.getCredentials();
        verify(mockCredentialsProviderBuilder).getCredentials();
        Map<String, String> resp = OBJECT_MAPPER.readValue(creds, new TypeReference<Map<String,String>>(){});
        assertThat(ACCESS_KEY_ID, is(resp.get("AccessKeyId")));
        assertThat(SECRET_ACCESS_KEY, is(resp.get("SecretAccessKey")));
        assertThat(SESSION_TOKEN, is(resp.get("Token")));
        assertThat(EXPIRATION, is(resp.get("Expiration")));
    }
}
