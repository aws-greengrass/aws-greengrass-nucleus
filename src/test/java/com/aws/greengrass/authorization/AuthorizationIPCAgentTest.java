/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.authorization;

import com.aws.greengrass.ipc.AuthenticationHandler;
import com.aws.greengrass.ipc.exceptions.UnauthenticatedException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.aws.greengrass.model.InvalidTokenError;
import software.amazon.awssdk.aws.greengrass.model.UnauthorizedError;
import software.amazon.awssdk.aws.greengrass.model.ValidateAuthorizationTokenRequest;
import software.amazon.awssdk.eventstreamrpc.AuthenticationData;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;

import static com.aws.greengrass.authorization.AuthorizationIPCAgent.STREAM_MANAGER_SERVICE_NAME;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class AuthorizationIPCAgentTest {
    private static final String TEST_TOKEN = "token";
    @Mock
    OperationContinuationHandlerContext mockContext;

    @Mock
    AuthenticationHandler authenticationHandler;

    @Mock
    AuthenticationData mockAuthenticationData;

    private AuthorizationIPCAgent authorizationIPCAgent;

    @BeforeEach
    void setup() {
        when(mockContext.getAuthenticationData()).thenReturn(mockAuthenticationData);
        authorizationIPCAgent = new AuthorizationIPCAgent();
        authorizationIPCAgent.setAuthenticationHandler(authenticationHandler);
    }

    @Test
    void GIVEN_authentication_handler_WHEN_handle_request_valid_token_THEN_response() throws UnauthenticatedException {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(STREAM_MANAGER_SERVICE_NAME);
        ValidateAuthorizationTokenRequest request = new ValidateAuthorizationTokenRequest();
        request.setToken(TEST_TOKEN);
        when(authenticationHandler.doAuthentication(TEST_TOKEN)).thenReturn(STREAM_MANAGER_SERVICE_NAME);

        assertTrue(
                authorizationIPCAgent.getValidateAuthorizationTokenOperationHandler(mockContext).handleRequest(request)
                        .isIsValid());
    }

    @Test
    void GIVEN_authentication_handler_WHEN_handle_request_invalid_token_request_THEN_throw()
            throws UnauthenticatedException {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(STREAM_MANAGER_SERVICE_NAME);
        ValidateAuthorizationTokenRequest request = new ValidateAuthorizationTokenRequest();
        request.setToken(TEST_TOKEN);
        doThrow(new UnauthenticatedException("Invalid authentication token")).when(authenticationHandler)
                .doAuthentication(TEST_TOKEN);
        try (AuthorizationIPCAgent.ValidateAuthorizationTokenOperationHandler handler = authorizationIPCAgent
                .getValidateAuthorizationTokenOperationHandler(mockContext)) {
            assertThrows(InvalidTokenError.class, () -> handler.handleRequest(request));
        }
    }

    @Test
    void GIVEN_authentication_handler_WHEN_handle_request_unauthorized_service_THEN_throw() {
        when(mockAuthenticationData.getIdentityLabel()).thenReturn("service");
        ValidateAuthorizationTokenRequest request = new ValidateAuthorizationTokenRequest();
        request.setToken(TEST_TOKEN);
        try (AuthorizationIPCAgent.ValidateAuthorizationTokenOperationHandler handler = authorizationIPCAgent
                .getValidateAuthorizationTokenOperationHandler(mockContext)) {
            assertThrows(UnauthorizedError.class, () -> handler.handleRequest(request));
        }
    }
}
