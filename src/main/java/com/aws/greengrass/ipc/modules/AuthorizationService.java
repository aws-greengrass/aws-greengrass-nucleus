/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc.modules;

import com.aws.greengrass.authorization.AuthorizationIPCAgent;
import com.aws.greengrass.dependency.InjectionActions;
import com.aws.greengrass.ipc.AuthenticationHandler;
import com.aws.greengrass.ipc.ConnectionContext;
import com.aws.greengrass.ipc.IPCRouter;
import com.aws.greengrass.ipc.Startable;
import com.aws.greengrass.ipc.authorization.AuthorizationRequest;
import com.aws.greengrass.ipc.authorization.AuthorizationResponse;
import com.aws.greengrass.ipc.common.BuiltInServiceDestinationCode;
import com.aws.greengrass.ipc.common.FrameReader.Message;
import com.aws.greengrass.ipc.exceptions.IPCException;
import com.aws.greengrass.ipc.exceptions.UnauthenticatedException;
import com.aws.greengrass.ipc.exceptions.UnauthorizedException;
import com.aws.greengrass.ipc.services.common.ApplicationMessage;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCService;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import javax.inject.Inject;

import static com.aws.greengrass.ipc.IPCRouter.DESTINATION_STRING;

public class AuthorizationService implements Startable, InjectionActions {
    private static final Logger logger = LogManager.getLogger(AuthorizationService.class);
    private static final ObjectMapper CBOR_MAPPER = new CBORMapper();

    @Inject
    private IPCRouter router;

    @Inject
    private AuthenticationHandler authenticationHandler;

    @Inject
    private GreengrassCoreIPCService greengrassCoreIPCService;

    @Inject
    private AuthorizationIPCAgent authorizationIPCAgent;

    @Override
    public void postInject() {
        BuiltInServiceDestinationCode destination = BuiltInServiceDestinationCode.AUTHORIZATION;

        try {
            router.registerServiceCallback(destination.getValue(), this::handleMessage);
            logger.atInfo("register-request-handler")
                    .kv(DESTINATION_STRING, destination.name())
                    .log();
        } catch (IPCException e) {
            logger.atError("register-request-handler-error", e)
                    .kv(DESTINATION_STRING, destination.name())
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
    @SuppressWarnings({"PMD.AvoidCatchingThrowable", "PMD.AvoidInstanceofChecksInCatchClause"})
    public Future<Message> handleMessage(Message message, ConnectionContext context) {

        ApplicationMessage applicationMessage = ApplicationMessage.fromBytes(message.getPayload());
        CompletableFuture<Message> fut = new CompletableFuture<>();
        AuthorizationResponse response;
        try {
            AuthorizationRequest request = CBOR_MAPPER.readValue(applicationMessage.getPayload(),
                    AuthorizationRequest.class);

            //This will throw an UnauthorizedException if not authenticated
            doAuthorize(request);
            response = new AuthorizationResponse(true, null);

            ApplicationMessage responseMessage = ApplicationMessage.builder()
                    .version(applicationMessage.getVersion())
                    .payload(CBOR_MAPPER.writeValueAsBytes(response))
                    .build();
            fut.complete(new Message(responseMessage.toByteArray()));

        } catch (Throwable e) {
            logger.atError("authorization-error", e).log("Failed to handle message");
            try {
                response = new AuthorizationResponse(false, e.toString());
                ApplicationMessage responseMessage = ApplicationMessage.builder()
                        .version(applicationMessage.getVersion())
                        .payload(CBOR_MAPPER.writeValueAsBytes(response))
                        .build();
                fut.complete(new Message(responseMessage.toByteArray()));
            } catch (IOException ex) {
                logger.atError("authorization-error", ex).log("Failed to send error response");
            }
        }

        if (!fut.isDone()) {
            fut.completeExceptionally(new IPCException("Unable to serialize any responses"));
        }
        return fut;
    }

    private void doAuthorize(AuthorizationRequest request) throws UnauthorizedException {
        try {
            authenticationHandler.doAuthentication(request.getToken());
        } catch (UnauthenticatedException e) {
            throw new UnauthorizedException("Unable to authorize request", e);
        }

    }

    @Override
    public void startup() {
        greengrassCoreIPCService.setValidateAuthorizationTokenHandler(
                context -> authorizationIPCAgent.getValidateAuthorizationTokenOperationHandler(context));
    }
}
