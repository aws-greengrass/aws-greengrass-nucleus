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
import lombok.SneakyThrows;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import javax.inject.Inject;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeviceConfiguration.IOT_ROLE_ALIAS_TOPIC;

@SuppressWarnings("PMD.DataClass")
@ImplementsService(name = TokenExchangeService.TOKEN_EXCHANGE_SERVICE_TOPICS)
public class TokenExchangeService extends GreengrassService implements AwsCredentialsProvider {
    public static final String PORT_TOPIC = "port";
    public static final String TOKEN_EXCHANGE_SERVICE_TOPICS = "aws.greengrass.TokenExchangeService";
    public static final String TES_URI_ENV_VARIABLE_NAME = "AWS_CONTAINER_CREDENTIALS_FULL_URI";
    public static final String AUTHZ_TES_OPERATION = "getCredentials";
    private static final String TES_CONFIG_ERROR_STR = "%s parameter is either empty or not configured for TES";
    // randomly choose a port
    private static final int DEFAULT_PORT = 0;
    private int port;
    private String iotRoleAlias;
    private HttpServerImpl server;

    private final AuthorizationHandler authZHandler;
    private final CredentialRequestHandler credentialRequestHandler;
    private final ExecutorService executor;

    /**
     * Constructor.
     * @param topics the configuration coming from kernel
     * @param credentialRequestHandler {@link CredentialRequestHandler}
     * @param authZHandler {@link AuthorizationHandler}
     * @param executor executor service shared with kernel
     * @param deviceConfiguration device's system configuration
     */
    @Inject
    public TokenExchangeService(Topics topics,
                                CredentialRequestHandler credentialRequestHandler,
                                AuthorizationHandler authZHandler,
                                ExecutorService executor, DeviceConfiguration deviceConfiguration) {
        super(topics);
        // Port change should not be allowed
        topics.lookup(CONFIGURATION_CONFIG_KEY, PORT_TOPIC).dflt(DEFAULT_PORT)
                .subscribe((why, newv) -> port = Coerce.toInt(newv));

        deviceConfiguration.getIotRoleAlias().subscribe((why, newv) -> {
            iotRoleAlias = Coerce.toString(newv);
            credentialRequestHandler.clearCache();
            credentialRequestHandler.setIotCredentialsPath(iotRoleAlias);
        });
        deviceConfiguration.getThingName().subscribe((why, newv) -> {
            credentialRequestHandler.clearCache();
            credentialRequestHandler.setThingName(Coerce.toString(newv));
        });
        deviceConfiguration.getCertificateFilePath().subscribe((why, newv) -> credentialRequestHandler.clearCache());
        deviceConfiguration.getRootCAFilePath().subscribe((why, newv) -> credentialRequestHandler.clearCache());
        deviceConfiguration.getPrivateKeyFilePath().subscribe((why, newv) -> credentialRequestHandler.clearCache());

        this.authZHandler = authZHandler;
        this.credentialRequestHandler = credentialRequestHandler;
        this.executor = executor;
    }

    @Override
    public void postInject() {
        super.postInject();
        try {
            authZHandler.registerComponent(this.getName(), new HashSet<>(Arrays.asList(AUTHZ_TES_OPERATION)));
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
            server = new HttpServerImpl(port, credentialRequestHandler, executor);
            server.start();
            logger.atInfo().log("Started server at port {}", server.getServerPort());
            // Get port from the server, in case no port was specified and server started on a random port
            setEnvVariablesForDependencies(server.getServerPort());
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
        tesUri.withValue(tesUriValue);
    }

    private void validateConfig() {
        // Validate roleAlias
        if (Utils.isEmpty(iotRoleAlias)) {
            throw new IllegalArgumentException(String.format(TES_CONFIG_ERROR_STR, IOT_ROLE_ALIAS_TOPIC));
        }
    }

    @SneakyThrows
    @Override
    public AwsCredentials resolveCredentials() {
        return credentialRequestHandler.getAwsCredentials();
    }
}
