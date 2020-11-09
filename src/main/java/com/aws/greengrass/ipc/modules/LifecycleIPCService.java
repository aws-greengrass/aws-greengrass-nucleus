/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc.modules;


import com.aws.greengrass.builtin.services.lifecycle.LifecycleIPCEventStreamAgent;
import com.aws.greengrass.dependency.InjectionActions;
import com.aws.greengrass.ipc.Startable;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import lombok.AccessLevel;
import lombok.Setter;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCService;

import javax.inject.Inject;

public class LifecycleIPCService implements Startable, InjectionActions {
    private static final Logger logger = LogManager.getLogger(LifecycleIPCService.class);

    @Inject
    @Setter(AccessLevel.PACKAGE)
    private LifecycleIPCEventStreamAgent eventStreamAgent;

    @Inject
    @Setter(AccessLevel.PACKAGE)
    private GreengrassCoreIPCService greengrassCoreIPCService;

    @Override
    public void startup() {
        greengrassCoreIPCService.setUpdateStateHandler(
                (context) -> {
                    logger.atInfo().log("Executing the lambda");
                    return eventStreamAgent.getUpdateStateOperationHandler(context);
                });
        greengrassCoreIPCService.setSubscribeToComponentUpdatesHandler(
                (context) -> eventStreamAgent.getSubscribeToComponentUpdateHandler(context));
        greengrassCoreIPCService.setDeferComponentUpdateHandler(
                (context) -> eventStreamAgent.getDeferComponentHandler(context));

    }
}
