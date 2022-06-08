/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc.modules;

import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.dependency.InjectionActions;
import com.aws.greengrass.ipc.Startable;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.telemetry.TelemetryIPCEventStreamAgent;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import javax.inject.Inject;

public class TelemetryIPCService implements Startable, InjectionActions {
    public static final String PUB_SUB_SERVICE_NAME = "aws.greengrass.ipc.pubsub";
    private static final Logger logger = LogManager.getLogger(TelemetryIPCService.class);
    @Inject
    private AuthorizationHandler authorizationHandler;

    @Inject
    private TelemetryIPCEventStreamAgent eventStreamAgent;

    @Inject
    private GreengrassCoreIPCService greengrassCoreIPCService;

    @Override
    public void postInject() {
        List<String> opCodes = new ArrayList<>();
        opCodes.add(GreengrassCoreIPCService.SUBSCRIBE_TO_TOPIC);
        opCodes.add(GreengrassCoreIPCService.PUBLISH_TO_TOPIC);
        try {
            authorizationHandler.registerComponent(PUB_SUB_SERVICE_NAME, new HashSet<>(opCodes));
        } catch (AuthorizationException e) {
            logger.atError("initialize-pubsub-authorization-error", e)
                    .log("Failed to initialize the Pub/Sub service with the Authorization module.");
        }
    }

    @Override
    public void startup() {
        greengrassCoreIPCService.setEmitTelemetryMetricsHandler(
                context -> eventStreamAgent.getEmitTelemetryMetricsHandler(context));
    }
}
