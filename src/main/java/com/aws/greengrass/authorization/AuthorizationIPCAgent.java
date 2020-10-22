/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.authorization;

import com.aws.greengrass.ipc.AuthenticationHandler;
import com.aws.greengrass.ipc.exceptions.UnauthenticatedException;
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
                throw new UnauthorizedError(String.format("%s is not authorized to perform %s", serviceName,
                        this.getOperationModelContext().getOperationName()));
            }
            ValidateAuthorizationTokenResponse response = new ValidateAuthorizationTokenResponse();
            try {
                authenticationHandler.doAuthentication(request.getToken());
                response.setIsValid(true);
                return response;
            } catch (UnauthenticatedException e) {
                throw new InvalidTokenError(e.getMessage());
            }
        }
    }
}
