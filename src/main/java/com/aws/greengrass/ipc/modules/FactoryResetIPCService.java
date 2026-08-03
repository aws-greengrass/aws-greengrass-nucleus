/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc.modules;

import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.builtin.services.factoryreset.FactoryResetIPCEventStreamAgent;
import com.aws.greengrass.dependency.InjectionActions;
import com.aws.greengrass.ipc.Startable;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import lombok.AccessLevel;
import lombok.Setter;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCService;

import java.util.HashSet;
import javax.inject.Inject;

/**
 * IPC service registration for the FactoryReset operation.
 *
 * <p>IPC service identifier: {@code aws.greengrass.ipc.factoryreset}
 *
 * <p>Authorization resource: {@code *} (wildcard — caller must be authorized to perform factory reset at all).
 *
 * <p>Example accessControl policy in a component recipe:
 * <pre>
 * accessControl:
 *   aws.greengrass.ipc.factoryreset:
 *     com.example.MyAdminComponent:factoryreset:1:
 *       policyDescription: "Allow factory reset"
 *       operations:
 *         - "aws.greengrass#FactoryReset"
 *       resources:
 *         - "*"
 * </pre>
 */
public class FactoryResetIPCService implements Startable, InjectionActions {

    private static final Logger logger = LogManager.getLogger(FactoryResetIPCService.class);

    /**
     * IPC service identifier used in accessControl policies.
     * Follows the same naming convention as {@link LifecycleIPCService#LIFECYCLE_SERVICE_NAME}.
     */
    public static final String FACTORY_RESET_SERVICE_NAME = "aws.greengrass.ipc.factoryreset";

    @Inject
    @Setter(AccessLevel.PACKAGE)
    private FactoryResetIPCEventStreamAgent eventStreamAgent;

    @Inject
    @Setter(AccessLevel.PACKAGE)
    private GreengrassCoreIPCService greengrassCoreIPCService;

    @Inject
    @Setter(AccessLevel.PACKAGE)
    private AuthorizationHandler authorizationHandler;

    @Override
    public void postInject() {
        // Register the FactoryReset operation with the authorization module so that
        // accessControl policies referencing FACTORY_RESET_SERVICE_NAME are enforced.
        try {
            authorizationHandler.registerComponent(FACTORY_RESET_SERVICE_NAME,
                    new HashSet<>(java.util.Collections.singletonList(GreengrassCoreIPCService.FACTORY_RESET)));
        } catch (AuthorizationException e) {
            logger.atError("initialize-factoryreset-authorization-error", e)
                    .log("Failed to initialize the FactoryReset IPC service with the Authorization module.");
        }
    }

    @Override
    public void startup() {
        // Wire the FactoryReset IPC operation to the handler from FactoryResetIPCEventStreamAgent
        greengrassCoreIPCService.setFactoryResetHandler(
                (context) -> eventStreamAgent.getFactoryResetHandler(context));
    }
}
