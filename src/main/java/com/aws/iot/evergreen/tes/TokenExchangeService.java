/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.tes;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.DeviceConfigurationHelper;
import com.aws.iot.evergreen.deployment.exceptions.DeviceConfigurationException;
import com.aws.iot.evergreen.deployment.model.DeviceConfiguration;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.util.Coerce;

import java.io.IOException;
import javax.inject.Inject;
import javax.inject.Singleton;

@ImplementsService(name = "TokenExchangeService", autostart = false)
@Singleton
public class TokenExchangeService extends EvergreenService {
    private static final String TES_URI_ENV_VARIABLE_NAME = "AWS_CONTAINER_CREDENTIALS_FULL_URI";
    // TODO: change when auth is supported
    private static final String TES_AUTH_ENV_VARIABLE_NAME = "AWS_CONTAINER_AUTHORIZATION_TOKEN";
    //TODO: this is used by GG daemon, revisit for backward compatibility
    private static final int DEFAULT_PORT = 8000;
    private int port;
    private String iotEndpoint;
    private HttpServerImpl server;
    @Inject
    private DeviceConfigurationHelper deviceConfigurationHelper;

    @Inject
    private IotConnectionManagerFactory iotConnectionManagerFactory;

    /**
     * Constructor.
     * @param topics the configuration coming from kernel
     */
    public TokenExchangeService(Topics topics) {
        super(topics);
        // TODO: Add support for other params like role Aliases
        topics.lookup("port")
                .dflt(DEFAULT_PORT)
                .subscribe((why, newv) ->
                        port = Coerce.toInt(newv));

        topics.lookup("iotEndpoint")
                .subscribe((why, newv) ->
                        iotEndpoint = Coerce.toString(newv));
    }

    /**
     * Contructor for unit testing.
     * @param topics the configuration coming from kernel
     * @param deviceConfigurationHelper {@link DeviceConfigurationHelper}
     * @param iotConnectionManagerFactory {@link IotConnectionManagerFactory}
     */
    public TokenExchangeService(Topics topics,
                                DeviceConfigurationHelper deviceConfigurationHelper,
                                IotConnectionManagerFactory iotConnectionManagerFactory) {
        super(topics);
        this.deviceConfigurationHelper = deviceConfigurationHelper;
        this.iotConnectionManagerFactory = iotConnectionManagerFactory;
    }

    @Override
    @SuppressWarnings("PMD.CloseResource")
    public void startup() {
        // TODO: Support tes restart with change in configuration like port, endpoint.
        logger.atInfo().addKeyValue("port", port).log("Starting Token Server at port {}", port);
        try {
            IotConnectionManager connManager = iotConnectionManagerFactory.getIotConnectionManager(iotEndpoint,
                    deviceConfigurationHelper.getDeviceConfiguration());
            IotCloudHelper cloudHelper = new IotCloudHelper();
            server = new HttpServerImpl(port, new CredentialRequestHandler(cloudHelper, connManager));
            server.start();
            setEnvVariablesForDependencies();
            reportState(State.RUNNING);
        } catch (IOException | DeviceConfigurationException e) {
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

    public static class IotConnectionManagerFactory {
        public IotConnectionManager getIotConnectionManager(final String iotEndpoint,
                                                            final DeviceConfiguration deviceConfiguration)
                throws DeviceConfigurationException {
            return new IotConnectionManager(iotEndpoint, deviceConfiguration);
        }
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
