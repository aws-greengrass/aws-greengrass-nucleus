/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.tes;

import com.aws.iot.evergreen.deployment.exceptions.AWSIotException;
import com.aws.iot.evergreen.iot.IotCloudHelper;
import com.aws.iot.evergreen.iot.IotConnectionManager;
import com.aws.iot.evergreen.iot.model.IotCloudResponse;
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
    public static final int CLOUD_4XX_ERROR_CACHE_IN_MIN = 2;
    public static final int CLOUD_5XX_ERROR_CACHE_IN_MIN = 1;
    public static final int UNKNOWN_ERROR_CACHE_IN_MIN = 5;

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
     *
     * @param iotRoleAlias      Iot role alias configured by the customer in AWS account.
     * @param cloudHelper       {@link IotCloudHelper} for making http requests to cloud.
     * @param connectionManager {@link IotConnectionManager} underlying connection manager for cloud.
     */
    public CredentialRequestHandler(final String iotRoleAlias, final IotCloudHelper cloudHelper,
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
     *
     * @return AWS credentials from cloud.
     */
    public byte[] getCredentials() {
        byte[] response;
        LOGGER.debug("Got request for credentials");

        if (areCredentialsValid()) {
            response = tesCache.get(iotCredentialsPath).credentials;
            return response;
        }

        // Get new credentials from cloud
        LOGGER.info("IAM credentials not found in cache or already expired. Fetching new ones from TES");
        Instant newExpiry = tesCache.get(iotCredentialsPath).expiry;

        try {
            final IotCloudResponse cloudResponse = iotCloudHelper
                    .sendHttpRequest(iotConnectionManager, iotCredentialsPath, IOT_CREDENTIALS_HTTP_VERB, null);
            final String credentials = cloudResponse.toString();
            final int cloudResponseCode = cloudResponse.getStatusCode();

            if (cloudResponseCode == 0) {
                // Client errors should expire immediately
                String responseString = "Failed to get credentials from TES";
                response = responseString.getBytes(StandardCharsets.UTF_8);
                newExpiry = Instant.now(clock);
                tesCache.get(iotCredentialsPath).responseCode = HttpURLConnection.HTTP_INTERNAL_ERROR;
            } else if (cloudResponseCode == HttpURLConnection.HTTP_OK) {
                // Get response successfully, cache credentials according to expiry in response
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
                            LOGGER.warn(
                                    "Can't cache credentials as new credentials {} will expire in less than {} minutes",
                                    expiry.toString(), TIME_BEFORE_CACHE_EXPIRE_IN_MIN);
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
            } else {
                // Cloud errors should be cached
                String responseString =
                        String.format("TES responded with status code: %d", cloudResponseCode, credentials);
                response = responseString.getBytes(StandardCharsets.UTF_8);
                newExpiry = getExpiryPolicyForErr(cloudResponseCode);
                tesCache.get(iotCredentialsPath).responseCode = cloudResponseCode;
            }
        } catch (AWSIotException e) {
            // Http connection error
            String responseString = "Failed to get connection:" + e.getMessage();
            response = responseString.getBytes(StandardCharsets.UTF_8);
            tesCache.get(iotCredentialsPath).responseCode = HttpURLConnection.HTTP_INTERNAL_ERROR;
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

    private Instant getExpiryPolicyForErr(int statusCode) {
        int expiryTime = UNKNOWN_ERROR_CACHE_IN_MIN; // In case of unrecognized cloud errors, back off
        // Add caching Time-To-Live (TTL) for TES cloud errors
        if (statusCode >= 400 && statusCode < 500) {
            // 4xx retries are only meaningful unless a user action has been adopted, TTL should be longer
            expiryTime = CLOUD_4XX_ERROR_CACHE_IN_MIN;
        } else if (statusCode >= 500 && statusCode < 600) {
            // 5xx could be a temporary cloud unavailability, TTL should be shorter
            expiryTime = CLOUD_5XX_ERROR_CACHE_IN_MIN;
        }
        return Instant.now(clock).plus(Duration.ofMinutes(expiryTime));
    }

    /**
     * Check if the cached credentials are valid.
     *
     * @return if the cached credentials are valid.
     */
    public boolean areCredentialsValid() {
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
