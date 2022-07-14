/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */


package com.aws.greengrass.builtin.services.telemetry;

import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.Permission;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCService;
import software.amazon.awssdk.aws.greengrass.model.InvalidArgumentsError;
import software.amazon.awssdk.aws.greengrass.model.Metric;
import software.amazon.awssdk.aws.greengrass.model.PutComponentMetricRequest;
import software.amazon.awssdk.aws.greengrass.model.PutComponentMetricResponse;
import software.amazon.awssdk.aws.greengrass.model.UnauthorizedError;
import software.amazon.awssdk.crt.eventstream.ServerConnectionContinuation;
import software.amazon.awssdk.eventstreamrpc.AuthenticationData;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static com.aws.greengrass.ipc.modules.ComponentMetricIPCService.PUT_COMPONENT_METRIC_SERVICE_NAME;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class ComponentMetricIPCEventStreamAgentTest {
    private static final String VALID_TEST_COMPONENT = "aws.greengrass.testcomponent";
    private static final String STREAM_MANAGER_COMPONENT = "aws.greengrass.StreamManager";
    private static final String INVALID_TEST_COMPONENT = "testcomponent";
    private static final Random RANDOM = new Random();

    @Mock
    OperationContinuationHandlerContext mockContext;
    @Mock
    AuthenticationData mockAuthenticationData;
    @Mock
    AuthorizationHandler authorizationHandler;
    @Captor
    ArgumentCaptor<Permission> permissionArgumentCaptor;

    final ExecutorService pool = Executors.newCachedThreadPool();
    private ComponentMetricIPCEventStreamAgent componentMetricIPCEventStreamAgent;
    private PutComponentMetricRequest validComponentMetricRequest;

    @BeforeEach
    public void setup() {
        validComponentMetricRequest = generateComponentRequest("BytesPerSecond");
        lenient().when(mockContext.getContinuation()).thenReturn(mock(ServerConnectionContinuation.class));
        lenient().when(mockContext.getAuthenticationData()).thenReturn(mockAuthenticationData);
        componentMetricIPCEventStreamAgent = new ComponentMetricIPCEventStreamAgent(authorizationHandler);
    }

    @AfterEach
    void afterEach() {
        pool.shutdownNow();
    }


    @Test
    void GIVEN_put_component_metric_request_with_valid_service_WHEN_handle_request_called_THEN_telemetry_metrics_published()
            throws AuthorizationException {
        when(authorizationHandler.isAuthorized(any(), any())).thenReturn(true);
        lenient().when(mockAuthenticationData.getIdentityLabel()).thenReturn(VALID_TEST_COMPONENT);

        try (ComponentMetricIPCEventStreamAgent.PutComponentMetricOperationHandler putComponentMetricOperationHandler =
                     componentMetricIPCEventStreamAgent.getPutComponentMetricHandler(
                mockContext)) {
            PutComponentMetricResponse putComponentMetricResponse =
                    putComponentMetricOperationHandler.handleRequest(validComponentMetricRequest);
            assertNotNull(putComponentMetricResponse);

            verify(authorizationHandler, times(4)).isAuthorized(eq(PUT_COMPONENT_METRIC_SERVICE_NAME),
                    permissionArgumentCaptor.capture());
            Permission capturedPermission = permissionArgumentCaptor.getValue();
            assertThat(capturedPermission.getOperation(), is(GreengrassCoreIPCService.PUT_COMPONENT_METRIC));
            assertThat(capturedPermission.getPrincipal(), is(VALID_TEST_COMPONENT));
            assertThat(capturedPermission.getResource(), containsString("ExampleName"));
        }
    }

    @Test
    void GIVEN_put_component_metric_request_with_stream_manager_WHEN_handle_request_called_THEN_telemetry_metrics_published()
            throws AuthorizationException {
        lenient().when(mockAuthenticationData.getIdentityLabel()).thenReturn(STREAM_MANAGER_COMPONENT);

        try (ComponentMetricIPCEventStreamAgent.PutComponentMetricOperationHandler putComponentMetricOperationHandler =
                     componentMetricIPCEventStreamAgent.getPutComponentMetricHandler(
                mockContext)) {
            PutComponentMetricResponse putComponentMetricResponse =
                    putComponentMetricOperationHandler.handleRequest(validComponentMetricRequest);
            assertNotNull(putComponentMetricResponse);

            verify(authorizationHandler, never()).isAuthorized(eq(PUT_COMPONENT_METRIC_SERVICE_NAME), any(Permission.class));
        }
    }

    @Test
    void GIVEN_put_component_metric_request_with_invalid_service_name_WHEN_handle_request_called_THEN_throw_exception() {
        lenient().when(mockAuthenticationData.getIdentityLabel()).thenReturn(INVALID_TEST_COMPONENT);
        try (ComponentMetricIPCEventStreamAgent.PutComponentMetricOperationHandler putComponentMetricOperationHandler =
                     componentMetricIPCEventStreamAgent.getPutComponentMetricHandler(
                mockContext)) {
            assertThrows(UnauthorizedError.class, () -> {
                putComponentMetricOperationHandler.handleRequest(validComponentMetricRequest);
            });
        }
    }

    @Test
    void GIVEN_put_component_metric_request_WHEN_component_not_authorized_for_metric_name_THEN_handle_request_throws_unauthorized_exception()
            throws AuthorizationException {
        when(authorizationHandler.isAuthorized(any(), any())).thenReturn(false);
        lenient().when(mockAuthenticationData.getIdentityLabel()).thenReturn(VALID_TEST_COMPONENT);
        try (ComponentMetricIPCEventStreamAgent.PutComponentMetricOperationHandler putComponentMetricOperationHandler =
                     componentMetricIPCEventStreamAgent.getPutComponentMetricHandler(
                mockContext)) {
            assertThrows(UnauthorizedError.class, () -> {
                putComponentMetricOperationHandler.handleRequest(validComponentMetricRequest);
            });
        }
    }

    @Test
    void GIVEN_put_component_metric_request_with_invalid_metric_unit_WHEN_handle_request_called_THEN_throw_exception()
            throws Exception {
        when(authorizationHandler.isAuthorized(any(), any())).thenReturn(true);
        lenient().when(mockAuthenticationData.getIdentityLabel()).thenReturn(VALID_TEST_COMPONENT);
        PutComponentMetricRequest componentMetricRequest = generateComponentRequest("invalid-unit");
        try (ComponentMetricIPCEventStreamAgent.PutComponentMetricOperationHandler putComponentMetricOperationHandler =
                     componentMetricIPCEventStreamAgent.getPutComponentMetricHandler(
                mockContext)) {
            assertThrows(InvalidArgumentsError.class, () -> {
                putComponentMetricOperationHandler.handleRequest(componentMetricRequest);
            });
        }
    }

    @Test
    void GIVEN_put_component_metric_request_with_null_metric_unit_WHEN_handle_request_called_THEN_throw_exception() {
        lenient().when(mockAuthenticationData.getIdentityLabel()).thenReturn(VALID_TEST_COMPONENT);
        PutComponentMetricRequest componentMetricRequest = generateComponentRequest("");
        try (ComponentMetricIPCEventStreamAgent.PutComponentMetricOperationHandler putComponentMetricOperationHandler =
                     componentMetricIPCEventStreamAgent.getPutComponentMetricHandler(
                mockContext)) {
            assertThrows(InvalidArgumentsError.class, () -> {
                putComponentMetricOperationHandler.handleRequest(componentMetricRequest);
            });
        }
    }

    @Test
    void GIVEN_put_component_metric_request_with_no_metrics_WHEN_handle_request_called_THEN_throw_exception() {
        lenient().when(mockAuthenticationData.getIdentityLabel()).thenReturn(VALID_TEST_COMPONENT);
        PutComponentMetricRequest componentMetricRequest = new PutComponentMetricRequest();
        try (ComponentMetricIPCEventStreamAgent.PutComponentMetricOperationHandler putComponentMetricOperationHandler =
                     componentMetricIPCEventStreamAgent.getPutComponentMetricHandler(
                mockContext)) {
            assertThrows(InvalidArgumentsError.class, () -> {
                putComponentMetricOperationHandler.handleRequest(componentMetricRequest);
            });
        }
    }

    private PutComponentMetricRequest generateComponentRequest(String unitType) {
        PutComponentMetricRequest componentMetricRequest = new PutComponentMetricRequest();
        List<Metric> metrics = new ArrayList<>();
        IntStream.range(0, 4).forEach(i -> {
            Metric metric = new Metric();
            metric.setName("ExampleName" + i);
            metric.setUnit(unitType);
            metric.setValue((double) RANDOM.nextInt(50));

            metrics.add(metric);
        });
        componentMetricRequest.setMetrics(metrics);

        return componentMetricRequest;
    }
}
