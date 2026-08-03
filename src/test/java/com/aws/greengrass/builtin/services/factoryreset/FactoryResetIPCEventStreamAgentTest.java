/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.factoryreset;

import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.Permission;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCService;
import software.amazon.awssdk.aws.greengrass.model.FactoryResetRequest;
import software.amazon.awssdk.aws.greengrass.model.FactoryResetResponse;
import software.amazon.awssdk.aws.greengrass.model.ServiceError;
import software.amazon.awssdk.aws.greengrass.model.UnauthorizedError;
import software.amazon.awssdk.crt.eventstream.ServerConnectionContinuation;
import software.amazon.awssdk.eventstreamrpc.AuthenticationData;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;

import static com.aws.greengrass.ipc.modules.FactoryResetIPCService.FACTORY_RESET_SERVICE_NAME;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class FactoryResetIPCEventStreamAgentTest {

    private static final String TEST_CALLER = "com.example.MyAdminComponent";

    FactoryResetIPCEventStreamAgent agent;

    @Mock
    FactoryResetAgent factoryResetAgent;

    @Mock
    AuthorizationHandler authorizationHandler;

    @Mock
    OperationContinuationHandlerContext mockContext;

    @Mock
    AuthenticationData mockAuthenticationData;

    @BeforeEach
    void setup() {
        when(mockContext.getContinuation()).thenReturn(mock(ServerConnectionContinuation.class));
        when(mockContext.getAuthenticationData()).thenReturn(mockAuthenticationData);
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_CALLER);

        agent = new FactoryResetIPCEventStreamAgent();
        agent.setFactoryResetAgent(factoryResetAgent);
        agent.setAuthorizationHandler(authorizationHandler);
    }

    // -----------------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void GIVEN_authorized_caller_WHEN_factory_reset_requested_THEN_agent_called_and_initiated_returned()
            throws AuthorizationException {
        when(authorizationHandler.isAuthorized(any(), any())).thenReturn(true);

        FactoryResetResponse response =
                agent.getFactoryResetHandler(mockContext).handleRequest(new FactoryResetRequest());

        assertNotNull(response);
        assertEquals("INITIATED", response.getStatus());

        // Verify correct authorization was checked
        ArgumentCaptor<Permission> permCaptor = ArgumentCaptor.forClass(Permission.class);
        verify(authorizationHandler).isAuthorized(eq(FACTORY_RESET_SERVICE_NAME), permCaptor.capture());
        Permission perm = permCaptor.getValue();
        assertThat(perm.getPrincipal(), is(TEST_CALLER));
        assertThat(perm.getOperation(), equalTo(GreengrassCoreIPCService.FACTORY_RESET));
        assertThat(perm.getResource(), is("*"));

        // Verify the agent was invoked
        verify(factoryResetAgent).performFactoryReset();
    }

    // -----------------------------------------------------------------------
    // Authorization failure
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void GIVEN_unauthorized_caller_WHEN_factory_reset_requested_THEN_unauthorized_error_thrown_and_agent_not_called()
            throws AuthorizationException {
        doThrow(new AuthorizationException("not allowed"))
                .when(authorizationHandler).isAuthorized(any(), any());

        assertThrows(UnauthorizedError.class,
                () -> agent.getFactoryResetHandler(mockContext).handleRequest(new FactoryResetRequest()));

        // Agent must NOT be called when authorization fails
        verify(factoryResetAgent, never()).performFactoryReset();

        // Authorization was still attempted
        ArgumentCaptor<Permission> permCaptor = ArgumentCaptor.forClass(Permission.class);
        verify(authorizationHandler).isAuthorized(eq(FACTORY_RESET_SERVICE_NAME), permCaptor.capture());
        assertThat(permCaptor.getValue().getPrincipal(), is(TEST_CALLER));
    }

    // -----------------------------------------------------------------------
    // Agent failure
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void GIVEN_authorized_caller_WHEN_agent_throws_THEN_service_error_returned()
            throws AuthorizationException {
        when(authorizationHandler.isAuthorized(any(), any())).thenReturn(true);
        doThrow(new IllegalStateException("snapshot missing"))
                .when(factoryResetAgent).performFactoryReset();

        assertThrows(ServiceError.class,
                () -> agent.getFactoryResetHandler(mockContext).handleRequest(new FactoryResetRequest()));

        verify(factoryResetAgent).performFactoryReset();
    }
}
