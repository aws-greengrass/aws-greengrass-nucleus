/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.tes;

import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.Permission;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.AWSIotException;
import com.aws.greengrass.iot.IotCloudHelper;
import com.aws.greengrass.iot.IotConnectionManager;
import com.aws.greengrass.iot.model.IotCloudResponse;
import com.aws.greengrass.ipc.AuthenticationHandler;
import com.aws.greengrass.ipc.exceptions.UnauthenticatedException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.DefaultConcurrentHashMap;
import com.aws.greengrass.util.LockFactory;
import com.aws.greengrass.util.LockScope;
import com.aws.greengrass.util.exceptions.TLSAuthException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import lombok.AccessLevel;
import lombok.Setter;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import javax.inject.Inject;

import static com.aws.greengrass.tes.HttpServerImpl.URL;

public class CredentialRequestHandler implements HttpHandler {
    private static final Logger LOGGER = LogManager.getLogger(CredentialRequestHandler.class);
    private static final String IOT_CRED_PATH_KEY = "iotCredentialsPath";
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
    public static final String AUTH_HEADER = "Authorization";
    public static final String IOT_CREDENTIALS_HTTP_VERB = "GET";
    public static final String SUPPORTED_REQUEST_VERB = "GET";
    public static final int TIME_BEFORE_CACHE_EXPIRE_IN_SEC = 300;
    public static final int CLOUD_4XX_ERROR_CACHE_IN_SEC = 120;
    public static final int CLOUD_5XX_ERROR_CACHE_IN_SEC = 60;
    public static final int UNKNOWN_ERROR_CACHE_IN_SEC = 300;

    private int cloud4xxErrorCacheInSec = CLOUD_4XX_ERROR_CACHE_IN_SEC;
    private int cloud5xxErrorCacheInSec = CLOUD_5XX_ERROR_CACHE_IN_SEC;
    private int unknownErrorCacheInSec = UNKNOWN_ERROR_CACHE_IN_SEC;

    private String iotCredentialsPath;

    private final IotCloudHelper iotCloudHelper;

    private final AuthenticationHandler authNHandler;

    private final AuthorizationHandler authZHandler;

    private final IotConnectionManager iotConnectionManager;

    private Clock clock = Clock.systemUTC();

    private final Map<String, TESCache> tesCache = new DefaultConcurrentHashMap<>(() -> {
        TESCache cache = new TESCache();
        cache.expiry = Instant.EPOCH;
        return cache;
    });
    @Setter(AccessLevel.PACKAGE)
    private String thingName;

    private static class TESCache {
        private byte[] credentials;
        private int responseCode;
        private Instant expiry;
        private final AtomicReference<CompletableFuture<Void>> future = new AtomicReference<>(null);
        private final Lock lock = LockFactory.newReentrantLock(this);
    }

    /**
     * Constructor.
     *
     * @param cloudHelper           {@link IotCloudHelper} for making http requests to cloud.
     * @param connectionManager     {@link IotConnectionManager} underlying connection manager for cloud.
     * @param authenticationHandler {@link AuthenticationHandler} authN module for authenticating requests.
     * @param authZHandler          {@link AuthorizationHandler} authZ module for authorizing requests.
     * @param deviceConfiguration   {@link DeviceConfiguration} for getting device configuration.
     */
    @Inject
    public CredentialRequestHandler(final IotCloudHelper cloudHelper, final IotConnectionManager connectionManager,
                                    final AuthenticationHandler authenticationHandler,
                                    final AuthorizationHandler authZHandler,
                                    final DeviceConfiguration deviceConfiguration) {
        this.iotCloudHelper = cloudHelper;
        this.iotConnectionManager = connectionManager;
        this.authNHandler = authenticationHandler;
        this.authZHandler = authZHandler;

        deviceConfiguration.getIotRoleAlias().subscribe((why, newv) -> {
            clearCache();
            setIotCredentialsPath(Coerce.toString(deviceConfiguration.getIotRoleAlias()));
        });
        deviceConfiguration.getThingName().subscribe((why, newv) -> {
            clearCache();
            setThingName(Coerce.toString(deviceConfiguration.getThingName()));
        });
        deviceConfiguration.getCertificateFilePath().subscribe((why, newv) -> clearCache());
        deviceConfiguration.getRootCAFilePath().subscribe((why, newv) -> clearCache());
        deviceConfiguration.getPrivateKeyFilePath().subscribe((why, newv) -> clearCache());
    }

    /**
     * Set the role alias.
     *
     * @param iotRoleAlias Iot role alias configured by the customer in AWS account.
     */
    void setIotCredentialsPath(String iotRoleAlias) {
        this.iotCredentialsPath = "/role-aliases/" + iotRoleAlias + "/credentials";
    }

    /**
     * Configure error cache settings for error responses.
     *
     * @param cloud4xxErrorCache error cache duration in seconds for 4xx errors.
     * @param cloud5xxErrorCache error cache duration in seconds for 5xx errors.
     * @param unknownErrorCache error cache duration in seconds for unknown errors.
     */
    public void configureCacheSettings(int cloud4xxErrorCache, int cloud5xxErrorCache, int unknownErrorCache) {
        this.cloud4xxErrorCacheInSec = cloud4xxErrorCache;
        this.cloud5xxErrorCacheInSec = cloud5xxErrorCache;
        this.unknownErrorCacheInSec = unknownErrorCache;
    }

    /**
     * Get current error cache configuration settings.
     *
     * @return Array containing error cache durations: [4xx, 5xx, unknown] in seconds.
     */
    public int[] getErrorCacheConfigSettings() {
        return new int[]{this.cloud4xxErrorCacheInSec, this.cloud5xxErrorCacheInSec, this.unknownErrorCacheInSec};
    }

    @Override
    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    public void handle(final HttpExchange exchange) throws IOException {
        try {
            if (!exchange.getRequestMethod().equals(SUPPORTED_REQUEST_VERB)) {
                LOGGER.atWarn().log("Unsupported http method for {}. GET is supported.", exchange.getRequestMethod());
                generateError(exchange, HttpURLConnection.HTTP_BAD_METHOD);
                return;
            }
            if (!exchange.getRequestURI().getPath().equals(URL)) {
                LOGGER.atWarn().log("Unexpected URI: {}.",
                        exchange.getRequestURI().getPath());
                generateError(exchange, HttpURLConnection.HTTP_BAD_REQUEST);
                return;
            }
            doAuth(exchange);
            final byte[] credentials = getCredentialsWithTimeout(30, TimeUnit.SECONDS);
            exchange.sendResponseHeaders(tesCache.get(iotCredentialsPath).responseCode, credentials.length);
            exchange.getResponseBody().write(credentials);
        } catch (AuthorizationException e) {
            LOGGER.atInfo().log("Request is not authorized");
            generateError(exchange, HttpURLConnection.HTTP_FORBIDDEN);
        } catch (UnauthenticatedException e) {
            LOGGER.atInfo().log("Request denied due to invalid token");
            generateError(exchange, HttpURLConnection.HTTP_FORBIDDEN);
        } catch (TimeoutException e) {
            LOGGER.atDebug().log("Client credential request timed out");
            generateError(exchange, HttpURLConnection.HTTP_GATEWAY_TIMEOUT);
        } catch (Throwable e) {
            // Broken pipe is ignorable; it just means that the client went away
            if ("Broken pipe".equalsIgnoreCase(e.getMessage())
                    || "An established connection was aborted by the software in your host machine".equalsIgnoreCase(
                    e.getMessage())) {
                LOGGER.atDebug().log("Client gave up before we could respond");
            } else {
                // Don't let the server crash, swallow problems with a 5xx
                LOGGER.atWarn().log("Request failed", e);
            }
            generateError(exchange, HttpURLConnection.HTTP_INTERNAL_ERROR);
        } finally {
            exchange.close();
        }
    }

    private byte[] getCredentialsWithTimeout(int timeout, TimeUnit timeUnit) throws TimeoutException {
        TESCache cacheEntry = tesCache.get(iotCredentialsPath);
        CompletableFuture<Void> future = null;
        try (LockScope ls = LockScope.lock(cacheEntry.lock)) {
            if (areCredentialsValid(cacheEntry)) {
                return cacheEntry.credentials;
            }
            CompletableFuture<Void> newFut = new CompletableFuture<>();
            // "take the lock" by immediately setting the future non-null while inside the sync block
            if (!cacheEntry.future.compareAndSet(null, newFut)) {
                future = cacheEntry.future.get();
            }
        }
        if (future != null) {
            LOGGER.atDebug().kv(IOT_CRED_PATH_KEY, iotCredentialsPath)
                    .log("IAM credentials not found in cache or already expired. A request to fetch new credentials "
                            + "is already ongoing, waiting for it to complete.");
            try {
                // block along with any other threads so we don't send multiple requests
                if (timeout == 0) {
                    future.get();
                } else {
                    future.get(timeout, timeUnit);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException ignore) {
                // We never complete the future exceptionally
            }
            return tesCache.get(iotCredentialsPath).credentials;
        }

        // Get new credentials from cloud
        LOGGER.atDebug().kv(IOT_CRED_PATH_KEY, iotCredentialsPath)
                .log("IAM credentials not found in cache or already expired. Fetching new ones from TES");
        return getCredentialsBypassCache();
    }

    private void generateError(HttpExchange exchange, int statusCode) throws IOException {
        exchange.sendResponseHeaders(statusCode, -1);
    }

    /**
     * API to get credentials while bypassing the caching layer.
     *
     * @return credentials
     */
    private byte[] getCredentialsBypassCache() {
        byte[] response;
        LOGGER.atDebug().kv(IOT_CRED_PATH_KEY, iotCredentialsPath).log("Got request for credentials, querying iot");

        TESCache cacheEntry = tesCache.get(iotCredentialsPath);
        // Use the future in order to prevent multiple concurrent requests for the same information.
        // If a request is already underway then it should simply wait on the existing future instead of making a
        // parallel call to the cloud.
        CompletableFuture<Void> future;
        try (LockScope ls = LockScope.lock(cacheEntry.lock)) {
            future = cacheEntry.future.get();
            if (future == null || future.isDone()) {
                future = new CompletableFuture<>();
                tesCache.get(iotCredentialsPath).future.set(future);
            }
        }
        Instant newExpiry = tesCache.get(iotCredentialsPath).expiry;

        try {
            final IotCloudResponse cloudResponse = iotCloudHelper
                    .sendHttpRequest(iotConnectionManager, thingName,
                            iotCredentialsPath, IOT_CREDENTIALS_HTTP_VERB, null);
            final String credentials = cloudResponse.toString();
            final int cloudResponseCode = cloudResponse.getStatusCode();
            LOGGER.atDebug().kv(IOT_CRED_PATH_KEY, iotCredentialsPath).kv("statusCode", cloudResponseCode)
                    .log("Received response from cloud: {}",
                            cloudResponseCode == 200 ? "response code 200, not logging credentials" : credentials);

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
                        String responseString = "TES responded with credentials that expired at " + expiry;
                        response = responseString.getBytes(StandardCharsets.UTF_8);
                        tesCache.get(iotCredentialsPath).responseCode = HttpURLConnection.HTTP_INTERNAL_ERROR;
                        LOGGER.atError().kv(IOT_CRED_PATH_KEY, iotCredentialsPath)
                                .log("Unable to cache expired credentials which expired at {}", expiry);
                    } else {
                        newExpiry = expiry.minus(Duration.ofSeconds(TIME_BEFORE_CACHE_EXPIRE_IN_SEC));
                        tesCache.get(iotCredentialsPath).responseCode = HttpURLConnection.HTTP_OK;

                        if (newExpiry.isBefore(Instant.now(clock))) {
                            LOGGER.atWarn().kv(IOT_CRED_PATH_KEY, iotCredentialsPath)
                                    .log("Can't cache credentials as new credentials {} will "
                                                    + "expire in less than {} seconds", expiry,
                                            TIME_BEFORE_CACHE_EXPIRE_IN_SEC);
                        } else {
                            LOGGER.atInfo().kv(IOT_CRED_PATH_KEY, iotCredentialsPath)
                                    .log("Received IAM credentials that will be cached until {}", newExpiry);
                        }
                    }
                } catch (AWSIotException e) {
                    String responseString = "Bad TES response: " + credentials;
                    response = responseString.getBytes(StandardCharsets.UTF_8);
                    tesCache.get(iotCredentialsPath).responseCode = HttpURLConnection.HTTP_INTERNAL_ERROR;
                    LOGGER.atError().kv(IOT_CRED_PATH_KEY, iotCredentialsPath).log("Unable to parse response body", e);
                }
            } else {
                // Cloud errors should be cached
                String responseString =
                        String.format("TES responded with status code: %d. Caching response. %s", cloudResponseCode,
                                credentials);
                response = responseString.getBytes(StandardCharsets.UTF_8);
                newExpiry = getExpiryPolicyForErr(cloudResponseCode);
                tesCache.get(iotCredentialsPath).responseCode = cloudResponseCode;
                LOGGER.atError().kv(IOT_CRED_PATH_KEY, iotCredentialsPath).log(responseString);
            }

            tesCache.get(iotCredentialsPath).expiry = newExpiry;
            tesCache.get(iotCredentialsPath).credentials = response;
        } catch (AWSIotException | TLSAuthException e) {
            // Http connection error should be cached to avoid excessive retries
            String responseString = "Failed to get connection";
            response = responseString.getBytes(StandardCharsets.UTF_8);
            // Use unknown error cache policy for SSL/TLS connection errors to prevent excessive retries
            newExpiry = Instant.now(clock).plus(Duration.ofSeconds(unknownErrorCacheInSec));
            tesCache.get(iotCredentialsPath).responseCode = HttpURLConnection.HTTP_INTERNAL_ERROR;
            tesCache.get(iotCredentialsPath).expiry = newExpiry;
            tesCache.get(iotCredentialsPath).credentials = response;
            LOGGER.atWarn().kv(IOT_CRED_PATH_KEY, iotCredentialsPath)
                    .log("Encountered error while fetching credentials", e);
        } finally {
            try (LockScope ls = LockScope.lock(cacheEntry.lock)) {
                // Complete the future to notify listeners that we're done.
                // Clear the future so that any new requests trigger an updated request instead of
                // pulling from the cache when the cached credentials are invalid
                CompletableFuture<Void> oldFuture = tesCache.get(iotCredentialsPath).future.getAndSet(null);
                if (oldFuture != null && !oldFuture.isDone()) {
                    oldFuture.complete(null);
                }
            }
        }

        return response;
    }

    /**
     * API for kernel to directly fetch credentials from TES instead of using HTTP server. Note that it bypasses
     * authN/authZ, so should be used carefully.
     *
     * @return AWS credentials from cloud.
     */
    public byte[] getCredentials() {
        try {
            return getCredentialsWithTimeout(0, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // Not possible
            return new byte[0];
        }
    }

    /**
     * API for kernel to directly fetch credentials from TES instead of using HTTP server.
     *
     * @return AwsCredentials instance compatible with the AWS SDK for credentials received from cloud.
     */
    public AwsCredentials getAwsCredentials() {
        return getCredentialsFromByte(getCredentials());
    }

    /**
     * API for kernel to directly fetch credentials from TES instead of using HTTP server.
     *
     * @return AwsCredentials instance compatible with the AWS SDK for credentials received from cloud.
     */
    public AwsCredentials getAwsCredentialsBypassCache() {
        return getCredentialsFromByte(getCredentialsBypassCache());
    }

    private AwsCredentials getCredentialsFromByte(byte[] data) {
        try {
            Map<String, String> credentials = OBJECT_MAPPER.readValue(data, Map.class);
            return AwsSessionCredentials
                    .create(credentials.get(ACCESS_KEY_DOWNSTREAM_STR), credentials.get(SECRET_ACCESS_DOWNSTREAM_STR),
                            credentials.get(SESSION_TOKEN_DOWNSTREAM_STR));
        } catch (IOException e) {
            LOGGER.atError().kv(IOT_CRED_PATH_KEY, iotCredentialsPath)
                    .kv("credentialData", new String(data, StandardCharsets.UTF_8))
                    .log("Error in retrieving AwsCredentials from TES");
            return null;
        }
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

    private void doAuth(final HttpExchange exchange) throws UnauthenticatedException, AuthorizationException {
        // if header is not present, then authToken would be null and authNhandler would throw
        String authNToken = exchange.getRequestHeaders().getFirst(AUTH_HEADER);
        String clientService = authNHandler.doAuthentication(authNToken);
        authZHandler.isAuthorized(TokenExchangeService.TOKEN_EXCHANGE_SERVICE_TOPICS,
                Permission.builder().principal(clientService).operation(TokenExchangeService.AUTHZ_TES_OPERATION)
                        .resource(null).build());
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
        int expiryTime = unknownErrorCacheInSec; // In case of unrecognized cloud errors, back off
        // Add caching Time-To-Live (TTL) for TES cloud errors
        if (statusCode >= 400 && statusCode < 500) {
            // 4xx retries are only meaningful unless a user action has been adopted, TTL should be longer
            expiryTime = cloud4xxErrorCacheInSec;
        } else if (statusCode >= 500 && statusCode < 600) {
            // 5xx could be a temporary cloud unavailability, TTL should be shorter
            expiryTime = cloud5xxErrorCacheInSec;
        }
        return Instant.now(clock).plus(Duration.ofSeconds(expiryTime));
    }

    /**
     * Check if the cached credentials are valid.
     *
     * @return if the cached credentials are valid.
     */
    private boolean areCredentialsValid(TESCache cacheEntry) {
        Instant now = Instant.now(clock);
        return cacheEntry.credentials != null && now.isBefore(cacheEntry.expiry);
    }

    /**
     * Clear cached credentials.
     */
    @SuppressWarnings("PMD.NullAssignment")
    void clearCache() {
        if (iotCredentialsPath != null && tesCache.containsKey(iotCredentialsPath)) {
            LOGGER.atDebug().kv(IOT_CRED_PATH_KEY, iotCredentialsPath).log("Clearing TES cache");
            TESCache cacheEntry = tesCache.get(iotCredentialsPath);
            cacheEntry.credentials = null;
            cacheEntry.responseCode = 0;
            cacheEntry.expiry = Instant.EPOCH;
            try (LockScope ls = LockScope.lock(cacheEntry.lock)) {
                CompletableFuture<Void> oldFuture = cacheEntry.future.getAndSet(null);
                if (oldFuture != null && !oldFuture.isDone()) {
                    oldFuture.complete(null);
                }
            }
        }
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
