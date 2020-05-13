/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.tes;

import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.OutputStream;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

@ExtendWith(MockitoExtension.class)
public class CredentialRequestHandlerTest {

    private static final String RESPONSE_STR = "HELLO";
    @Mock
    IotConnectionManager mockConnectionManager;

    @Mock
    IotCloudHelper mockCloudHelper;

    @Test
    @SuppressWarnings("PMD.CloseResource")
    public void GIVEN_credential_handler_WHEN_called_handle_THEN_returns_creds() throws Exception {
        when(mockCloudHelper.sendHttpRequest(any(), any(), any())).thenReturn(RESPONSE_STR);
        CredentialRequestHandler handler = new CredentialRequestHandler(mockCloudHelper, mockConnectionManager);
        HttpExchange mockExchange = mock(HttpExchange.class);
        OutputStream mockStream = mock(OutputStream.class);
        when(mockExchange.getResponseBody()).thenReturn(mockStream);
        handler.handle(mockExchange);
        int expectedStatus = 200;
        int expectedResponseLength = RESPONSE_STR.length();
        verify(mockExchange, times(1)).sendResponseHeaders(expectedStatus, expectedResponseLength);
        verify(mockStream, times(1)).write(RESPONSE_STR.getBytes());
        mockStream.close();
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    public void GIVEN_credential_handler_WHEN_called_get_credentials_THEN_returns_success() throws Exception {
        when(mockCloudHelper.sendHttpRequest(any(), any(), any())).thenReturn(RESPONSE_STR);
        CredentialRequestHandler handler = new CredentialRequestHandler(mockCloudHelper, mockConnectionManager);
        final String creds = handler.getCredentials();
        assertThat(RESPONSE_STR, is(creds));
    }
}
