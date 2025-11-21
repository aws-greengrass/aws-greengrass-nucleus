/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.tes;

import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.ImplementsService;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Utils;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import javax.inject.Inject;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeviceConfiguration.IOT_ROLE_ALIAS_TOPIC;

@SuppressWarnings("PMD.DataClass")
@ImplementsService(name = TokenExchangeService.TOKEN_EXCHANGE_SERVICE_TOPICS)
public class TokenExchangeService extends GreengrassService implements AwsCredentialsProvider {
    public static final String PORT_TOPIC = "port";
    public static final String ACTIVE_PORT_TOPIC = "activePort";
    public static final String TOKEN_EXCHANGE_SERVICE_TOPICS = "aws.greengrass.TokenExchangeService";
    public static final String TES_URI_ENV_VARIABLE_NAME = "AWS_CONTAINER_CREDENTIALS_FULL_URI";
    public static final String AUTHZ_TES_OPERATION = "getCredentials";
    private static final String TES_CONFIG_ERROR_STR = "%s parameter is either empty or not configured for TES";
    // randomly choose a port
    private static final int DEFAULT_PORT = 0;
    private int port;
    private String iotRoleAlias;
    private HttpServerImpl server;

    public static final String CLOUD_4XX_ERROR_CACHE_TOPIC = "error4xxCredentialRetryInSec";
    public static final String CLOUD_5XX_ERROR_CACHE_TOPIC = "error5xxCredentialRetryInSec";
    public static final String UNKNOWN_ERROR_CACHE_TOPIC = "errorUnknownCredentialRetryInSec";
    private static final int MINIMUM_ERROR_CACHE_IN_SEC = 10;
    private int cloud4xxErrorCache;
    private int cloud5xxErrorCache;
    private int unknownErrorCache;

    private final AuthorizationHandler authZHandler;
    private final CredentialRequestHandler credentialRequestHandler;

    /**
     * Constructor.
     * @param topics the configuration coming from kernel
     * @param credentialRequestHandler {@link CredentialRequestHandler}
     * @param authZHandler {@link AuthorizationHandler}
     * @param deviceConfiguration device's system configuration
     */
    @Inject
    public TokenExchangeService(Topics topics,
                                CredentialRequestHandler credentialRequestHandler,
                                AuthorizationHandler authZHandler, DeviceConfiguration deviceConfiguration) {
        super(topics);
        port = Coerce.toInt(config.lookup(CONFIGURATION_CONFIG_KEY, PORT_TOPIC).dflt(DEFAULT_PORT));
        config.subscribe((why, node) -> {
            if (node != null && node.childOf(PORT_TOPIC)) {
                logger.atDebug("tes-config-change").kv("node", node).kv("why", why).log();
                port = Coerce.toInt(node);
                Topic activePortTopic = config.lookup(CONFIGURATION_CONFIG_KEY, ACTIVE_PORT_TOPIC);
                if (port != Coerce.toInt(activePortTopic)) {
                    logger.atInfo("tes-config-change").kv(PORT_TOPIC, port).kv("node", node).kv("why", why)
                            .log("Restarting TES server due to port config change");
                    requestRestart();
                }
            }
        });
        deviceConfiguration.getIotRoleAlias().subscribe((why, newv) -> {
            iotRoleAlias = Coerce.toString(newv);
        });

        this.authZHandler = authZHandler;
        this.credentialRequestHandler = credentialRequestHandler;

        cloud4xxErrorCache = validateCacheConfig(Coerce.toInt(config.lookup(
                        CONFIGURATION_CONFIG_KEY, CLOUD_4XX_ERROR_CACHE_TOPIC).dflt(
                        CredentialRequestHandler.CLOUD_4XX_ERROR_CACHE_IN_SEC)),
                CredentialRequestHandler.CLOUD_4XX_ERROR_CACHE_IN_SEC);
        cloud5xxErrorCache = validateCacheConfig(Coerce.toInt(config.lookup(
                        CONFIGURATION_CONFIG_KEY, CLOUD_5XX_ERROR_CACHE_TOPIC).dflt(
                        CredentialRequestHandler.CLOUD_5XX_ERROR_CACHE_IN_SEC)),
                CredentialRequestHandler.CLOUD_5XX_ERROR_CACHE_IN_SEC);
        unknownErrorCache = validateCacheConfig(Coerce.toInt(config.lookup(
                        CONFIGURATION_CONFIG_KEY, UNKNOWN_ERROR_CACHE_TOPIC).dflt(
                        CredentialRequestHandler.UNKNOWN_ERROR_CACHE_IN_SEC)),
                CredentialRequestHandler.UNKNOWN_ERROR_CACHE_IN_SEC);

        credentialRequestHandler.configureCacheSettings(cloud4xxErrorCache, cloud5xxErrorCache, unknownErrorCache);

        // Subscribe to cache configuration changes
        config.subscribe((why, node) -> {
            if (node != null && (node.childOf(CLOUD_4XX_ERROR_CACHE_TOPIC)
                    || node.childOf(CLOUD_5XX_ERROR_CACHE_TOPIC)
                    || node.childOf(UNKNOWN_ERROR_CACHE_TOPIC))) {
                logger.atDebug("tes-cache-config-change").kv("node", node).kv("why", why).log();

                int newCloud4xxErrorCache = validateCacheConfig(Coerce.toInt(config.lookup(
                        CONFIGURATION_CONFIG_KEY, CLOUD_4XX_ERROR_CACHE_TOPIC).dflt(
                        CredentialRequestHandler.CLOUD_4XX_ERROR_CACHE_IN_SEC)), cloud4xxErrorCache);
                int newCloud5xxErrorCache = validateCacheConfig(Coerce.toInt(config.lookup(
                        CONFIGURATION_CONFIG_KEY, CLOUD_5XX_ERROR_CACHE_TOPIC).dflt(
                        CredentialRequestHandler.CLOUD_5XX_ERROR_CACHE_IN_SEC)), cloud5xxErrorCache);
                int newUnknownErrorCache = validateCacheConfig(Coerce.toInt(config.lookup(
                        CONFIGURATION_CONFIG_KEY, UNKNOWN_ERROR_CACHE_TOPIC).dflt(
                        CredentialRequestHandler.UNKNOWN_ERROR_CACHE_IN_SEC)), unknownErrorCache);

                if (cloud4xxErrorCache != newCloud4xxErrorCache
                        || cloud5xxErrorCache != newCloud5xxErrorCache
                        || unknownErrorCache != newUnknownErrorCache) {
                    cloud4xxErrorCache = newCloud4xxErrorCache;
                    cloud5xxErrorCache = newCloud5xxErrorCache;
                    unknownErrorCache = newUnknownErrorCache;
                    credentialRequestHandler.configureCacheSettings(
                            newCloud4xxErrorCache, newCloud5xxErrorCache, newUnknownErrorCache);

                    logger.atInfo("tes-cache-config-change").kv("unknownErrorCache", newUnknownErrorCache)
                            .kv("cloud4xxErrorCache", newCloud4xxErrorCache)
                            .kv("cloud5xxErrorCache", newCloud5xxErrorCache)
                            .log("Restarting TES server due to cache config change");
                    requestRestart();
                }
            }
        });
    }

    @Override
    public void postInject() {
        super.postInject();
        try {
            authZHandler.registerComponent(this.getName(),
                    new HashSet<>(Collections.singletonList(AUTHZ_TES_OPERATION)));
        } catch (AuthorizationException e) {
            serviceErrored(e);
        }
    }

    @Override
    @SuppressWarnings("PMD.CloseResource")
    protected void startup() {
        logger.atInfo().addKeyValue(PORT_TOPIC, port).addKeyValue(IOT_ROLE_ALIAS_TOPIC, iotRoleAlias)
                .log("Attempting to start server at configured port {}", port);
        try {
            validateConfig();
            server = new HttpServerImpl(port, credentialRequestHandler);
            server.start();
            logger.atInfo().log("Started server at port {}", server.getServerPort());
            // Get port from the server, in case no port was specified and server started on a random port
            setEnvVariablesForDependencies(server.getServerPort());
            // Store the actual port being used in the config so that the CLI can show the value for debugging
            config.lookup(CONFIGURATION_CONFIG_KEY, ACTIVE_PORT_TOPIC).overrideValue(server.getServerPort());
            reportState(State.RUNNING);
        } catch (IOException | IllegalArgumentException e) {
            serviceErrored(e);
        }
    }

    @Override
    protected void shutdown() {
        logger.atInfo().log("TokenExchangeService is shutting down!");
        if (server != null) {
            server.stop();
            logger.atInfo().log("Stopped server at port {}", server.getServerPort());
        }
    }

    private void setEnvVariablesForDependencies(final int serverPort) {
        Topic tesUri = config.getRoot().lookup(SETENV_CONFIG_NAMESPACE, TES_URI_ENV_VARIABLE_NAME);
        final String tesUriValue = "http://localhost:" + serverPort + HttpServerImpl.URL;
        tesUri.overrideValue(tesUriValue);
    }

    private void validateConfig() {
        // Validate roleAlias
        if (Utils.isEmpty(iotRoleAlias)) {
            throw new IllegalArgumentException(String.format(TES_CONFIG_ERROR_STR, IOT_ROLE_ALIAS_TOPIC));
        }
    }

    private int validateCacheConfig(int newCacheValue, int oldCacheValue) {
        if (newCacheValue < MINIMUM_ERROR_CACHE_IN_SEC) {
            logger.atError().log("Error cache value must be at least {}", MINIMUM_ERROR_CACHE_IN_SEC);
            return oldCacheValue;
        }
        return newCacheValue;
    }

    @Override
    public AwsCredentials resolveCredentials() {
        return credentialRequestHandler.getAwsCredentials();
    }
}
