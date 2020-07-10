/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.tes;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.util.Coerce;

import java.io.IOException;
import javax.inject.Inject;

import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;

@ImplementsService(name = TokenExchangeService.TOKEN_EXCHANGE_SERVICE_TOPICS)
public class TokenExchangeService extends EvergreenService {
    public static final String IOT_ROLE_ALIAS_TOPIC = "iotRoleAlias";
    public static final String PORT_TOPIC = "port";
    public static final String TOKEN_EXCHANGE_SERVICE_TOPICS = "TokenExchangeService";
    private static final String TES_URI_ENV_VARIABLE_NAME = "AWS_CONTAINER_CREDENTIALS_FULL_URI";
    // TODO: change when auth is supported
    private static final String TES_AUTH_ENV_VARIABLE_NAME = "AWS_CONTAINER_AUTHORIZATION_TOKEN";
    // Randomly choose a port
    private static final int DEFAULT_PORT = 0;
    private int port;
    private String iotRoleAlias;
    private HttpServerImpl server;

    private final CredentialsProviderBuilder credentialsProviderBuilder;

    /**
     * Constructor.
     * @param topics the configuration coming from kernel
     * @param credentialsProviderBuilder  {@link CredentialsProviderBuilder}
     */
    @Inject
    public TokenExchangeService(Topics topics,
                                CredentialsProviderBuilder credentialsProviderBuilder) {
        super(topics);
        // TODO: Add support for other params like role Aliases
        topics.lookup(PARAMETERS_CONFIG_KEY, PORT_TOPIC)
                .dflt(DEFAULT_PORT)
                .subscribe((why, newv) ->
                        port = Coerce.toInt(newv));

        topics.lookup(PARAMETERS_CONFIG_KEY, IOT_ROLE_ALIAS_TOPIC)
                .subscribe((why, newv) ->
                        iotRoleAlias = Coerce.toString(newv));

        this.credentialsProviderBuilder = credentialsProviderBuilder;
    }

    @Override
    @SuppressWarnings("PMD.CloseResource")
    public void startup() {
        // TODO: Support tes restart with change in configuration like port, roleAlias.
        logger.atInfo().addKeyValue("port", port).log("Starting Token Server at port {}", port);
        try {
            server = new HttpServerImpl(port,
                    new CredentialRequestHandler(iotRoleAlias, credentialsProviderBuilder));
            server.start();
            setEnvVariablesForDependencies();
            reportState(State.RUNNING);
        } catch (IOException e) {
            logger.atError().setCause(e).log();
            reportState(State.ERRORED);
        }
    }

    @Override
    public void shutdown() {
        logger.atInfo().log("TokenExchangeService is shutting down!");
        if (server != null) {
            server.stop();
        }
        logger.atInfo().log("Stopped Server at port {}", port);
    }

    private void setEnvVariablesForDependencies() {
        Topic tesUri = config.getRoot().lookup(SETENV_CONFIG_NAMESPACE, TES_URI_ENV_VARIABLE_NAME);
        final String tesUriValue = "http://localhost:" + port + HttpServerImpl.URL;
        tesUri.withValue(tesUriValue);
        Topic tesAuth = config.getRoot().lookup(SETENV_CONFIG_NAMESPACE, TES_AUTH_ENV_VARIABLE_NAME);
        // TODO: Add auth support
        final String tesAuthValue = "Basic auth_not_supported";
        tesAuth.withValue(tesAuthValue);
    }
}
