/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc.modules;

import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.builtin.services.telemetry.ComponentMetricIPCEventStreamAgent;
import com.aws.greengrass.dependency.InjectionActions;
import com.aws.greengrass.ipc.Startable;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import javax.inject.Inject;

public class ComponentMetricIPCService implements Startable, InjectionActions {
    public static final String PUT_COMPONENT_METRIC_SERVICE_NAME = "aws.greengrass.ipc.componentmetric";
    private static final Logger logger = LogManager.getLogger(ComponentMetricIPCService.class);
    @Inject
    private AuthorizationHandler authorizationHandler;

    @Inject
    private ComponentMetricIPCEventStreamAgent eventStreamAgent;

    @Inject
    private GreengrassCoreIPCService greengrassCoreIPCService;

    @Override
    public void postInject() {
        List<String> opCodes = new ArrayList<>();
        opCodes.add(GreengrassCoreIPCService.PUT_COMPONENT_METRIC);
        try {
            authorizationHandler.registerComponent(PUT_COMPONENT_METRIC_SERVICE_NAME, new HashSet<>(opCodes));
        } catch (AuthorizationException e) {
            logger.atError("initialize-put-component-metric-authorization-error", e)
                    .log("Failed to initialize the Component Metric service with the Authorization module.");
        }
    }

    @Override
    public void startup() {
        greengrassCoreIPCService.setPutComponentMetricHandler(
                context -> eventStreamAgent.getPutComponentMetricHandler(context));
    }
}
