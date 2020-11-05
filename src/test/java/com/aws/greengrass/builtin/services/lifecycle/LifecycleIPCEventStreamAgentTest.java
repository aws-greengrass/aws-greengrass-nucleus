/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.lifecycle;

import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.aws.greengrass.model.DeferComponentUpdateRequest;
import software.amazon.awssdk.aws.greengrass.model.DeferComponentUpdateResponse;
import software.amazon.awssdk.aws.greengrass.model.InvalidArgumentsError;
import software.amazon.awssdk.aws.greengrass.model.LifecycleState;
import software.amazon.awssdk.aws.greengrass.model.ResourceNotFoundError;
import software.amazon.awssdk.aws.greengrass.model.ServiceError;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToComponentUpdatesRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToComponentUpdatesResponse;
import software.amazon.awssdk.aws.greengrass.model.UpdateStateRequest;
import software.amazon.awssdk.aws.greengrass.model.UpdateStateResponse;
import software.amazon.awssdk.crt.eventstream.ServerConnectionContinuation;
import software.amazon.awssdk.eventstreamrpc.AuthenticationData;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class LifecycleIPCEventStreamAgentTest {

    private static final String TEST_SERVICE = "TestService";

    LifecycleIPCEventStreamAgent lifecycleIPCEventStreamAgent;

    @Mock
    Kernel kernel;

    @Mock
    OperationContinuationHandlerContext mockContext;

    @Mock
    AuthenticationData mockAuthenticationData;

    @BeforeEach
    public void setup() {
        when(mockContext.getContinuation()).thenReturn(mock(ServerConnectionContinuation.class));
        when(mockContext.getAuthenticationData()).thenReturn(mockAuthenticationData);
        when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_SERVICE);
        lifecycleIPCEventStreamAgent = new LifecycleIPCEventStreamAgent();
        lifecycleIPCEventStreamAgent.setKernel(kernel);
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void testUpdateStateHandler_successful_update() throws ServiceLoadException {
        UpdateStateRequest updateStateRequest = new UpdateStateRequest();
        updateStateRequest.setState(LifecycleState.ERRORED);
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
        updateStateRequest.setState(LifecycleState.ERRORED);
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
        CompletableFuture<DeferUpdateRequest> deferFuture = new CompletableFuture<>();
        lifecycleIPCEventStreamAgent.getDeferUpdateFuturesMap().put(new Pair<>(TEST_SERVICE, "A"), deferFuture);
        DeferComponentUpdateRequest deferComponentUpdateRequest = new DeferComponentUpdateRequest();
        deferComponentUpdateRequest.setMessage("Test defer");
        deferComponentUpdateRequest.setDeploymentId("A");
        deferComponentUpdateRequest.setRecheckAfterMs(1000L);
        DeferComponentUpdateResponse response1 = lifecycleIPCEventStreamAgent.getDeferComponentHandler(mockContext)
                .handleRequest(deferComponentUpdateRequest);
        assertNotNull(response1);
        DeferUpdateRequest request = deferFuture.get();
        assertEquals(TEST_SERVICE, request.getComponentName());
        assertEquals("A", request.getDeploymentId());
        assertEquals("Test defer", request.getMessage());
        assertEquals(1000L, request.getRecheckTimeInMs());
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
}
