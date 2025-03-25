/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.iot;

import com.aws.greengrass.deployment.exceptions.AWSIotException;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.iot.model.IotCloudResponse;
import com.aws.greengrass.util.BaseRetryableAccessor;
import com.aws.greengrass.util.CrashableSupplier;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.exceptions.TLSAuthException;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.utils.IoUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import javax.inject.Singleton;


@Singleton
@NoArgsConstructor
public class IotCloudHelper {
    private static final String HTTP_HEADER_THING_NAME = "x-amzn-iot-thingname";
    // TODO: [P41179510]: User configurable network timeout settings
    // Max wait time for device to receive HTTP response from IOT CLOUD
    private static final int RETRY_COUNT = 3;
    private static final int BACKOFF_MILLIS = 200;

    /**
     * Sends Http request to Iot Cloud.
     *
     * @param connManager underlying connection manager to use for sending requests
     * @param thingName   IoT Thing Name
     * @param path        Http url to query
     * @param verb        Http verb for the request
     * @param body        Http body for the request
     * @return Http response corresponding to http request for path
     * @throws AWSIotException when unable to send the request successfully
     * @throws TLSAuthException when unable to configure the client with mTLS
     */
    public IotCloudResponse sendHttpRequest(final IotConnectionManager connManager, String thingName, final String path,
                                            final String verb, final byte[] body)
                                            throws AWSIotException, TLSAuthException {
        URI uri = null;
        try {
            uri = connManager.getURI();
        } catch (DeviceConfigurationException e) {
            throw new AWSIotException(e);
        }

        SdkHttpRequest.Builder innerRequestBuilder = SdkHttpRequest.builder().method(SdkHttpMethod.fromValue(verb));

        // If the path is actually a full URI, then treat it as such
        if (path.startsWith("https://")) {
            uri = URI.create(path);
            innerRequestBuilder.uri(uri);
        } else {
            innerRequestBuilder.uri(uri).encodedPath(path);
        }

        if (Utils.isNotEmpty(thingName)) {
            innerRequestBuilder.appendHeader(HTTP_HEADER_THING_NAME, thingName);
        }

        ExecutableHttpRequest request = connManager.getClient().prepareRequest(HttpExecuteRequest.builder()
                .contentStreamProvider(body == null ? null : () -> new ByteArrayInputStream(body))
                .request(innerRequestBuilder.build()).build());

        BaseRetryableAccessor accessor = new BaseRetryableAccessor();
        CrashableSupplier<IotCloudResponse, AWSIotException> getHttpResponse = () -> getHttpResponse(request);
        return accessor.retry(RETRY_COUNT, BACKOFF_MILLIS, getHttpResponse,
                new HashSet<>(Collections.singletonList(AWSIotException.class)));
    }

    private IotCloudResponse getHttpResponse(ExecutableHttpRequest request) throws AWSIotException {
        final IotCloudResponse response = new IotCloudResponse();
        try {
            HttpExecuteResponse httpResponse = request.call();
            response.setStatusCode(httpResponse.httpResponse().statusCode());
            try (AbortableInputStream bodyStream = httpResponse.responseBody()
                    .orElseThrow(() -> new AWSIotException("No response body"))) {
                response.setResponseBody(IoUtils.toByteArray(bodyStream));
            }
        } catch (IOException e) {
            throw new AWSIotException("Unable to get response", e);
        }
        return response;
    }
}
