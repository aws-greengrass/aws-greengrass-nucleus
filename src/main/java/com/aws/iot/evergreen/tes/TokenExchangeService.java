/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.tes;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.DeviceConfigurationHelper;
import com.aws.iot.evergreen.deployment.exceptions.DeviceConfigurationException;
import com.aws.iot.evergreen.deployment.model.DeviceConfiguration;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Coerce;

import java.io.IOException;
import javax.inject.Inject;
import javax.inject.Singleton;

@ImplementsService(name = "TokenExchangeService", autostart = false)
@Singleton
public class TokenExchangeService extends EvergreenService {
    private static final Logger LOGGER = LogManager.getLogger(TokenExchangeService.class);
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
                .dflt(6666)
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
        LOGGER.atInfo().addKeyValue("port", port).log("Starting Token Server at port {}", port);
        try {
            IotConnectionManager connManager = iotConnectionManagerFactory.getIotConnectionManager(iotEndpoint,
                    deviceConfigurationHelper.getDeviceConfiguration());
            IotCloudHelper cloudHelper = new IotCloudHelper();
            server = new HttpServerImpl(port, new CredentialRequestHandler(cloudHelper, connManager));
            server.start();
        } catch (IOException | DeviceConfigurationException e) {
            LOGGER.error("Caught exception...", e);
            reportState(State.ERRORED);
        }
        reportState(State.RUNNING);
    }

    @Override
    public void shutdown() {
        LOGGER.atInfo().log("TokenExchangeService is shutting down!");
        if (server != null) {
            server.stop();
        }
        LOGGER.atInfo().log("Stopped Server at port {}", port);
    }

    public static class IotConnectionManagerFactory {
        public IotConnectionManager getIotConnectionManager(final String iotEndpoint,
                                                            final DeviceConfiguration deviceConfiguration)
                throws DeviceConfigurationException {
            return new IotConnectionManager(iotEndpoint, deviceConfiguration);
        }
    }
}
