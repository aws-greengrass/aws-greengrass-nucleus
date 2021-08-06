/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc.modules;


import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.builtin.services.lifecycle.LifecycleIPCEventStreamAgent;
import com.aws.greengrass.dependency.InjectionActions;
import com.aws.greengrass.ipc.Startable;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import lombok.AccessLevel;
import lombok.Setter;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import javax.inject.Inject;

public class LifecycleIPCService implements Startable, InjectionActions {
    private static final Logger logger = LogManager.getLogger(LifecycleIPCService.class);
    public static final String LIFECYCLE_SERVICE_NAME = "aws.greengrass.ipc.lifecycle";

    @Inject
    @Setter(AccessLevel.PACKAGE)
    private LifecycleIPCEventStreamAgent eventStreamAgent;

    @Inject
    @Setter(AccessLevel.PACKAGE)
    private GreengrassCoreIPCService greengrassCoreIPCService;

    @Inject
    @Setter(AccessLevel.PACKAGE)
    private AuthorizationHandler authorizationHandler;

    @Override
    public void postInject() {
        List<String> opCodes = new ArrayList<>();
        opCodes.add(GreengrassCoreIPCService.PAUSE_COMPONENT);
        opCodes.add(GreengrassCoreIPCService.RESUME_COMPONENT);
        try {
            authorizationHandler.registerComponent(LIFECYCLE_SERVICE_NAME, new HashSet<>(opCodes));
        } catch (AuthorizationException e) {
            logger.atError("initialize-lifecycle-authorization-error", e)
                    .log("Failed to initialize the Lifecycle service with the Authorization module.");
        }
    }

    @Override
    public void startup() {
        greengrassCoreIPCService.setUpdateStateHandler(
                (context) -> eventStreamAgent.getUpdateStateOperationHandler(context));
        greengrassCoreIPCService.setSubscribeToComponentUpdatesHandler(
                (context) -> eventStreamAgent.getSubscribeToComponentUpdateHandler(context));
        greengrassCoreIPCService.setDeferComponentUpdateHandler(
                (context) -> eventStreamAgent.getDeferComponentHandler(context));
        greengrassCoreIPCService.setPauseComponentHandler(
                (context) -> eventStreamAgent.getPauseComponentHandler(context));
        greengrassCoreIPCService.setResumeComponentHandler(
                (context) -> eventStreamAgent.getResumeComponentHandler(context));
    }
}
