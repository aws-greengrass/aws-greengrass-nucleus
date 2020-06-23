/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.tes;

import com.aws.iot.evergreen.deployment.exceptions.AWSIotException;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import software.amazon.awssdk.crt.auth.credentials.Credentials;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class CredentialRequestHandler implements HttpHandler {
    private static final Logger LOGGER = LogManager.getLogger(CredentialRequestHandler.class);
    public static final String IOT_CREDENTIALS_HTTP_VERB = "GET";
    private static final String ACCESS_KEY_DOWNSTREAM_STR = "AccessKeyId";
    private static final String SECRET_ACCESS_DOWNSTREAM_STR = "SecretAccessKey";
    private static final String SESSION_TOKEN_DOWNSTREAM_STR = "Token";
    private static final String EXPIRATION_DOWNSTREAM_STR = "Expiration";
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    public static final String DATE_PATTERN = "yyyy-MM-dd'T'HH:mm:ss'Z'";
    private final CredentialsProviderBuilder credentialsProviderBuilder;

    /**
     * Constructor.
     * @param iotRoleAlias Iot role alias configured by the customer in AWS account.
     * @param credentialsProviderBuilder {@link CredentialsProviderBuilder} credentials provider builder
     */
    public CredentialRequestHandler(final String iotRoleAlias,
                                    final CredentialsProviderBuilder credentialsProviderBuilder) {
        this.credentialsProviderBuilder = credentialsProviderBuilder;
        this.credentialsProviderBuilder.withRoleAlias(iotRoleAlias);
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
            final Credentials credentials = credentialsProviderBuilder.getCredentials();
            final Date expiration = credentialsProviderBuilder.getCredentialsExpiration();
            response = translateToAwsSdkFormat(credentials, expiration);
        } catch (AWSIotException e) {
            // TODO: Generate 4xx, 5xx responses for all error scnearios
            LOGGER.error("Encountered error while fetching credentials", e);
        }
        return response;
    }

    private byte[] translateToAwsSdkFormat(final Credentials credentials, final Date expiration)
            throws AWSIotException {
        try {
            final SimpleDateFormat alternateIso8601DateFormat = new SimpleDateFormat(DATE_PATTERN, Locale.getDefault());
            String formattedExpiration = alternateIso8601DateFormat.format(expiration);
            Map<String, String> response = new HashMap<>();
            response.put(ACCESS_KEY_DOWNSTREAM_STR,
                    new String(credentials.getAccessKeyId(), StandardCharsets.UTF_8));
            response.put(SECRET_ACCESS_DOWNSTREAM_STR,
                    new String(credentials.getSecretAccessKey(), StandardCharsets.UTF_8));
            response.put(SESSION_TOKEN_DOWNSTREAM_STR,
                    new String(credentials.getSessionToken(), StandardCharsets.UTF_8));
            response.put(EXPIRATION_DOWNSTREAM_STR, formattedExpiration);
            return OBJECT_MAPPER.writeValueAsBytes(response);
        } catch (IOException e) {
            LOGGER.error("Received malformed credential input", e);
            throw new AWSIotException(e);
        }
    }
}