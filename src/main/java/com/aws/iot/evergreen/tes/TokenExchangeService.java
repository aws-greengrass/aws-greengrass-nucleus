/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.tes;

import com.aws.iot.evergreen.auth.AuthorizationHandler;
import com.aws.iot.evergreen.auth.AuthorizationPolicy;
import com.aws.iot.evergreen.auth.exceptions.AuthorizationException;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.Utils;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import javax.inject.Inject;

import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;

@ImplementsService(name = TokenExchangeService.TOKEN_EXCHANGE_SERVICE_TOPICS)
public class TokenExchangeService extends EvergreenService {
    public static final String IOT_ROLE_ALIAS_TOPIC = "iotRoleAlias";
    public static final String PORT_TOPIC = "port";
    public static final String TOKEN_EXCHANGE_SERVICE_TOPICS = "TokenExchangeService";
    public static final String TES_URI_ENV_VARIABLE_NAME = "AWS_CONTAINER_CREDENTIALS_FULL_URI";
    // TODO: change when auth is supported
    public static final String TES_AUTH_ENV_VARIABLE_NAME = "AWS_CONTAINER_AUTHORIZATION_TOKEN";
    public static final String AUTHZ_TES_OPERATION = "getCredentials";
    private static final String TES_CONFIG_ERROR_STR = "%s parameter is either empty or not configured for TES";
    // randomly choose a port
    private static final int DEFAULT_PORT = 0;
    private int port;
    private String iotRoleAlias;
    private HttpServerImpl server;
    private List<AuthorizationPolicy> authZPolicy;

    private final AuthorizationHandler authZHandler;
    private final CredentialRequestHandler credentialRequestHandler;

    /**
     * Constructor.
     * @param topics the configuration coming from kernel
     * @param credentialRequestHandler {@link CredentialRequestHandler}
     * @param authZHandler {@link AuthorizationHandler}
     */
    @Inject
    public TokenExchangeService(Topics topics,
                                CredentialRequestHandler credentialRequestHandler,
                                AuthorizationHandler authZHandler) {
        super(topics);
        // TODO: Add support for other params like role Aliases
        topics.lookup(PARAMETERS_CONFIG_KEY, PORT_TOPIC)
                .dflt(DEFAULT_PORT)
                .subscribe((why, newv) ->
                        port = Coerce.toInt(newv));

        topics.lookup(PARAMETERS_CONFIG_KEY, IOT_ROLE_ALIAS_TOPIC)
                .subscribe((why, newv) ->
                        iotRoleAlias = Coerce.toString(newv));
        // TODO: Add support for overriding this from config
        this.authZPolicy = getDefaultAuthZPolicy();
        this.authZHandler = authZHandler;
        this.credentialRequestHandler = credentialRequestHandler;
    }

    @Override
    @SuppressWarnings("PMD.CloseResource")
    public void startup() {
        // TODO: Support tes restart with change in configuration like port, roleAlias.
        logger.atInfo().addKeyValue(PORT_TOPIC, port)
                .addKeyValue(IOT_ROLE_ALIAS_TOPIC, iotRoleAlias).log("Starting Token Server at port {}", port);
        try {
            authZHandler.registerService(this.getName(), new HashSet(Arrays.asList(AUTHZ_TES_OPERATION)));
            authZHandler.loadAuthorizationPolicy(this.getName(), authZPolicy);
            reportState(State.RUNNING);
            validateConfig();
            server = new HttpServerImpl(port, credentialRequestHandler);
            credentialRequestHandler.setIotCredentialsPath(iotRoleAlias);
            server.start();
            // Get port from the server, in case no port was specified and server started on a random port
            setEnvVariablesForDependencies(server.getServerPort());
        } catch (IOException | IllegalArgumentException e) {
            serviceErrored(e.toString());
        } catch (AuthorizationException e) {
            // This should never happen
            serviceErrored(e.toString());
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

    List<AuthorizationPolicy> getDefaultAuthZPolicy() {
        String defaultPolicyDesc = "Default TokenExchangeService policy";
        return Arrays.asList(AuthorizationPolicy.builder()
                .policyId(UUID.randomUUID().toString())
                .policyDescription(defaultPolicyDesc)
                .principles(new HashSet(Arrays.asList("*")))
                .operations(new HashSet(Arrays.asList(AUTHZ_TES_OPERATION)))
                .build());
    }
}
