/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.authorization;

import com.aws.greengrass.ipc.AuthenticationHandler;
import com.aws.greengrass.ipc.exceptions.UnauthenticatedException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import lombok.AccessLevel;
import lombok.Setter;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractValidateAuthorizationTokenOperationHandler;
import software.amazon.awssdk.aws.greengrass.model.InvalidTokenError;
import software.amazon.awssdk.aws.greengrass.model.UnauthorizedError;
import software.amazon.awssdk.aws.greengrass.model.ValidateAuthorizationTokenRequest;
import software.amazon.awssdk.aws.greengrass.model.ValidateAuthorizationTokenResponse;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

import java.util.Collections;
import java.util.List;
import javax.inject.Inject;

/**
 * Class to handle business logic for authorization.
 */
public class AuthorizationIPCAgent {

    // This API can be used only by stream manager now
    public static final String STREAM_MANAGER_SERVICE_NAME = "aws.greengrass.StreamManager";
    private static final List<String> AUTHORIZED_COMPONENTS = Collections.singletonList(STREAM_MANAGER_SERVICE_NAME);

    private static final Logger logger = LogManager.getLogger(AuthorizationIPCAgent.class);
    @Inject
    @Setter(AccessLevel.PACKAGE)
    private AuthenticationHandler authenticationHandler;

    public ValidateAuthorizationTokenOperationHandler getValidateAuthorizationTokenOperationHandler(
            OperationContinuationHandlerContext context) {
        return new ValidateAuthorizationTokenOperationHandler(context);
    }

    @SuppressWarnings("PMD.PreserveStackTrace")
    class ValidateAuthorizationTokenOperationHandler
            extends GeneratedAbstractValidateAuthorizationTokenOperationHandler {
        private final String serviceName;

        protected ValidateAuthorizationTokenOperationHandler(OperationContinuationHandlerContext context) {
            super(context);
            serviceName = context.getAuthenticationData().getIdentityLabel();
        }

        @Override
        protected void onStreamClosed() {

        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {

        }

        @Override
        public ValidateAuthorizationTokenResponse handleRequest(ValidateAuthorizationTokenRequest request) {
            if (!AUTHORIZED_COMPONENTS.contains(serviceName)) {
                logger.atDebug("service-unauthorized-error").log("{} is not authorized to perform {}",
                        serviceName, this.getOperationModelContext().getOperationName());
                throw new UnauthorizedError(String.format("%s is not authorized to perform %s", serviceName,
                        this.getOperationModelContext().getOperationName()));
            }
            ValidateAuthorizationTokenResponse response = new ValidateAuthorizationTokenResponse();
            try {
                authenticationHandler.doAuthentication(request.getToken());
                response.setIsValid(true);
                logger.atDebug("authorization-validated").log("Authorization validated for {} for {}",
                        serviceName, this.getOperationModelContext().getOperationName());
                return response;
            } catch (UnauthenticatedException e) {
                logger.atDebug("invalid-token-error").log("Invalid token used when trying to authorize {} "
                                + "to perform {}", serviceName,  this.getOperationModelContext().getOperationName());
                throw new InvalidTokenError(e.getMessage());
            }
        }
    }
}
