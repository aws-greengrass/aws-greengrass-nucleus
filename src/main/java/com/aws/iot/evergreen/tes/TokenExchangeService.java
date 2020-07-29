/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.tes;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.iot.IotCloudHelper;
import com.aws.iot.evergreen.iot.IotConnectionManager;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.Utils;

import java.io.IOException;
import javax.inject.Inject;

import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;

@SuppressWarnings("PMD.DataClass")
@ImplementsService(name = TokenExchangeService.TOKEN_EXCHANGE_SERVICE_TOPICS)
public class TokenExchangeService extends EvergreenService {
    public static final String IOT_ROLE_ALIAS_TOPIC = "iotRoleAlias";
    public static final String PORT_TOPIC = "port";
    public static final String TOKEN_EXCHANGE_SERVICE_TOPICS = "TokenExchangeService";
    public static final String TES_URI_ENV_VARIABLE_NAME = "AWS_CONTAINER_CREDENTIALS_FULL_URI";
    // TODO: change when auth is supported
    public static final String TES_AUTH_ENV_VARIABLE_NAME = "AWS_CONTAINER_AUTHORIZATION_TOKEN";
    private static final String TES_CONFIG_ERROR_STR = "%s parameter is either empty or not configured for TES";
    // randomly choose a port
    private static final int DEFAULT_PORT = 0;
    private int port;
    private String iotRoleAlias;
    private HttpServerImpl server;

    private final IotConnectionManager iotConnectionManager;

    /**
     * Constructor.
     * @param topics the configuration coming from kernel
     * @param iotConnectionManager {@link IotConnectionManager}
     */
    @Inject
    public TokenExchangeService(Topics topics,
                                IotConnectionManager iotConnectionManager) {
        super(topics);
        // TODO: Add support for other params like role Aliases
        topics.lookup(PARAMETERS_CONFIG_KEY, PORT_TOPIC)
                .dflt(DEFAULT_PORT)
                .subscribe((why, newv) ->
                        port = Coerce.toInt(newv));

        topics.lookup(PARAMETERS_CONFIG_KEY, IOT_ROLE_ALIAS_TOPIC)
                .subscribe((why, newv) ->
                        iotRoleAlias = Coerce.toString(newv));

        this.iotConnectionManager = iotConnectionManager;
    }

    @Override
    @SuppressWarnings("PMD.CloseResource")
    protected void startup() {
        // TODO: Support tes restart with change in configuration like port, roleAlias.
        logger.atInfo().addKeyValue(PORT_TOPIC, port)
                .addKeyValue(IOT_ROLE_ALIAS_TOPIC, iotRoleAlias).log("Starting Token Server at port {}", port);
        reportState(State.RUNNING);
        try {
            validateConfig();
            IotCloudHelper cloudHelper = new IotCloudHelper();
            server = new HttpServerImpl(port,
                    new CredentialRequestHandler(iotRoleAlias, cloudHelper, iotConnectionManager));
            server.start();
            // Get port from the server, in case no port was specified and server started on a random port
            setEnvVariablesForDependencies(server.getServerPort());
        } catch (IOException | IllegalArgumentException e) {
            serviceErrored(e.toString());
        }
    }

    @Override
    protected void shutdown() {
        logger.atInfo().log("TokenExchangeService is shutting down!");
        if (server != null) {
            server.stop();
        }
        logger.atInfo().log("Stopped Server at port {}", port);
    }

    private void setEnvVariablesForDependencies(final int serverPort) {
        Topic tesUri = config.getRoot().lookup(SETENV_CONFIG_NAMESPACE, TES_URI_ENV_VARIABLE_NAME);
        final String tesUriValue = "http://localhost:" + serverPort + HttpServerImpl.URL;
        tesUri.withValue(tesUriValue);
        Topic tesAuth = config.getRoot().lookup(SETENV_CONFIG_NAMESPACE, TES_AUTH_ENV_VARIABLE_NAME);
        // TODO: Add auth support
        final String tesAuthValue = "Basic auth_not_supported";
        tesAuth.withValue(tesAuthValue);
    }

    private void validateConfig() {
        // Validate roleAlias
        if (Utils.isEmpty(iotRoleAlias)) {
            throw new IllegalArgumentException(String.format(TES_CONFIG_ERROR_STR, IOT_ROLE_ALIAS_TOPIC));
        }
    }
}
