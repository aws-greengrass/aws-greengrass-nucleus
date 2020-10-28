/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc.modules;


import com.aws.greengrass.builtin.services.lifecycle.LifecycleIPCAgent;
import com.aws.greengrass.builtin.services.lifecycle.LifecycleIPCEventStreamAgent;
import com.aws.greengrass.dependency.InjectionActions;
import com.aws.greengrass.ipc.ConnectionContext;
import com.aws.greengrass.ipc.IPCRouter;
import com.aws.greengrass.ipc.Startable;
import com.aws.greengrass.ipc.common.BuiltInServiceDestinationCode;
import com.aws.greengrass.ipc.common.FrameReader.Message;
import com.aws.greengrass.ipc.exceptions.IPCException;
import com.aws.greengrass.ipc.services.common.ApplicationMessage;
import com.aws.greengrass.ipc.services.lifecycle.DeferComponentUpdateRequest;
import com.aws.greengrass.ipc.services.lifecycle.LifecycleClientOpCodes;
import com.aws.greengrass.ipc.services.lifecycle.LifecycleGenericResponse;
import com.aws.greengrass.ipc.services.lifecycle.LifecycleResponseStatus;
import com.aws.greengrass.ipc.services.lifecycle.UpdateStateRequest;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import lombok.AccessLevel;
import lombok.Setter;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCService;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import javax.inject.Inject;

public class LifecycleIPCService implements Startable, InjectionActions {
    private static final Logger logger = LogManager.getLogger(LifecycleIPCService.class);
    private static final ObjectMapper CBOR_MAPPER = new CBORMapper();

    @Inject
    private IPCRouter router;

    @Inject
    private LifecycleIPCAgent agent;

    @Inject
    @Setter (AccessLevel.PACKAGE)
    private LifecycleIPCEventStreamAgent eventStreamAgent;

    @Inject
    @Setter (AccessLevel.PACKAGE)
    private GreengrassCoreIPCService greengrassCoreIPCService;

    @Override
    public void postInject() {
        BuiltInServiceDestinationCode destination = BuiltInServiceDestinationCode.LIFECYCLE;
        try {
            router.registerServiceCallback(destination.getValue(), this::handleMessage);
            logger.atInfo().setEventType("ipc-register-request-handler").addKeyValue("destination", destination.name())
                    .log();
        } catch (IPCException e) {
            logger.atError().setEventType("ipc-register-request-handler-error").setCause(e)
                    .addKeyValue("destination", destination.name())
                    .log("Failed to register service callback to destination");
        }
    }

    /**
     * Handle all requests from the client.
     *
     * @param message the incoming request
     * @param context caller request context
     * @return future containing our response
     */
    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    public Future<Message> handleMessage(Message message, ConnectionContext context) {
        CompletableFuture<Message> fut = new CompletableFuture<>();

        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        try {
            // GG_NEEDS_REVIEW: TODO: add version compatibility check
            LifecycleGenericResponse lifecycleGenericResponse = new LifecycleGenericResponse(
                    LifecycleResponseStatus.InvalidRequest, "Unknown request type");
            if (LifecycleClientOpCodes.values().length > applicationMessage.getOpCode()) {
                LifecycleClientOpCodes lifecycleClientOpCodes =
                        LifecycleClientOpCodes.values()[applicationMessage.getOpCode()];
                switch (lifecycleClientOpCodes) {
                    case UPDATE_STATE:
                        UpdateStateRequest updateStateRequest =
                                CBOR_MAPPER.readValue(applicationMessage.getPayload(), UpdateStateRequest.class);
                        lifecycleGenericResponse = agent.updateState(updateStateRequest, context);
                        break;
                    case SUBSCRIBE_COMPONENT_UPDATE:
                        lifecycleGenericResponse = agent.subscribeToComponentUpdate(context);
                        break;
                    case DEFER_COMPONENT_UPDATE:
                        DeferComponentUpdateRequest deferUpdateRequest = CBOR_MAPPER
                                .readValue(applicationMessage.getPayload(), DeferComponentUpdateRequest.class);
                        lifecycleGenericResponse = agent.handleDeferComponentUpdateRequest(deferUpdateRequest, context);
                        break;
                    default:
                        lifecycleGenericResponse
                                .setErrorMessage("Unknown request type " + lifecycleClientOpCodes.toString());
                        break;
                }
            }
            ApplicationMessage responseMessage = ApplicationMessage.builder().version(applicationMessage.getVersion())
                    .payload(CBOR_MAPPER.writeValueAsBytes(lifecycleGenericResponse)).build();
            fut.complete(new Message(responseMessage.toByteArray()));
        } catch (Throwable e) {
            logger.atError().setEventType("lifecycle-error").setCause(e).log("Failed to handle message");
            try {
                LifecycleGenericResponse response = new LifecycleGenericResponse(LifecycleResponseStatus.InternalError,
                        e.getMessage());
                ApplicationMessage responseMessage =
                        ApplicationMessage.builder().version(applicationMessage.getVersion())
                                .payload(CBOR_MAPPER.writeValueAsBytes(response)).build();
                fut.complete(new Message(responseMessage.toByteArray()));
            } catch (IOException ex) {
                logger.atError().setEventType("lifecycle-error").setCause(ex).log("Failed to send error response");
            }
        }
        if (!fut.isDone()) {
            fut.completeExceptionally(new IPCException("Unable to serialize any responses"));
        }
        return fut;
    }

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
