/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.tes;

import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.iot.IotCloudHelper;
import com.aws.greengrass.iot.IotConnectionManager;
import com.aws.greengrass.iot.model.IotCloudResponse;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class IotCloudHelperTest {
    private static final byte[] CLOUD_RESPONSE = "HELLO WORLD".getBytes(StandardCharsets.UTF_8);
    private static final int STATUS_CODE = 200;
    private static final URI HOST = URI.create("http://localhost");
    private static final String IOT_CREDENTIALS_PATH = "MOCK_PATH/get.json";

    @Mock
    IotConnectionManager mockConnectionManager;

    @Mock
    SdkHttpClient mockClient;

    @Test
    void GIVEN_valid_creds_WHEN_send_request_called_THEN_success() throws Exception {
        when(mockConnectionManager.getClient()).thenReturn(mockClient);
        when(mockConnectionManager.getURI()).thenReturn(HOST);

        ExecutableHttpRequest requestMock = mock(ExecutableHttpRequest.class);
        when(requestMock.call()).thenReturn(
                HttpExecuteResponse.builder().response(SdkHttpResponse.builder().statusCode(STATUS_CODE).build())
                        .responseBody(AbortableInputStream.create(new ByteArrayInputStream(CLOUD_RESPONSE))).build());

        doReturn(requestMock).when(mockClient).prepareRequest(any());
        IotCloudHelper cloudHelper = new IotCloudHelper();
        final IotCloudResponse response = cloudHelper.sendHttpRequest(mockConnectionManager, null, IOT_CREDENTIALS_PATH,
                CredentialRequestHandler.IOT_CREDENTIALS_HTTP_VERB, null);
        assertArrayEquals(CLOUD_RESPONSE, response.getResponseBody());
        assertEquals(STATUS_CODE, response.getStatusCode());
    }

    @Test
    void GIVEN_valid_creds_WHEN_send_request_called_with_body_THEN_success() throws Exception {
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        when(mockConnectionManager.getClient()).thenReturn(mockClient);
        when(mockConnectionManager.getURI()).thenReturn(HOST);
        ExecutableHttpRequest requestMock = mock(ExecutableHttpRequest.class);
        when(requestMock.call()).thenReturn(
                HttpExecuteResponse.builder().response(SdkHttpResponse.builder().statusCode(STATUS_CODE).build())
                        .responseBody(AbortableInputStream.create(new ByteArrayInputStream(CLOUD_RESPONSE))).build());

        doReturn(requestMock).when(mockClient).prepareRequest(any());
        IotCloudHelper cloudHelper = new IotCloudHelper();
        final byte[] creds = cloudHelper.sendHttpRequest(mockConnectionManager, null, IOT_CREDENTIALS_PATH,
                CredentialRequestHandler.IOT_CREDENTIALS_HTTP_VERB, body).getResponseBody();
        assertArrayEquals(CLOUD_RESPONSE, creds);
    }

    @Test
    void GIVEN_error_code_once_WHEN_send_request_called_THEN_retry_and_success() throws Exception {
        when(mockConnectionManager.getClient()).thenReturn(mockClient);
        when(mockConnectionManager.getURI()).thenReturn(HOST);
        ExecutableHttpRequest requestMock = mock(ExecutableHttpRequest.class);
        when(requestMock.call()).thenThrow(IOException.class).thenReturn(
                HttpExecuteResponse.builder().response(SdkHttpResponse.builder().statusCode(STATUS_CODE).build())
                        .responseBody(AbortableInputStream.create(new ByteArrayInputStream(CLOUD_RESPONSE))).build());

        doReturn(requestMock).when(mockClient).prepareRequest(any());
        IotCloudHelper cloudHelper = new IotCloudHelper();
        final IotCloudResponse response = cloudHelper.sendHttpRequest(mockConnectionManager, null, IOT_CREDENTIALS_PATH,
                CredentialRequestHandler.IOT_CREDENTIALS_HTTP_VERB, null);
        assertArrayEquals(CLOUD_RESPONSE, response.getResponseBody());
        assertEquals(STATUS_CODE, response.getStatusCode());
    }

    @Test
    void GIVEN_error_code_once_WHEN_client_and_endpoint_null_THEN_unsuccessful() throws Exception {
        when(mockConnectionManager.getURI()).thenThrow(new DeviceConfigurationException("Credentials endpoint not "
                + "configured"));
        IotCloudHelper cloudHelper = new IotCloudHelper();
        assertThrows(DeviceConfigurationException.class, () -> cloudHelper.sendHttpRequest(mockConnectionManager, null,
                IOT_CREDENTIALS_PATH,
                CredentialRequestHandler.IOT_CREDENTIALS_HTTP_VERB, null));
    }
}
