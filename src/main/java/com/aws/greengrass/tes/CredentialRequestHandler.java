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
import java.util.concurrent.ThreadLocalRandom;
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
    public static final int DEFAULT_TIME_BEFORE_CACHE_EXPIRE_IN_SEC = 300;
    public static final int DEFAULT_CLOUD_4XX_ERROR_CACHE_IN_SEC = 120;
    public static final int DEFAULT_CLOUD_5XX_ERROR_CACHE_IN_SEC = 60;
    public static final int DEFAULT_UNKNOWN_ERROR_CACHE_IN_SEC = 300;
    // Small jitter window applied before the single immediate retry in sendRequestResettingOnceOnConnectionError().
    // A shared-endpoint failure (e.g. a regional connectivity blip, or a breaking change rolled out to the
    // credentials endpoint) causes many devices to fail their *current* request at the same instant, independent
    // of each device's own cache-expiry schedule (each device's cache expiry is anchored to when it individually
    // last fetched, so cache-driven fetches are already staggered fleet-wide and are not the concern here).
    // Without jitter, every affected device's immediate retry would also land in the same instant as every other
    // device's retry, with no smoothing between the two waves of requests hitting the shared endpoint.
    private static final int RETRY_JITTER_MAX_MILLIS = 200;

    private volatile int cloud4xxErrorCacheInSec = DEFAULT_CLOUD_4XX_ERROR_CACHE_IN_SEC;
    private volatile int cloud5xxErrorCacheInSec = DEFAULT_CLOUD_5XX_ERROR_CACHE_IN_SEC;
    private volatile int unknownErrorCacheInSec = DEFAULT_UNKNOWN_ERROR_CACHE_IN_SEC;

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
            final IotCloudResponse cloudResponse = sendRequestResettingOnceOnConnectionError();
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
                        newExpiry = expiry.minus(Duration.ofSeconds(DEFAULT_TIME_BEFORE_CACHE_EXPIRE_IN_SEC));
                        tesCache.get(iotCredentialsPath).responseCode = HttpURLConnection.HTTP_OK;

                        if (newExpiry.isBefore(Instant.now(clock))) {
                            LOGGER.atWarn().kv(IOT_CRED_PATH_KEY, iotCredentialsPath)
                                    .log("Can't cache credentials as new credentials {} will "
                                                    + "expire in less than {} seconds", expiry,
                                            DEFAULT_TIME_BEFORE_CACHE_EXPIRE_IN_SEC);
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
            // Reaching here means the request failed on both the initial attempt and the immediate
            // retry (after resetting the connection manager) inside sendRequestResettingOnceOnConnectionError().
            // That rules out a simple stale-client/local-network-change cause, so cache the failure for longer
            // to avoid excessive retries against a cloud/network problem that a fresh client can't fix.
            String responseString = "Failed to get connection";
            response = responseString.getBytes(StandardCharsets.UTF_8);
            newExpiry = Instant.now(clock).plus(Duration.ofSeconds(unknownErrorCacheInSec));
            tesCache.get(iotCredentialsPath).responseCode = HttpURLConnection.HTTP_INTERNAL_ERROR;
            tesCache.get(iotCredentialsPath).expiry = newExpiry;
            tesCache.get(iotCredentialsPath).credentials = response;
            // The WARN-level log for this failure was already emitted by sendRequestResettingOnceOnConnectionError()
            // when the retry failed; log the resulting cache action at DEBUG here to avoid double-logging the
            // same failure at WARN.
            LOGGER.atDebug().kv(IOT_CRED_PATH_KEY, iotCredentialsPath)
                    .log("Caching credential fetch failure for {} seconds", unknownErrorCacheInSec, e);
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
     * Sends the TES credentials request, retrying once immediately if the first attempt fails with a
     * connection-level error.
     *
     * <p>A connection error ({@link AWSIotException} or {@link TLSAuthException} from
     * {@code sendHttpRequest}) discards the cached {@link IotConnectionManager} client and retries right away
     * against a freshly-built client before falling back to the longer {@code unknownErrorCacheInSec} cache
     * TTL. This is safe to do unconditionally because it is a single bounded retry, not an open-ended loop: if
     * the network is still down the retry fails fast against the new client too, and behavior falls through to
     * today's TTL-based backoff exactly as before. If the failure was actually caused by a stale client (e.g. a
     * local network change such as an IPv4-&gt;IPv6 failover, where the old client's connection pool/DNS
     * resolution no longer matches the current route), the retry succeeds immediately instead of waiting out the
     * full {@code unknownErrorCacheInSec} window.</p>
     *
     * <p>The initial attempt uses {@code sendHttpRequest}'s default internal retry-with-backoff (up to 3
     * attempts). The post-reset retry passes {@code maxAttempts=1} to skip that internal retry loop: a client
     * that was just rebuilt and fails immediately is unlikely to succeed within a couple hundred more
     * milliseconds of internal retries, so paying for those extra attempts would mostly just add latency. This
     * keeps the worst-case added latency from this method bounded to roughly one extra connect/read attempt
     * (plus jitter) rather than compounding a second full 3-attempt retry loop on top of the first — which
     * matters because some callers of {@link #getCredentialsBypassCache()} (see {@link #getCredentials()}) block
     * indefinitely on the result.</p>
     *
     * @return the cloud response from either the first attempt or the immediate retry
     * @throws AWSIotException  if both the initial attempt and the retry fail with an IoT error
     * @throws TLSAuthException if both the initial attempt and the retry fail with a TLS/connection error
     */
    private IotCloudResponse sendRequestResettingOnceOnConnectionError() throws AWSIotException, TLSAuthException {
        try {
            return iotCloudHelper.sendHttpRequest(iotConnectionManager, thingName, iotCredentialsPath,
                    IOT_CREDENTIALS_HTTP_VERB, null);
        } catch (AWSIotException | TLSAuthException e) {
            // Discard the cached HTTP client and retry once immediately. This covers the case where the
            // client's connection pool/DNS resolution is stale for the current network state (e.g. after a
            // transient outage causes an IPv4->IPv6 failover) without needing to detect or classify the
            // underlying network change.
            //
            // Note: TLSAuthException can also be thrown by client construction itself (bad cert/key/CA/trust
            // manager - see ClientConfigurationUtils/SecurityService), not just by the network call. For that
            // class of failure, rebuilding the client is a no-op and the retry below is expected to fail again
            // with the same error; that's fine since it's a single bounded retry either way.
            //
            // Logged at DEBUG here (not WARN) because a connection error that self-heals on the immediate retry
            // is not customer-impacting; only log at WARN if the retry below also fails, so log severity tracks
            // actual impact.
            LOGGER.atDebug().kv(IOT_CRED_PATH_KEY, iotCredentialsPath)
                    .log("Encountered connection error while fetching credentials, resetting connection and "
                            + "retrying once immediately", e);
            iotConnectionManager.reset();
            sleepWithJitterBeforeRetry();
            try {
                // maxAttempts=1: skip sendHttpRequest's own internal retry-with-backoff for this post-reset
                // attempt (see javadoc above) to keep this method's worst-case added latency bounded.
                return iotCloudHelper.sendHttpRequest(iotConnectionManager, thingName, iotCredentialsPath,
                        IOT_CREDENTIALS_HTTP_VERB, null, 1);
            } catch (AWSIotException | TLSAuthException retryException) {
                LOGGER.atWarn().kv(IOT_CRED_PATH_KEY, iotCredentialsPath)
                        .log("Immediate retry after resetting connection also failed", retryException);
                throw retryException;
            }
        }
    }

    /**
     * Sleep for a small random duration (0-{@link #RETRY_JITTER_MAX_MILLIS} ms) before the immediate retry in
     * {@link #sendRequestResettingOnceOnConnectionError()}.
     *
     * <p>Without this, a shared-endpoint failure affecting many devices at once (e.g. a regional connectivity
     * blip, or a breaking change rolled out to the credentials endpoint) would cause every affected device's
     * retry to land at the exact same instant as every other device's retry, with no smoothing between the two
     * waves of requests hitting the shared endpoint.</p>
     */
    private void sleepWithJitterBeforeRetry() {
        try {
            TimeUnit.MILLISECONDS.sleep(ThreadLocalRandom.current().nextInt(RETRY_JITTER_MAX_MILLIS + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
