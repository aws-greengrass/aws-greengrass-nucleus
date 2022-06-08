/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.telemetry;

import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.Permission;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.GGServiceTestUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.aws.greengrass.model.EmitTelemetryMetricsRequest;
import software.amazon.awssdk.aws.greengrass.model.EmitTelemetryMetricsResponse;
import software.amazon.awssdk.aws.greengrass.model.SubscriptionResponseMessage;
import software.amazon.awssdk.aws.greengrass.model.TelemetryMetric;
import software.amazon.awssdk.aws.greengrass.model.TelemetryMetricUnitType;
import software.amazon.awssdk.crt.eventstream.ServerConnectionContinuation;
import software.amazon.awssdk.eventstreamrpc.AuthenticationData;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class TelemetryIPCEventStreamAgentTest extends GGServiceTestUtil {
    private static final String TEST_SERVICE_1 = "TestService_1";
    private static final String TEST_SERVICE_2 = "TestService_2";

    @Mock
    AuthorizationHandler authorizationHandler;
    @Mock
    OperationContinuationHandlerContext mockContext;
    @Mock
    AuthenticationData mockAuthenticationData;
    @Captor
    ArgumentCaptor<SubscriptionResponseMessage> subscriptionResponseMessageCaptor;
    @TempDir
    protected Path tempRootDir;
    @Captor
    ArgumentCaptor<Permission> permissionArgumentCaptor;

    final ExecutorService pool = Executors.newCachedThreadPool();
    // private final OrderedExecutorService orderedExecutorService =
    //         new OrderedExecutorService(pool);
    private TelemetryIPCEventStreamAgent telemetryIPCEventStreamAgent;

    @BeforeEach
    public void setup() {
        lenient().when(mockContext.getContinuation()).thenReturn(mock(ServerConnectionContinuation.class));
        lenient().when(mockContext.getAuthenticationData()).thenReturn(mockAuthenticationData);

        telemetryIPCEventStreamAgent = new TelemetryIPCEventStreamAgent();
    }

    @AfterEach
    void afterEach() {
        pool.shutdownNow();
    }

    @Test
    void GIVEN_telemetryAgent_WHEN_2_different_services_emit_telemetry_metric_THEN_2_metric_factory_created() throws AuthorizationException {
        EmitTelemetryMetricsRequest emitTelemetryMetricsRequest = new EmitTelemetryMetricsRequest();


        List<TelemetryMetric> metrics = new ArrayList<>();
        metrics.add(new TelemetryMetric().withName("test-metric").withUnit(TelemetryMetricUnitType.None).withValue(1.0));
        emitTelemetryMetricsRequest.setMetrics(metrics);

        lenient().when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_SERVICE_1);
        try (TelemetryIPCEventStreamAgent.EmitTelemetryMetricsOperationHandler emitTelemetryMetricsHandler =
                     telemetryIPCEventStreamAgent.getEmitTelemetryMetricsHandler(mockContext)) {
            // Emit Telemetry Metric as TEST_SERVICE_1;
            EmitTelemetryMetricsResponse emitTelemetryMetricsResponse =
                    emitTelemetryMetricsHandler.handleRequest(emitTelemetryMetricsRequest);
            assertNotNull(emitTelemetryMetricsResponse);
            assertTrue(telemetryIPCEventStreamAgent.getMfMap().containsKey("TelemetryMetric-" + TEST_SERVICE_1));
        }


        metrics = new ArrayList<>();
        metrics.add(new TelemetryMetric().withName("test-metric-2").withUnit(TelemetryMetricUnitType.None).withValue(1.0));

        lenient().when(mockAuthenticationData.getIdentityLabel()).thenReturn(TEST_SERVICE_2);
        try (TelemetryIPCEventStreamAgent.EmitTelemetryMetricsOperationHandler emitTelemetryMetricsHandler =
                     telemetryIPCEventStreamAgent.getEmitTelemetryMetricsHandler(mockContext)) {
            // Emit Telemetry Metric as TEST_SERVICE_2;
            EmitTelemetryMetricsResponse emitTelemetryMetricsResponse =
                    emitTelemetryMetricsHandler.handleRequest(emitTelemetryMetricsRequest);
            assertNotNull(emitTelemetryMetricsResponse);
            assertTrue(telemetryIPCEventStreamAgent.getMfMap().containsKey("TelemetryMetric-" + TEST_SERVICE_2));
        }
    }
}
