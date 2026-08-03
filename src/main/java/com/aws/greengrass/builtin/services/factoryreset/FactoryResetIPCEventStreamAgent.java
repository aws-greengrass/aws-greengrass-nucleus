/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.factoryreset;

import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.Permission;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import lombok.AccessLevel;
import lombok.Setter;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractFactoryResetOperationHandler;
import software.amazon.awssdk.aws.greengrass.model.FactoryResetRequest;
import software.amazon.awssdk.aws.greengrass.model.FactoryResetResponse;
import software.amazon.awssdk.aws.greengrass.model.ServiceError;
import software.amazon.awssdk.aws.greengrass.model.UnauthorizedError;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

import java.util.concurrent.ExecutorService;
import javax.inject.Inject;

import static com.aws.greengrass.ipc.common.ExceptionUtil.translateExceptions;
import static com.aws.greengrass.ipc.modules.FactoryResetIPCService.FACTORY_RESET_SERVICE_NAME;

public class FactoryResetIPCEventStreamAgent {

    private static final Logger logger = LogManager.getLogger(FactoryResetIPCEventStreamAgent.class);

    @Inject
    @Setter(AccessLevel.PACKAGE)
    private FactoryResetAgent factoryResetAgent;

    @Inject
    @Setter(AccessLevel.PACKAGE)
    private AuthorizationHandler authorizationHandler;

    @Inject
    @Setter(AccessLevel.PACKAGE)
    private ExecutorService executorService;

    /**
     * Get a FactoryResetOperationHandler for the given IPC context.
     *
     * @param context the IPC continuation handler context
     * @return a new handler instance
     */
    public FactoryResetOperationHandler getFactoryResetHandler(OperationContinuationHandlerContext context) {
        return new FactoryResetOperationHandler(context);
    }

    class FactoryResetOperationHandler extends GeneratedAbstractFactoryResetOperationHandler {

        private final String callerComponentName;

        protected FactoryResetOperationHandler(OperationContinuationHandlerContext context) {
            super(context);
            this.callerComponentName = context.getAuthenticationData().getIdentityLabel();
        }

        @Override
        protected void onStreamClosed() {
            // no-op: factory reset is a unary request/response, no stream to close
        }

        @Override
        public FactoryResetResponse handleRequest(FactoryResetRequest request) {
            return translateExceptions(() -> {
                // CLI and CLI clients are always allowed — consistent with CLIEventStreamAgent behavior.
                // All other components require an explicit accessControl policy entry.
                boolean isCliPrincipal = callerComponentName.startsWith("greengrass-cli#")
                        || "aws.greengrass.Cli".equals(callerComponentName);
                if (!isCliPrincipal) {
                    try {
                        authorizationHandler.isAuthorized(
                                FACTORY_RESET_SERVICE_NAME,
                                Permission.builder()
                                        .principal(callerComponentName)
                                        .operation(this.getOperationModelContext().getOperationName())
                                        .resource("*")
                                        .build());
                    } catch (AuthorizationException e) {
                        throw new UnauthorizedError(e.getMessage());
                    }
                }

                logger.atInfo()
                        .kv("caller", callerComponentName)
                        .log("Factory reset initiated via IPC");

                // Schedule the actual reset on a background thread.
                // The response is returned first so the CLI receives it before the IPC
                // connection is dropped by the nucleus shutdown.
                executorService.submit(() -> {
                    try {
                        // Small delay to allow the IPC response to be flushed to the client
                        Thread.sleep(500);
                        factoryResetAgent.performFactoryReset();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.atWarn().log("Factory reset was interrupted");
                    } catch (Exception e) {
                        logger.atError().setCause(e).log("Factory reset failed");
                    }
                });

                FactoryResetResponse response = new FactoryResetResponse();
                response.setStatus("INITIATED");
                response.setMessage("Factory reset initiated. The device will restart.");
                return response;
            });
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {
            // no-op: no streaming events for factory reset
        }
    }
}
