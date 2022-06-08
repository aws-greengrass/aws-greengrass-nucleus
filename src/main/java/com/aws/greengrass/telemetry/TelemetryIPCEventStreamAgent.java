/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.telemetry;

import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.telemetry.impl.Metric;
import com.aws.greengrass.telemetry.impl.MetricFactory;
import com.aws.greengrass.telemetry.models.TelemetryAggregation;
import com.aws.greengrass.telemetry.models.TelemetryUnit;
import com.aws.greengrass.util.Utils;
import lombok.AccessLevel;
import lombok.Getter;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractEmitTelemetryMetricsOperationHandler;
import software.amazon.awssdk.aws.greengrass.model.EmitTelemetryMetricsRequest;
import software.amazon.awssdk.aws.greengrass.model.EmitTelemetryMetricsResponse;
import software.amazon.awssdk.aws.greengrass.model.InvalidArgumentsError;
import software.amazon.awssdk.aws.greengrass.model.TelemetryMetric;
import software.amazon.awssdk.aws.greengrass.model.UnauthorizedError;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.aws.greengrass.ipc.common.ExceptionUtil.translateExceptions;

public class TelemetryIPCEventStreamAgent {
    private static final String TELEMETRY_METRIC_PREFIX = "TelemetryMetric-";

    @Getter(AccessLevel.PACKAGE)
    private final Map<String, MetricFactory> mfMap = new HashMap<>();

    TelemetryIPCEventStreamAgent() {
    }

    public EmitTelemetryMetricsOperationHandler getEmitTelemetryMetricsHandler(
            OperationContinuationHandlerContext context) {
        return new EmitTelemetryMetricsOperationHandler(context);
    }

    private void handleEmitTelemetryMetricsRequest(EmitTelemetryMetricsRequest emitTelemetryMetricsRequest,
                                                   String namespace) {
        // TODO: [P32540011]: All IPC service requests need input validation
        List<TelemetryMetric> telemetryMetrics = emitTelemetryMetricsRequest.getMetrics();
        if (Utils.isEmpty(telemetryMetrics)) {
            throw new InvalidArgumentsError("Metric list must not be empty");
        }

        for (TelemetryMetric tlMetric: telemetryMetrics) {
            validateMetric(tlMetric);

            Metric metric = Metric.builder()
                    .namespace(namespace)
                    .name(tlMetric.getName())
                    .unit(TelemetryUnit.valueOf(tlMetric.getUnitAsString()))
                    // Telemetry cloud side currently only support Sum
                    .aggregation(TelemetryAggregation.Sum)
                    .value(tlMetric.getValue())
                    .timestamp(System.currentTimeMillis())
                    .build();

            emitMetricByNamespace(metric, namespace);
        }
    }

    class EmitTelemetryMetricsOperationHandler extends GeneratedAbstractEmitTelemetryMetricsOperationHandler {
        @Getter
        private final String serviceName;
        private final String namespace;

        protected EmitTelemetryMetricsOperationHandler(OperationContinuationHandlerContext context) {
            super(context);
            serviceName = context.getAuthenticationData().getIdentityLabel();
            namespace = TELEMETRY_METRIC_PREFIX + serviceName;
        }

        @Override
        protected void onStreamClosed() {
        }

        @SuppressWarnings("PMD.PreserveStackTrace")
        @Override
        public EmitTelemetryMetricsResponse handleRequest(EmitTelemetryMetricsRequest emitTelemetryMetricsRequest) {
            return translateExceptions(() -> {
                try {
                    doAuthorization(this.getOperationModelContext().getOperationName(), serviceName);
                } catch (AuthorizationException e) {
                    throw new UnauthorizedError(e.getMessage());
                }
                handleEmitTelemetryMetricsRequest(emitTelemetryMetricsRequest, namespace);
                return new EmitTelemetryMetricsResponse();
            });
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {
            // NA
        }
    }

    private MetricFactory getMetricFactoryByNamespace(String namespace) {
        return mfMap.computeIfAbsent(namespace, k -> new MetricFactory(namespace));
    }

    public void emitMetricByNamespace(Metric metric, String namespace) {
        MetricFactory mf = getMetricFactoryByNamespace(namespace);
        mf.putMetricData(metric);
    }

    private void validateMetric(TelemetryMetric metric) {
        // TODO: validate name only has character: (0-9A-Za-z), (-), (_)
        if (Utils.isEmpty(metric.getName()) || Utils.isEmpty(metric.getUnitAsString())) {
            throw new InvalidArgumentsError("Name and unit cannot be empty");
        }
    }


    private void doAuthorization(String opName, String serviceName) throws AuthorizationException {
        // TODO: validate serviceName start with aws.*
        // This is dummy code to get rid of checking error
        if ("dummy".equals(opName + serviceName)) {
            throw new AuthorizationException("Dummy code");
        }
    }
}
