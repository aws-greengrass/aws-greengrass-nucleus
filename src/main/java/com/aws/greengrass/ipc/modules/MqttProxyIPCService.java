/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc.modules;

import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.builtin.services.mqttproxy.MqttProxyIPCAgent;
import com.aws.greengrass.dependency.InjectionActions;
import com.aws.greengrass.ipc.Startable;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCService;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import javax.inject.Inject;

public class MqttProxyIPCService implements Startable, InjectionActions {
    private static final Logger logger = LogManager.getLogger(MqttProxyIPCService.class);
    public static final String MQTT_PROXY_SERVICE_NAME = "aws.greengrass.ipc.mqttproxy";

    @Inject
    MqttProxyIPCAgent mqttProxyIPCAgent;

    @Inject
    private GreengrassCoreIPCService greengrassCoreIPCService;

    @Inject
    private AuthorizationHandler authorizationHandler;

    @Override
    public void postInject() {
        List<String> opNames = Arrays.asList(GreengrassCoreIPCService.PUBLISH_TO_IOT_CORE,
                GreengrassCoreIPCService.SUBSCRIBE_TO_IOT_CORE);
        try {
            authorizationHandler.registerComponent(MQTT_PROXY_SERVICE_NAME, new HashSet<>(opNames));
        } catch (AuthorizationException e) {
            logger.atError("initialize-mqttproxy-authorization-error", e)
                    .log("Failed to initialize the MQTT Proxy service with the Authorization module.");
        }
    }

    @Override
    public void startup() {
        greengrassCoreIPCService.setPublishToIoTCoreHandler(
                (context) -> mqttProxyIPCAgent.getPublishToIoTCoreOperationHandler(context));
        greengrassCoreIPCService.setSubscribeToIoTCoreHandler(
                (context) -> mqttProxyIPCAgent.getSubscribeToIoTCoreOperationHandler(context));
    }
}
