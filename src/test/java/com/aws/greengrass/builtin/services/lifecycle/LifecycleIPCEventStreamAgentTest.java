/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.lifecycle;

import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.Permission;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.lifecyclemanager.GenericExternalService;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceException;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.DisabledOnAndroid;
import com.aws.greengrass.util.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCService;
import software.amazon.awssdk.aws.greengrass.model.DeferComponentUpdateRequest;
import software.amazon.awssdk.aws.greengrass.model.DeferComponentUpdateResponse;
import software.amazon.awssdk.aws.greengrass.model.InvalidArgumentsError;
import software.amazon.awssdk.aws.greengrass.model.PauseComponentRequest;
import software.amazon.awssdk.aws.greengrass.model.ReportedLifecycleState;
import software.amazon.awssdk.aws.greengrass.model.ResourceNotFoundError;
import software.amazon.awssdk.aws.greengrass.model.ResumeComponentRequest;
import software.amazon.awssdk.aws.greengrass.model.ServiceError;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToComponentUpdatesRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToComponentUpdatesResponse;
import software.amazon.awssdk.aws.greengrass.model.UnauthorizedError;
import software.amazon.awssdk.aws.greengrass.model.UpdateStateRequest;
import software.amazon.awssdk.aws.greengrass.model.UpdateStateResponse;
import software.amazon.awssdk.crt.eventstream.ServerConnectionContinuation;
import software.amazon.awssdk.eventstreamrpc.AuthenticationData;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static com.aws.greengrass.ipc.modules.LifecycleIPCService.LIFECYCLE_SERVICE_NAME;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class LifecycleIPCEventStreamAgentTest {

    private static final String TEST_SERVICE = "TestService";
    private static final String TEST_TARGET_COMPONENT = "TestTargetComponent";

    LifecycleIPCEventStreamAgent lifecycleIPCEventStreamAgent;

    @Mock
    Kernel kernel;

    @Mock
    OperationContinuationHandlerContext mockContext;

    @Mock
    AuthenticationData mockAuthenticationData;

    @Mock
    AuthorizationHandler authorizationHandler;

    @Mock
    GenericExternalService targetComponent;

    @BeforeEach
    void setup() {
        when(mockContext.getContinuation()).thenReturn(mock(ServerConnectionContinuation.class));
        when(mockContext.getAuthenticationData()).thenReturn(mockAuthenticationData);
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_SERVICE);
        lifecycleIPCEventStreamAgent = new LifecycleIPCEventStreamAgent();
        lifecycleIPCEventStreamAgent.setKernel(kernel);
        lifecycleIPCEventStreamAgent.setAuthorizationHandler(authorizationHandler);
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void testUpdateStateHandler_successful_update() throws ServiceLoadException {
        UpdateStateRequest updateStateRequest = new UpdateStateRequest();
        updateStateRequest.setState(ReportedLifecycleState.ERRORED);
        GreengrassService mockTestService = mock(GreengrassService.class);
        when(kernel.locate(TEST_SERVICE)).thenReturn(mockTestService);
        UpdateStateResponse response =
                lifecycleIPCEventStreamAgent.getUpdateStateOperationHandler(mockContext).handleRequest(updateStateRequest);
        assertNotNull(response);
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void testUpdateStateHandler_service_not_found() throws ServiceLoadException {
        UpdateStateRequest updateStateRequest = new UpdateStateRequest();
        updateStateRequest.setState(ReportedLifecycleState.ERRORED);
        when(kernel.locate(TEST_SERVICE)).thenThrow(new ServiceLoadException("error"));
        assertThrows(ResourceNotFoundError.class,
                () -> lifecycleIPCEventStreamAgent.getUpdateStateOperationHandler(mockContext).handleRequest(updateStateRequest));
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void testSubscribeToComponent_successful_request() {
        SubscribeToComponentUpdatesRequest subsRequest = new SubscribeToComponentUpdatesRequest();
        LifecycleIPCEventStreamAgent.SubscribeToComponentUpdateOperationHandler handler =
                lifecycleIPCEventStreamAgent.getSubscribeToComponentUpdateHandler(mockContext);
        SubscribeToComponentUpdatesResponse response = handler.handleRequest(subsRequest);
        assertTrue(lifecycleIPCEventStreamAgent.getComponentUpdateListeners().containsKey(TEST_SERVICE));
        assertTrue(lifecycleIPCEventStreamAgent.getComponentUpdateListeners().get(TEST_SERVICE).contains(handler));
        assertNotNull(response);
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void testSubscribeToComponent_on_stream_closure() {
        SubscribeToComponentUpdatesRequest subsRequest = new SubscribeToComponentUpdatesRequest();
        LifecycleIPCEventStreamAgent.SubscribeToComponentUpdateOperationHandler handler =
                lifecycleIPCEventStreamAgent.getSubscribeToComponentUpdateHandler(mockContext);
        handler.handleRequest(subsRequest);
        assertTrue(lifecycleIPCEventStreamAgent.getComponentUpdateListeners().containsKey(TEST_SERVICE));
        assertTrue(lifecycleIPCEventStreamAgent.getComponentUpdateListeners().get(TEST_SERVICE).contains(handler));
        handler.onStreamClosed();
        assertFalse(lifecycleIPCEventStreamAgent.getComponentUpdateListeners().containsKey(TEST_SERVICE));
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void testSubscribeToComponent_request_from_removed_service(ExtensionContext context) throws ServiceLoadException {
        ignoreExceptionOfType(context, ServiceLoadException.class);
        SubscribeToComponentUpdatesRequest subsRequest = new SubscribeToComponentUpdatesRequest();
        LifecycleIPCEventStreamAgent.SubscribeToComponentUpdateOperationHandler handler =
                lifecycleIPCEventStreamAgent.getSubscribeToComponentUpdateHandler(mockContext);
        when(kernel.locate(TEST_SERVICE)).thenThrow(new ServiceLoadException("Not found"));
        assertThrows(ResourceNotFoundError.class, () -> handler.handleRequest(subsRequest));
    }

    @Test
    void testDeferComponentUpdateHandler_defer_without_subscribing() {
        DeferComponentUpdateRequest deferComponentUpdateRequest = new DeferComponentUpdateRequest();
        deferComponentUpdateRequest.setMessage("Test defer");
        deferComponentUpdateRequest.setRecheckAfterMs(1000L);
        assertThrows(InvalidArgumentsError.class, () ->
                lifecycleIPCEventStreamAgent.getDeferComponentHandler(mockContext)
                        .handleRequest(deferComponentUpdateRequest));
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void testUpdateStateHandler_subscribe_then_defer() throws ExecutionException, InterruptedException {
        SubscribeToComponentUpdatesRequest subsRequest = new SubscribeToComponentUpdatesRequest();
        LifecycleIPCEventStreamAgent.SubscribeToComponentUpdateOperationHandler handler =
                lifecycleIPCEventStreamAgent.getSubscribeToComponentUpdateHandler(mockContext);
        SubscribeToComponentUpdatesResponse response = handler.handleRequest(subsRequest);
        assertNotNull(response);
        CompletableFuture<DeferComponentUpdateRequest> deferFuture = new CompletableFuture<>();
        lifecycleIPCEventStreamAgent.getDeferUpdateFuturesMap().put(new Pair<>(TEST_SERVICE, "A"), deferFuture);
        DeferComponentUpdateRequest deferComponentUpdateRequest = new DeferComponentUpdateRequest();
        deferComponentUpdateRequest.setMessage("Test defer");
        deferComponentUpdateRequest.setDeploymentId("A");
        deferComponentUpdateRequest.setRecheckAfterMs(1000L);
        DeferComponentUpdateResponse response1 = lifecycleIPCEventStreamAgent.getDeferComponentHandler(mockContext)
                .handleRequest(deferComponentUpdateRequest);
        assertNotNull(response1);
        DeferComponentUpdateRequest request = deferFuture.get();
        assertEquals("A", request.getDeploymentId());
        assertEquals("Test defer", request.getMessage());
        assertEquals(1000L, request.getRecheckAfterMs());
        assertFalse(lifecycleIPCEventStreamAgent.getDeferUpdateFuturesMap()
                .containsKey(new Pair<>(TEST_SERVICE, "A")));
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void testUpdateStateHandler_subscribe_then_defer_when_future_no_longer_waiting() {
        SubscribeToComponentUpdatesRequest subsRequest = new SubscribeToComponentUpdatesRequest();
        LifecycleIPCEventStreamAgent.SubscribeToComponentUpdateOperationHandler handler =
                lifecycleIPCEventStreamAgent.getSubscribeToComponentUpdateHandler(mockContext);
        SubscribeToComponentUpdatesResponse response = handler.handleRequest(subsRequest);
        assertNotNull(response);
        DeferComponentUpdateRequest deferComponentUpdateRequest = new DeferComponentUpdateRequest();
        deferComponentUpdateRequest.setMessage("Test defer");
        deferComponentUpdateRequest.setDeploymentId("abc");
        deferComponentUpdateRequest.setRecheckAfterMs(1000L);
        assertThrows(ServiceError.class, () -> lifecycleIPCEventStreamAgent.getDeferComponentHandler(mockContext)
                .handleRequest(deferComponentUpdateRequest));
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void GIVEN_defer_request_without_deployment_id_THEN_fail() {
        DeferComponentUpdateRequest deferComponentUpdateRequest = new DeferComponentUpdateRequest();
        deferComponentUpdateRequest.setMessage("Test defer");
        deferComponentUpdateRequest.setRecheckAfterMs(1000L);
        assertThrows(InvalidArgumentsError.class, () -> lifecycleIPCEventStreamAgent.getDeferComponentHandler(mockContext)
                .handleRequest(deferComponentUpdateRequest));
    }

    // Pause component tests
    @Test
    @DisabledOnAndroid
    @EnabledOnOs(OS.LINUX)
    void GIVEN_pause_component_request_WHEN_successful_THEN_return_response()
            throws ServiceException, AuthorizationException {
        when(kernel.locate(TEST_TARGET_COMPONENT)).thenReturn(targetComponent);
        when(targetComponent.getState()).thenReturn(State.RUNNING);
        when(authorizationHandler.isAuthorized(any(), any())).thenReturn(true);

        PauseComponentRequest request = new PauseComponentRequest();
        request.setComponentName(TEST_TARGET_COMPONENT);
        assertNotNull(lifecycleIPCEventStreamAgent.getPauseComponentHandler(mockContext).handleRequest(request));

        ArgumentCaptor<Permission> permissionArg = ArgumentCaptor.forClass(Permission.class);
        verify(authorizationHandler).isAuthorized(eq(LIFECYCLE_SERVICE_NAME), permissionArg.capture());
        Permission permission = permissionArg.getValue();
        assertThat(permission.getOperation(), is(GreengrassCoreIPCService.PAUSE_COMPONENT));
        assertThat(permission.getPrincipal(), is(TEST_SERVICE));
        assertThat(permission.getResource(), is(TEST_TARGET_COMPONENT));

        verify(kernel).locate(TEST_TARGET_COMPONENT);
        verify(targetComponent).getState();
        verify(targetComponent).pause();
    }

    @Test
    @DisabledOnAndroid
    @EnabledOnOs(OS.LINUX)
    void GIVEN_pause_component_request_WHEN_failure_THEN_return_service_error()
            throws AuthorizationException, ServiceException {
        when(kernel.locate(TEST_TARGET_COMPONENT)).thenReturn(targetComponent);
        when(targetComponent.getState()).thenReturn(State.RUNNING);
        doThrow(new ServiceException("Failed to pause")).when(targetComponent).pause();
        when(authorizationHandler.isAuthorized(any(), any())).thenReturn(true);

        PauseComponentRequest request = new PauseComponentRequest();
        request.setComponentName(TEST_TARGET_COMPONENT);
        assertThrows(ServiceError.class, () ->
                lifecycleIPCEventStreamAgent.getPauseComponentHandler(mockContext).handleRequest(request));

        ArgumentCaptor<Permission> permissionArg = ArgumentCaptor.forClass(Permission.class);
        verify(authorizationHandler).isAuthorized(eq(LIFECYCLE_SERVICE_NAME), permissionArg.capture());
        Permission permission = permissionArg.getValue();
        assertThat(permission.getOperation(), is(GreengrassCoreIPCService.PAUSE_COMPONENT));
        assertThat(permission.getPrincipal(), is(TEST_SERVICE));
        assertThat(permission.getResource(), is(TEST_TARGET_COMPONENT));

        verify(kernel).locate(TEST_TARGET_COMPONENT);
        verify(targetComponent).getState();
        verify(targetComponent).pause();
    }

    @Test
    @DisabledOnAndroid
    @EnabledOnOs(OS.LINUX)
    void GIVEN_pause_component_request_WHEN_component_name_input_not_present_THEN_return_invalid_error()
            throws AuthorizationException, ServiceException {
        assertThrows(InvalidArgumentsError.class, () ->
                lifecycleIPCEventStreamAgent.getPauseComponentHandler(mockContext)
                        .handleRequest(new PauseComponentRequest()));

        verify(authorizationHandler, never()).isAuthorized(any(), any());
        verify(kernel, never()).locate(TEST_TARGET_COMPONENT);
        verify(targetComponent, never()).getState();
        verify(targetComponent, never()).pause();
    }

    @Test
    @DisabledOnAndroid
    @EnabledOnOs(OS.LINUX)
    void GIVEN_pause_component_request_WHEN_unauthorized_THEN_return_auth_error()
            throws AuthorizationException, ServiceException {
        when(authorizationHandler.isAuthorized(any(), any())).thenThrow(new AuthorizationException("Unauthorized"));

        PauseComponentRequest request = new PauseComponentRequest();
        request.setComponentName(TEST_TARGET_COMPONENT);
        assertThrows(UnauthorizedError.class, () ->
                lifecycleIPCEventStreamAgent.getPauseComponentHandler(mockContext).handleRequest(request));

        ArgumentCaptor<Permission> permissionArg = ArgumentCaptor.forClass(Permission.class);
        verify(authorizationHandler).isAuthorized(eq(LIFECYCLE_SERVICE_NAME), permissionArg.capture());
        Permission permission = permissionArg.getValue();
        assertThat(permission.getOperation(), is(GreengrassCoreIPCService.PAUSE_COMPONENT));
        assertThat(permission.getPrincipal(), is(TEST_SERVICE));
        assertThat(permission.getResource(), is(TEST_TARGET_COMPONENT));

        verify(kernel, never()).locate(TEST_TARGET_COMPONENT);
        verify(targetComponent, never()).getState();
        verify(targetComponent, never()).pause();
    }

    @Test
    @DisabledOnAndroid
    @EnabledOnOs(OS.LINUX)
    void GIVEN_pause_component_request_WHEN_component_not_present_THEN_return_resource_not_found_error()
            throws ServiceException, AuthorizationException {
        when(kernel.locate(TEST_TARGET_COMPONENT)).thenThrow(new ServiceLoadException("Failed to load"));
        when(authorizationHandler.isAuthorized(any(), any())).thenReturn(true);

        PauseComponentRequest request = new PauseComponentRequest();
        request.setComponentName(TEST_TARGET_COMPONENT);
        assertThrows(ResourceNotFoundError.class, () ->
                lifecycleIPCEventStreamAgent.getPauseComponentHandler(mockContext).handleRequest(request));

        ArgumentCaptor<Permission> permissionArg = ArgumentCaptor.forClass(Permission.class);
        verify(authorizationHandler).isAuthorized(eq(LIFECYCLE_SERVICE_NAME), permissionArg.capture());
        Permission permission = permissionArg.getValue();
        assertThat(permission.getOperation(), is(GreengrassCoreIPCService.PAUSE_COMPONENT));
        assertThat(permission.getPrincipal(), is(TEST_SERVICE));
        assertThat(permission.getResource(), is(TEST_TARGET_COMPONENT));

        verify(kernel).locate(TEST_TARGET_COMPONENT);
        verify(targetComponent, never()).getState();
        verify(targetComponent, never()).pause();
    }

    @Test
    @DisabledOnAndroid
    @EnabledOnOs(OS.LINUX)
    void GIVEN_pause_component_request_WHEN_component_not_running_THEN_return_invalid_error()
            throws ServiceException, AuthorizationException {
        when(kernel.locate(TEST_TARGET_COMPONENT)).thenReturn(targetComponent);
        when(targetComponent.getState()).thenReturn(State.FINISHED);
        when(authorizationHandler.isAuthorized(any(), any())).thenReturn(true);

        PauseComponentRequest request = new PauseComponentRequest();
        request.setComponentName(TEST_TARGET_COMPONENT);
        assertThrows(InvalidArgumentsError.class, () ->
                lifecycleIPCEventStreamAgent.getPauseComponentHandler(mockContext).handleRequest(request));

        ArgumentCaptor<Permission> permissionArg = ArgumentCaptor.forClass(Permission.class);
        verify(authorizationHandler).isAuthorized(eq(LIFECYCLE_SERVICE_NAME), permissionArg.capture());
        Permission permission = permissionArg.getValue();
        assertThat(permission.getOperation(), is(GreengrassCoreIPCService.PAUSE_COMPONENT));
        assertThat(permission.getPrincipal(), is(TEST_SERVICE));
        assertThat(permission.getResource(), is(TEST_TARGET_COMPONENT));

        verify(kernel).locate(TEST_TARGET_COMPONENT);
        verify(targetComponent).getState();
        verify(targetComponent, never()).pause();
    }

    @Test
    @DisabledOnAndroid
    @EnabledOnOs(OS.LINUX)
    void GIVEN_pause_component_request_WHEN_component_not_external_THEN_return_invalid_error()
            throws ServiceException, AuthorizationException {
        GreengrassService mockInternalComponent = mock(GreengrassService.class);
        when(kernel.locate(TEST_TARGET_COMPONENT)).thenReturn(mockInternalComponent);
        when(authorizationHandler.isAuthorized(any(), any())).thenReturn(true);

        PauseComponentRequest request = new PauseComponentRequest();
        request.setComponentName(TEST_TARGET_COMPONENT);
        assertThrows(InvalidArgumentsError.class, () ->
                lifecycleIPCEventStreamAgent.getPauseComponentHandler(mockContext).handleRequest(request));

        ArgumentCaptor<Permission> permissionArg = ArgumentCaptor.forClass(Permission.class);
        verify(authorizationHandler).isAuthorized(eq(LIFECYCLE_SERVICE_NAME), permissionArg.capture());
        Permission permission = permissionArg.getValue();
        assertThat(permission.getOperation(), is(GreengrassCoreIPCService.PAUSE_COMPONENT));
        assertThat(permission.getPrincipal(), is(TEST_SERVICE));
        assertThat(permission.getResource(), is(TEST_TARGET_COMPONENT));

        verify(kernel).locate(TEST_TARGET_COMPONENT);
        verify(targetComponent, never()).getState();
        verify(targetComponent, never()).pause();
    }

    @Test
    @DisabledOnOs(OS.LINUX)
    void GIVEN_pause_component_request_WHEN_not_on_linux_THEN_throws_unsupported_operation_exception() {
        PauseComponentRequest request = new PauseComponentRequest();
        request.setComponentName(TEST_TARGET_COMPONENT);

        ServiceError exception = assertThrows(ServiceError.class,
                () -> lifecycleIPCEventStreamAgent.getPauseComponentHandler(mockContext).handleRequest(request));
        assertThat(exception.getMessage(), containsString("Pause/resume component not supported on this platform"));
    }

    // Resume component tests
    @Test
    @DisabledOnAndroid
    @EnabledOnOs(OS.LINUX)
    void GIVEN_resume_component_request_WHEN_successful_THEN_return_response()
            throws AuthorizationException, ServiceException {
        when(kernel.locate(TEST_TARGET_COMPONENT)).thenReturn(targetComponent);
        when(targetComponent.isPaused()).thenReturn(true);
        when(authorizationHandler.isAuthorized(any(), any())).thenReturn(true);

        ResumeComponentRequest request = new ResumeComponentRequest();
        request.setComponentName(TEST_TARGET_COMPONENT);
        assertNotNull(lifecycleIPCEventStreamAgent.getResumeComponentHandler(mockContext).handleRequest(request));

        ArgumentCaptor<Permission> permissionArg = ArgumentCaptor.forClass(Permission.class);
        verify(authorizationHandler).isAuthorized(eq(LIFECYCLE_SERVICE_NAME), permissionArg.capture());
        Permission permission = permissionArg.getValue();
        assertThat(permission.getOperation(), is(GreengrassCoreIPCService.RESUME_COMPONENT));
        assertThat(permission.getPrincipal(), is(TEST_SERVICE));
        assertThat(permission.getResource(), is(TEST_TARGET_COMPONENT));

        verify(kernel).locate(TEST_TARGET_COMPONENT);
        verify(targetComponent).isPaused();
        verify(targetComponent).resume();
    }

    @Test
    @DisabledOnAndroid
    @EnabledOnOs(OS.LINUX)
    void GIVEN_resume_component_request_WHEN_failure_THEN_return_service_error()
            throws AuthorizationException, ServiceException {
        when(kernel.locate(TEST_TARGET_COMPONENT)).thenReturn(targetComponent);
        when(targetComponent.isPaused()).thenReturn(true);
        doThrow(new ServiceException("Failed to resume")).when(targetComponent).resume();
        when(authorizationHandler.isAuthorized(any(), any())).thenReturn(true);

        ResumeComponentRequest request = new ResumeComponentRequest();
        request.setComponentName(TEST_TARGET_COMPONENT);
        assertThrows(ServiceError.class, () ->
                lifecycleIPCEventStreamAgent.getResumeComponentHandler(mockContext).handleRequest(request));

        ArgumentCaptor<Permission> permissionArg = ArgumentCaptor.forClass(Permission.class);
        verify(authorizationHandler).isAuthorized(eq(LIFECYCLE_SERVICE_NAME), permissionArg.capture());
        Permission permission = permissionArg.getValue();
        assertThat(permission.getOperation(), is(GreengrassCoreIPCService.RESUME_COMPONENT));
        assertThat(permission.getPrincipal(), is(TEST_SERVICE));
        assertThat(permission.getResource(), is(TEST_TARGET_COMPONENT));

        verify(kernel).locate(TEST_TARGET_COMPONENT);
        verify(targetComponent).isPaused();
        verify(targetComponent).resume();
    }

    @Test
    @DisabledOnAndroid
    @EnabledOnOs(OS.LINUX)
    void GIVEN_resume_component_request_WHEN_component_name_input_not_present_THEN_return_invalid_error()
            throws ServiceException, AuthorizationException {
        ResumeComponentRequest request = new ResumeComponentRequest();
        assertThrows(InvalidArgumentsError.class, () ->
                lifecycleIPCEventStreamAgent.getResumeComponentHandler(mockContext).handleRequest(request));

        verify(authorizationHandler, never()).isAuthorized(any(), any());
        verify(kernel, never()).locate(TEST_TARGET_COMPONENT);
        verify(targetComponent, never()).isPaused();
        verify(targetComponent, never()).resume();
    }

    @Test
    @DisabledOnAndroid
    @EnabledOnOs(OS.LINUX)
    void GIVEN_resume_component_request_WHEN_unauthorized_THEN_return_auth_error()
            throws AuthorizationException, ServiceException {
        when(authorizationHandler.isAuthorized(any(), any())).thenThrow(new AuthorizationException("Unauthorized"));

        ResumeComponentRequest request = new ResumeComponentRequest();
        request.setComponentName(TEST_TARGET_COMPONENT);
        assertThrows(UnauthorizedError.class, () ->
                lifecycleIPCEventStreamAgent.getResumeComponentHandler(mockContext).handleRequest(request));

        ArgumentCaptor<Permission> permissionArg = ArgumentCaptor.forClass(Permission.class);
        verify(authorizationHandler).isAuthorized(eq(LIFECYCLE_SERVICE_NAME), permissionArg.capture());
        Permission permission = permissionArg.getValue();
        assertThat(permission.getOperation(), is(GreengrassCoreIPCService.RESUME_COMPONENT));
        assertThat(permission.getPrincipal(), is(TEST_SERVICE));
        assertThat(permission.getResource(), is(TEST_TARGET_COMPONENT));

        verify(kernel, never()).locate(TEST_TARGET_COMPONENT);
        verify(targetComponent, never()).isPaused();
        verify(targetComponent, never()).resume();
    }

    @Test
    @DisabledOnAndroid
    @EnabledOnOs(OS.LINUX)
    void GIVEN_resume_component_request_WHEN_component_not_present_THEN_return_resource_not_found_error()
            throws ServiceException, AuthorizationException {
        when(kernel.locate(TEST_TARGET_COMPONENT)).thenThrow(new ServiceLoadException("Failed to load"));
        when(authorizationHandler.isAuthorized(any(), any())).thenReturn(true);

        ResumeComponentRequest request = new ResumeComponentRequest();
        request.setComponentName(TEST_TARGET_COMPONENT);
        assertThrows(ResourceNotFoundError.class, () ->
                lifecycleIPCEventStreamAgent.getResumeComponentHandler(mockContext).handleRequest(request));

        ArgumentCaptor<Permission> permissionArg = ArgumentCaptor.forClass(Permission.class);
        verify(authorizationHandler).isAuthorized(eq(LIFECYCLE_SERVICE_NAME), permissionArg.capture());
        Permission permission = permissionArg.getValue();
        assertThat(permission.getOperation(), is(GreengrassCoreIPCService.RESUME_COMPONENT));
        assertThat(permission.getPrincipal(), is(TEST_SERVICE));
        assertThat(permission.getResource(), is(TEST_TARGET_COMPONENT));

        verify(kernel).locate(TEST_TARGET_COMPONENT);
        verify(targetComponent, never()).isPaused();
        verify(targetComponent, never()).resume();
    }

    @Test
    @DisabledOnAndroid
    @EnabledOnOs(OS.LINUX)
    void GIVEN_resume_component_request_WHEN_component_not_paused_THEN_return_invalid_error()
            throws ServiceException, AuthorizationException {
        when(kernel.locate(TEST_TARGET_COMPONENT)).thenReturn(targetComponent);
        when(targetComponent.isPaused()).thenReturn(false);
        when(authorizationHandler.isAuthorized(any(), any())).thenReturn(true);

        ResumeComponentRequest request = new ResumeComponentRequest();
        request.setComponentName(TEST_TARGET_COMPONENT);
        assertThrows(InvalidArgumentsError.class, () ->
                lifecycleIPCEventStreamAgent.getResumeComponentHandler(mockContext).handleRequest(request));

        ArgumentCaptor<Permission> permissionArg = ArgumentCaptor.forClass(Permission.class);
        verify(authorizationHandler).isAuthorized(eq(LIFECYCLE_SERVICE_NAME), permissionArg.capture());
        Permission permission = permissionArg.getValue();
        assertThat(permission.getOperation(), is(GreengrassCoreIPCService.RESUME_COMPONENT));
        assertThat(permission.getPrincipal(), is(TEST_SERVICE));
        assertThat(permission.getResource(), is(TEST_TARGET_COMPONENT));

        verify(kernel).locate(TEST_TARGET_COMPONENT);
        verify(targetComponent).isPaused();
        verify(targetComponent, never()).resume();
    }

    @Test
    @DisabledOnAndroid
    @EnabledOnOs(OS.LINUX)
    void GIVEN_resume_component_request_WHEN_component_not_external_THEN_return_invalid_error()
            throws ServiceException, AuthorizationException {
        GreengrassService mockInternalComponent = mock(GreengrassService.class);
        when(kernel.locate(TEST_TARGET_COMPONENT)).thenReturn(mockInternalComponent);
        when(authorizationHandler.isAuthorized(any(), any())).thenReturn(true);

        ResumeComponentRequest request = new ResumeComponentRequest();
        request.setComponentName(TEST_TARGET_COMPONENT);
        assertThrows(InvalidArgumentsError.class, () ->
                lifecycleIPCEventStreamAgent.getResumeComponentHandler(mockContext).handleRequest(request));

        ArgumentCaptor<Permission> permissionArg = ArgumentCaptor.forClass(Permission.class);
        verify(authorizationHandler).isAuthorized(eq(LIFECYCLE_SERVICE_NAME), permissionArg.capture());
        Permission permission = permissionArg.getValue();
        assertThat(permission.getOperation(), is(GreengrassCoreIPCService.RESUME_COMPONENT));
        assertThat(permission.getPrincipal(), is(TEST_SERVICE));
        assertThat(permission.getResource(), is(TEST_TARGET_COMPONENT));

        verify(kernel).locate(TEST_TARGET_COMPONENT);
        verify(targetComponent, never()).isPaused();
        verify(targetComponent, never()).resume();
    }

    @Test
    @DisabledOnOs(OS.LINUX)
    void GIVEN_resume_component_request_WHEN_not_on_linux_THEN_throws_unsupported_operation_exception() {
        ResumeComponentRequest request = new ResumeComponentRequest();
        request.setComponentName(TEST_TARGET_COMPONENT);

        ServiceError exception = assertThrows(ServiceError.class,
                () -> lifecycleIPCEventStreamAgent.getResumeComponentHandler(mockContext).handleRequest(request));
        assertThat(exception.getMessage(), containsString("Pause/resume component not supported on this platform"));
    }

}
