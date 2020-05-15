/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.tes;

import com.aws.iot.evergreen.deployment.exceptions.AWSIotException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

public class CredentialRequestHandler implements HttpHandler {

    public static final String IOT_CREDENTIALS_PATH = "/greengrass/assumeRoleForGroup";
    public static final String IOT_CREDENTIALS_HTTP_VERB = "GET";

    private final IotCloudHelper iotCloudHelper;

    private final IotConnectionManager iotConnectionManager;

    /**
     * Constructor.
     * @param cloudHelper {@link IotCloudHelper} for making http requests to cloud.
     * @param connectionManager {@link IotConnectionManager} underlying connection manager for cloud.
     */
    public CredentialRequestHandler(final IotCloudHelper cloudHelper, final IotConnectionManager connectionManager) {
        this.iotCloudHelper = cloudHelper;
        this.iotConnectionManager = connectionManager;
    }

    @Override
    public void handle(final HttpExchange exchange) throws IOException {
        final byte[] credentials = getCredentials();
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, credentials.length);
        exchange.getResponseBody().write(credentials);
        exchange.close();
    }

    /**
     * API for kernel to directly fetch credentials from TES instead of using HTTP server.
     * @return AWS credentials from cloud.
     */
    public byte[] getCredentials() {
        String credentials = "--alive--";
        // TODO: Add cache
        try {
            credentials = iotCloudHelper.sendHttpRequest(iotConnectionManager,
                    IOT_CREDENTIALS_PATH,
                    IOT_CREDENTIALS_HTTP_VERB);
        } catch (AWSIotException e) {
            // TODO: Generate 4xx, 5xx responses for all error scnearios
        }
        return credentials.getBytes(StandardCharsets.UTF_8);
    }
}