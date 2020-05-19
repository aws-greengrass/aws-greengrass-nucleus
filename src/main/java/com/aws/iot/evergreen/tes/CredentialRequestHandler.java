/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.tes;

import com.aws.iot.evergreen.deployment.exceptions.AWSIotException;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.HashMap;
import java.util.Map;

public class CredentialRequestHandler implements HttpHandler {
    private static final Logger LOGGER = LogManager.getLogger(CredentialRequestHandler.class);
    public static final String IOT_CREDENTIALS_HTTP_VERB = "GET";
    private static final String CREDENTIALS_UPSTREAM_STR = "credentials";
    private static final String ACCESS_KEY_UPSTREAM_STR = "accessKeyId";
    private static final String ACCESS_KEY_DOWNSTREAM_STR = "AccessKeyId";
    private static final String SECRET_ACCESS_UPSTREAM_STR = "secretAccessKey";
    private static final String SECRET_ACCESS_DOWNSTREAM_STR = "SecretAccessKey";
    private static final String SESSION_TOKEN_UPSTREAM_STR = "sessionToken";
    private static final String SESSION_TOKEN_DOWNSTREAM_STR = "Token";
    private static final String EXPIRATION_UPSTREAM_STR = "expiration";
    private static final String EXPIRATION_DOWNSTREAM_STR = "Expiration";
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private final String iotCredentialsPath;

    private final IotCloudHelper iotCloudHelper;

    private final IotConnectionManager iotConnectionManager;

    /**
     * Constructor.
     * @param iotRoleAlias Iot role alias configured by the customer in AWS account.
     * @param cloudHelper {@link IotCloudHelper} for making http requests to cloud.
     * @param connectionManager {@link IotConnectionManager} underlying connection manager for cloud.
     */
    public CredentialRequestHandler(final String iotRoleAlias,
                                    final IotCloudHelper cloudHelper,
                                    final IotConnectionManager connectionManager) {
        this.iotCredentialsPath = "/role-aliases/" + iotRoleAlias + "/credentials";
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
        byte[] response = {};
        LOGGER.debug("Got request for credentials");
        // TODO: Add cache
        try {
            final String credentials = iotCloudHelper.sendHttpRequest(iotConnectionManager,
                    iotCredentialsPath,
                    IOT_CREDENTIALS_HTTP_VERB);
            response = translateToAwsSdkFormat(credentials);
        } catch (AWSIotException e) {
            // TODO: Generate 4xx, 5xx responses for all error scnearios
            LOGGER.error("Encountered error while fetching credentials", e);
        }
        return response;
    }

    private byte[] translateToAwsSdkFormat(final String credentials) throws AWSIotException {
        try {
            JsonNode jsonNode = OBJECT_MAPPER.readTree(credentials).get(CREDENTIALS_UPSTREAM_STR);
            Map<String, String> response = new HashMap<>();
            response.put(ACCESS_KEY_DOWNSTREAM_STR, jsonNode.get(ACCESS_KEY_UPSTREAM_STR).asText());
            response.put(SECRET_ACCESS_DOWNSTREAM_STR, jsonNode.get(SECRET_ACCESS_UPSTREAM_STR).asText());
            response.put(SESSION_TOKEN_DOWNSTREAM_STR, jsonNode.get(SESSION_TOKEN_UPSTREAM_STR).asText());
            response.put(EXPIRATION_DOWNSTREAM_STR, jsonNode.get(EXPIRATION_UPSTREAM_STR).asText());
            return OBJECT_MAPPER.writeValueAsBytes(response);
        } catch (JsonProcessingException e) {
            LOGGER.error("Received malformed credential input", e);
            throw new AWSIotException(e);
        }
    }
}