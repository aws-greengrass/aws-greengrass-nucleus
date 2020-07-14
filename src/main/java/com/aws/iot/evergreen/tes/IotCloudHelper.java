/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.tes;

import com.aws.iot.evergreen.deployment.exceptions.AWSIotException;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.crt.http.HttpClientConnection;
import software.amazon.awssdk.crt.http.HttpHeader;
import software.amazon.awssdk.crt.http.HttpRequest;
import software.amazon.awssdk.crt.http.HttpRequestBodyStream;
import software.amazon.awssdk.crt.http.HttpStream;
import software.amazon.awssdk.crt.http.HttpStreamResponseHandler;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
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
    // TODO: revisit all timeout values
    // Max wait time for device to receive HTTP response from IOT CLOUD
    private static final long TIMEOUT_FOR_RESPONSE_FROM_IOT_CLOUD_SECONDS = (long) Duration.ofSeconds(30).getSeconds();

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
    public String sendHttpRequest(final IotConnectionManager connManager, final String path, final String verb,
                                  final byte[] body) throws AWSIotException {
        final HttpHeader[] headers = {new HttpHeader("host", connManager.getHost())};

        final HttpRequestBodyStream httpRequestBodyStream = body == null ? null : createHttpRequestBodyStream(body);
        final HttpRequest request = new HttpRequest(verb, path, headers, httpRequestBodyStream);

        String response = "";
        try (HttpClientConnection conn = connManager.getConnection()) {
            // TODO: Make it exponential backoff, create backoff util for common use.
            int numAttempts = 0;
            do {
                numAttempts++;
                response = getHttpResponse(conn, request);
            } while (numAttempts < 1);
        }
        return response;
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
                                                            StringBuilder responseBody) {
        return new HttpStreamResponseHandler() {
            @Override
            public void onResponseHeaders(HttpStream httpStream, int i, int i1, HttpHeader[] httpHeaders) {
                Arrays.stream(httpHeaders).forEach(header -> {
                    responseHeaders.put(header.getName(), header.getValue());
                });
            }

            @Override
            public int onResponseBody(HttpStream stream, byte[] bodyBytes) {
                responseBody.append(new String(bodyBytes, StandardCharsets.UTF_8));
                return bodyBytes.length;
            }

            @Override
            public void onResponseComplete(HttpStream httpStream, int errorCode) {
                reqCompleted.complete(errorCode);
                httpStream.close();
            }
        };
    }

    private String getHttpResponse(HttpClientConnection conn, HttpRequest request) throws AWSIotException {
        final CompletableFuture<Integer> reqCompleted = new CompletableFuture<>();
        final Map<String, String> responseHeaders = new HashMap<>();
        final StringBuilder responseBody = new StringBuilder();
        conn.makeRequest(request, createResponseHandler(reqCompleted, responseHeaders, responseBody)).activate();
        // Give the request up to N seconds to complete, otherwise throw a TimeoutException
        // TODO: handle 4xx,5xx, timeouts and connection issues
        try {
            int error = reqCompleted.get(TIMEOUT_FOR_RESPONSE_FROM_IOT_CLOUD_SECONDS, TimeUnit.SECONDS);
            if (error != 0) {
                throw new AWSIotException(String.format("Error %s(%d); RequestId: %s", HTTP_HEADER_ERROR_TYPE, error,
                        HTTP_HEADER_REQUEST_ID));
            }
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            LOGGER.error("Http request failed with error", e);
            throw new AWSIotException(e);
        }
        return responseBody.toString();
    }
}
