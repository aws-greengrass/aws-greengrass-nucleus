/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.tes;

import com.aws.greengrass.iot.IotCloudHelper;
import com.aws.greengrass.iot.IotConnectionManager;
import com.aws.greengrass.iot.model.IotCloudResponse;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.http.HttpClientConnection;
import software.amazon.awssdk.crt.http.HttpRequest;
import software.amazon.awssdk.crt.http.HttpStream;
import software.amazon.awssdk.crt.http.HttpStreamResponseHandler;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class IotCloudHelperTest {
    private static final byte[] CLOUD_RESPONSE = "HELLO WORLD".getBytes(StandardCharsets.UTF_8);
    private static final int STATUS_CODE = 200;
    private static final String HOST = "localhost";
    private static final String IOT_CREDENTIALS_PATH = "MOCK_PATH/get.json";

    @Mock
    IotConnectionManager mockConnectionManager;

    @Mock
    HttpClientConnection mockConnection;

    @Mock
    HttpStream mockHttpStream;

    @Test
    void GIVEN_valid_creds_WHEN_send_request_called_THEN_success() throws Exception {
        when(mockConnectionManager.getConnection()).thenReturn(mockConnection);
        when(mockConnectionManager.getHost()).thenReturn(HOST);
        when(mockHttpStream.getResponseStatusCode()).thenReturn(STATUS_CODE);
        doAnswer(invocationArgs -> {
            HttpStreamResponseHandler handler = (HttpStreamResponseHandler)invocationArgs.getArguments()[1];
            handler.onResponseBody(mockHttpStream, CLOUD_RESPONSE);
            handler.onResponseComplete(mockHttpStream, 0);
            return mockHttpStream;
        }).when(mockConnection).makeRequest(any(), any());
        IotCloudHelper cloudHelper = new IotCloudHelper();
        final IotCloudResponse response = cloudHelper.sendHttpRequest(mockConnectionManager, null,
                IOT_CREDENTIALS_PATH, CredentialRequestHandler.IOT_CREDENTIALS_HTTP_VERB, null);
        assertArrayEquals(CLOUD_RESPONSE, response.getResponseBody());
        assertEquals(STATUS_CODE, response.getStatusCode());
    }

    @Test
    void GIVEN_valid_creds_WHEN_send_request_called_with_body_THEN_success() throws Exception {
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        when(mockConnectionManager.getConnection()).thenReturn(mockConnection);
        when(mockConnectionManager.getHost()).thenReturn(HOST);
        doAnswer(invocationArgs -> {
            HttpRequest request = invocationArgs.getArgument(0);
            ByteBuffer byteBuffer = ByteBuffer.allocate(body.length);
            request.getBodyStream().sendRequestBody(byteBuffer);
            assertArrayEquals(body, byteBuffer.array());
            HttpStreamResponseHandler handler = invocationArgs.getArgument(1);
            handler.onResponseBody(mockHttpStream, CLOUD_RESPONSE);
            handler.onResponseComplete(mockHttpStream, 0);
            return mockHttpStream;
        }).when(mockConnection).makeRequest(any(), any());
        IotCloudHelper cloudHelper = new IotCloudHelper();
        final byte[] creds = cloudHelper.sendHttpRequest(mockConnectionManager, null,
                IOT_CREDENTIALS_PATH, CredentialRequestHandler.IOT_CREDENTIALS_HTTP_VERB, body).getResponseBody();
        assertArrayEquals(CLOUD_RESPONSE, creds);
    }

    @Test
    void GIVEN_error_code_once_WHEN_send_request_called_THEN_retry_and_success() throws Exception {
        when(mockConnectionManager.getConnection()).thenReturn(mockConnection);
        when(mockConnectionManager.getHost()).thenReturn(HOST);
        when(mockHttpStream.getResponseStatusCode()).thenReturn(STATUS_CODE);
        doAnswer(invocationArgs -> {
            HttpStreamResponseHandler handler = (HttpStreamResponseHandler) invocationArgs.getArguments()[1];
            handler.onResponseBody(mockHttpStream, CLOUD_RESPONSE);
            // error code not 0, fail to complete
            handler.onResponseComplete(mockHttpStream, 1);
            return mockHttpStream;
        }).doAnswer(invocationArgs -> {
            HttpStreamResponseHandler handler = (HttpStreamResponseHandler) invocationArgs.getArguments()[1];
            handler.onResponseBody(mockHttpStream, CLOUD_RESPONSE);
            handler.onResponseComplete(mockHttpStream, 0);
            return mockHttpStream;
        }).when(mockConnection).makeRequest(any(), any());
        IotCloudHelper cloudHelper = new IotCloudHelper();
        final IotCloudResponse response = cloudHelper.sendHttpRequest(mockConnectionManager, null, IOT_CREDENTIALS_PATH,
                CredentialRequestHandler.IOT_CREDENTIALS_HTTP_VERB, null);
        assertArrayEquals(CLOUD_RESPONSE, response.getResponseBody());
        assertEquals(STATUS_CODE, response.getStatusCode());
    }
}
