/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.tes;

import com.aws.iot.evergreen.deployment.exceptions.AWSIotException;
import com.aws.iot.evergreen.iot.IotCloudHelper;
import com.aws.iot.evergreen.iot.IotConnectionManager;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    public static final int TIME_BEFORE_CACHE_EXPIRE_IN_MIN = 5;

    private final String iotCredentialsPath;

    private final IotCloudHelper iotCloudHelper;

    private final IotConnectionManager iotConnectionManager;

    private Clock clock = Clock.systemUTC();

    private final Map<String, TESCache> tesCache = new HashMap<>();

    private static class TESCache {
        private byte[] credentials;
        private int responseCode;
        private Instant expiry;
    }

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
        this.tesCache.put(this.iotCredentialsPath, new TESCache());
        this.tesCache.get(iotCredentialsPath).expiry = Instant.now(clock);
    }

    @Override
    public void handle(final HttpExchange exchange) throws IOException {
        final byte[] credentials = getCredentials();
        exchange.sendResponseHeaders(tesCache.get(iotCredentialsPath).responseCode, credentials.length);
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

        if (areCredentialsValid()) {
            response = tesCache.get(iotCredentialsPath).credentials;
            return response;
        }
        
        Instant newExpiry = tesCache.get(iotCredentialsPath).expiry;

        try {
            final String credentials = iotCloudHelper
                    .sendHttpRequest(iotConnectionManager, iotCredentialsPath, IOT_CREDENTIALS_HTTP_VERB, null)
                    .toString();

            try {
                response = translateToAwsSdkFormat(credentials);
                String expiryString = parseExpiryFromResponse(credentials);
                Instant expiry = Instant.parse(expiryString);

                if (expiry.isBefore(Instant.now(clock))) {
                    String responseString = "TES responded with expired credentials: " + credentials;
                    response = responseString.getBytes(StandardCharsets.UTF_8);
                    tesCache.get(iotCredentialsPath).responseCode = HttpURLConnection.HTTP_INTERNAL_ERROR;
                    LOGGER.error("Unable to cache expired credentials which expired at {}", expiry.toString());
                } else {
                    newExpiry = expiry.minus(Duration.ofMinutes(TIME_BEFORE_CACHE_EXPIRE_IN_MIN));
                    tesCache.get(iotCredentialsPath).responseCode = HttpURLConnection.HTTP_OK;

                    if (newExpiry.isBefore(Instant.now(clock))) {
                        LOGGER.warn("Can't cache credentials as new credentials {} will expire in less than {} minutes",
                                expiry.toString(),
                                TIME_BEFORE_CACHE_EXPIRE_IN_MIN);
                    } else {
                        LOGGER.info("Received IAM credentials that will be cached until {}", newExpiry.toString());
                    }
                }
            } catch (AWSIotException e) {
                String responseString = "Bad TES response: " + credentials;
                response = responseString.getBytes(StandardCharsets.UTF_8);
                tesCache.get(iotCredentialsPath).responseCode = HttpURLConnection.HTTP_INTERNAL_ERROR;
                LOGGER.error("Unable to parse response body", e);
            }
        } catch (AWSIotException e) {
            LOGGER.error("Encountered error while fetching credentials", e);
        }

        tesCache.get(iotCredentialsPath).expiry = newExpiry;
        tesCache.get(iotCredentialsPath).credentials = response;

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

    private String parseExpiryFromResponse(final String credentials) throws AWSIotException {
        try {
            JsonNode jsonNode = OBJECT_MAPPER.readTree(credentials).get(CREDENTIALS_UPSTREAM_STR);
            return jsonNode.get(EXPIRATION_UPSTREAM_STR).asText();
        } catch (JsonProcessingException e) {
            LOGGER.error("Received malformed credential input", e);
            throw new AWSIotException(e);
        }
    }

    private boolean areCredentialsValid() {
        Instant now = Instant.now(clock);
        return now.isBefore(tesCache.get(iotCredentialsPath).expiry);
    }

    /**
     * Inject clock for controlled testing.
     *
     * @param clock fixed time clock
     */
    public void setClock(Clock clock) {
        this.clock = clock;
    }
}
