/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.iot;

import com.aws.greengrass.deployment.exceptions.AWSIotException;
import com.aws.greengrass.iot.model.IotCloudResponse;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.BaseRetryableAccessor;
import com.aws.greengrass.util.CrashableSupplier;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.crt.http.HttpClientConnection;
import software.amazon.awssdk.crt.http.HttpHeader;
import software.amazon.awssdk.crt.http.HttpRequest;
import software.amazon.awssdk.crt.http.HttpRequestBodyStream;
import software.amazon.awssdk.crt.http.HttpStream;
import software.amazon.awssdk.crt.http.HttpStreamResponseHandler;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.inject.Singleton;


@Singleton
@NoArgsConstructor
public class IotCloudHelper {
    private static final Logger LOGGER = LogManager.getLogger(IotCloudHelper.class);
    private static final String HTTP_HEADER_REQUEST_ID = "x-amzn-RequestId";
    private static final String HTTP_HEADER_ERROR_TYPE = "x-amzn-ErrorType";
    // TODO: [P41179510]: User configurable network timeout settings
    // Max wait time for device to receive HTTP response from IOT CLOUD
    private static final long TIMEOUT_FOR_RESPONSE_FROM_IOT_CLOUD_SECONDS = Duration.ofSeconds(30).getSeconds();
    private static final int RETRY_COUNT = 3;
    private static final int BACKOFF_MILLIS = 200;

    /**
     * Sends Http request to Iot Cloud.
     *
     * @param connManager underlying connection manager to use for sending requests
     * @param path        Http url to query
     * @param verb        Http verb for the request
     * @param body        Http body for the request
     * @return Http response corresponding to http request for path
     * @throws AWSIotException when unable to send the request successfully
     */
    public IotCloudResponse sendHttpRequest(final IotConnectionManager connManager, final String path,
                                            final String verb, final byte[] body) throws AWSIotException {
        final HttpHeader[] headers = {new HttpHeader("host", connManager.getHost())};

        final HttpRequestBodyStream httpRequestBodyStream = body == null ? null : createHttpRequestBodyStream(body);
        final HttpRequest request = new HttpRequest(verb, path, headers, httpRequestBodyStream);

        try (HttpClientConnection conn = connManager.getConnection()) {
            BaseRetryableAccessor accessor = new BaseRetryableAccessor();
            CrashableSupplier<IotCloudResponse, AWSIotException> getHttpResponse = () -> getHttpResponse(conn, request);
            return accessor.retry(RETRY_COUNT, BACKOFF_MILLIS, getHttpResponse,
                    new HashSet<>(Arrays.asList(AWSIotException.class)));
        }
    }

    private HttpRequestBodyStream createHttpRequestBodyStream(byte[] bytes) {
        return new HttpRequestBodyStream() {
            @Override
            public boolean sendRequestBody(ByteBuffer bodyBytesOut) {
                bodyBytesOut.put(bytes);
                return true;
            }

            @Override
            public boolean resetPosition() {
                return true;
            }
        };
    }

    private HttpStreamResponseHandler createResponseHandler(CompletableFuture<Integer> reqCompleted,
                                                            Map<String, String> responseHeaders,
                                                            IotCloudResponse response) {
        return new HttpStreamResponseHandler() {
            @Override
            public void onResponseHeaders(HttpStream httpStream, int i, int i1, HttpHeader[] httpHeaders) {
                Arrays.stream(httpHeaders).forEach(header -> responseHeaders.put(header.getName(), header.getValue()));
            }

            @Override
            public int onResponseBody(HttpStream stream, byte[] bodyBytes) {
                ByteArrayOutputStream responseByteArray = new ByteArrayOutputStream();
                if (response.getResponseBody() != null) {
                    responseByteArray.write(response.getResponseBody(), 0, response.getResponseBody().length);
                }
                responseByteArray.write(bodyBytes, 0, bodyBytes.length);
                response.setResponseBody(responseByteArray.toByteArray());
                return bodyBytes.length;
            }

            @Override
            public void onResponseComplete(HttpStream httpStream, int errorCode) {
                response.setStatusCode(httpStream.getResponseStatusCode());
                httpStream.close();
                reqCompleted.complete(errorCode);
            }
        };
    }

    private IotCloudResponse getHttpResponse(HttpClientConnection conn, HttpRequest request) throws AWSIotException {
        final CompletableFuture<Integer> reqCompleted = new CompletableFuture<>();
        final Map<String, String> responseHeaders = new HashMap<>();
        final IotCloudResponse response = new IotCloudResponse();
        // Give the request up to N seconds to complete, otherwise throw a TimeoutException
        try {
            conn.makeRequest(request, createResponseHandler(reqCompleted, responseHeaders, response)).activate();
            int error = reqCompleted.get(TIMEOUT_FOR_RESPONSE_FROM_IOT_CLOUD_SECONDS, TimeUnit.SECONDS);
            if (error != 0) {
                throw new AWSIotException(String.format("Error %s(%d); RequestId: %s", HTTP_HEADER_ERROR_TYPE, error,
                        HTTP_HEADER_REQUEST_ID));
            }
            return response;
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            LOGGER.error("Http request failed with error", e);
            throw new AWSIotException(e);
        }
    }
}
