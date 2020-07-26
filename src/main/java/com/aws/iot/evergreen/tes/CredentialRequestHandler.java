/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.tes;

import com.aws.iot.evergreen.auth.AuthorizationHandler;
import com.aws.iot.evergreen.auth.Permission;
import com.aws.iot.evergreen.auth.exceptions.AuthorizationException;
import com.aws.iot.evergreen.deployment.exceptions.AWSIotException;
import com.aws.iot.evergreen.iot.IotCloudHelper;
import com.aws.iot.evergreen.iot.IotConnectionManager;
import com.aws.iot.evergreen.ipc.AuthenticationHandler;
import com.aws.iot.evergreen.ipc.exceptions.UnauthenticatedException;
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
import javax.inject.Inject;

public class CredentialRequestHandler implements HttpHandler {
    private static final Logger LOGGER = LogManager.getLogger(CredentialRequestHandler.class);
    public static final String AUTH_HEADER = "Authorization";
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
    private String iotCredentialsPath;

    private final IotCloudHelper iotCloudHelper;
    private final AuthenticationHandler authNHandler;
    private final AuthorizationHandler authZHandler;

    private final IotConnectionManager iotConnectionManager;

    /**
     * Constructor.
     * @param cloudHelper {@link IotCloudHelper} for making http requests to cloud.
     * @param connectionManager {@link IotConnectionManager} underlying connection manager for cloud.
     * @param authenticationHandler {@link AuthenticationHandler} authN module for authenticating requests.
     * @param authZHandler {@link AuthorizationHandler} authZ module for authorizing requests.
     */
    @Inject
    public CredentialRequestHandler(final IotCloudHelper cloudHelper,
                                    final IotConnectionManager connectionManager,
                                    final AuthenticationHandler authenticationHandler,
                                    final AuthorizationHandler authZHandler) {
        this.iotCloudHelper = cloudHelper;
        this.iotConnectionManager = connectionManager;
        this.authNHandler = authenticationHandler;
        this.authZHandler = authZHandler;
    }

    /**
     * Set the role alias.
     * @param iotRoleAlias  Iot role alias configured by the customer in AWS account.
     */
    public void setIotCredentialsPath(String iotRoleAlias) {
        this.iotCredentialsPath = "/role-aliases/" + iotRoleAlias + "/credentials";
    }

    @Override
    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    public void handle(final HttpExchange exchange) throws IOException {
        try {
            doAuth(exchange);
            final byte[] credentials = getCredentials();
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, credentials.length);
            exchange.getResponseBody().write(credentials);
            exchange.close();
        } catch (AuthorizationException e) {
            LOGGER.atInfo().log("Request is not authorized");
            generateError(exchange, HttpURLConnection.HTTP_FORBIDDEN);
        } catch (UnauthenticatedException e) {
            LOGGER.atInfo().log("Request denied due to invalid token");
            generateError(exchange, HttpURLConnection.HTTP_FORBIDDEN);
        } catch (Throwable e) {
            // Dont let the server crash, swallow problems with a 5xx
            LOGGER.atInfo().log("Request failed due to e", e);
            generateError(exchange, HttpURLConnection.HTTP_FORBIDDEN);
        }
    }

    private void generateError(HttpExchange exchange, int statusCode) throws IOException {
        exchange.sendResponseHeaders(statusCode, -1);
        exchange.close();
    }

    /**
     * API for kernel to directly fetch credentials from TES instead of using HTTP server.
     * Note that it bypassed authN/authZ, so should be used carefully.
     * @return AWS credentials from cloud.
     */
    public byte[] getCredentials() {
        byte[] response = {};
        LOGGER.debug("Got request for credentials, querying iot {}", iotCredentialsPath);
        // TODO: Add cache
        try {
            final String credentials = iotCloudHelper.sendHttpRequest(iotConnectionManager,
                    iotCredentialsPath,
                    IOT_CREDENTIALS_HTTP_VERB, null);
            response = translateToAwsSdkFormat(credentials);
        } catch (AWSIotException e) {
            // TODO: Generate 4xx, 5xx responses for all error scenarios
            LOGGER.error("Encountered error while fetching credentials", e);
        }
        return response;
    }

    private byte[] translateToAwsSdkFormat(final String credentials) throws AWSIotException {
        try {
            // TODO: Validate if lowercase lookup can make this simpler
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

    private void doAuth(final HttpExchange exchange) throws UnauthenticatedException, AuthorizationException {
        // if header is not present, then authToken would be null and authNhandler would throw
        String authNToken = exchange.getRequestHeaders().getFirst(AUTH_HEADER);
        String clientService = authNHandler.doAuthentication(authNToken);
        authZHandler.isAuthorized(
                TokenExchangeService.TOKEN_EXCHANGE_SERVICE_TOPICS,
                Permission.builder()
                        .principal(clientService)
                        .operation(TokenExchangeService.AUTHZ_TES_OPERATION)
                        .resource(null)
                        .build());
    }
}