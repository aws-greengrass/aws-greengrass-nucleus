/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.telemetry;

import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.Permission;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.telemetry.impl.Metric;
import com.aws.greengrass.telemetry.impl.MetricFactory;
import com.aws.greengrass.telemetry.models.TelemetryAggregation;
import com.aws.greengrass.telemetry.models.TelemetryUnit;
import com.aws.greengrass.util.Utils;
import lombok.AccessLevel;
import lombok.Getter;
import org.apache.commons.lang3.EnumUtils;
import software.amazon.awssdk.aws.greengrass.GeneratedAbstractPutComponentMetricOperationHandler;
import software.amazon.awssdk.aws.greengrass.model.InvalidArgumentsError;
import software.amazon.awssdk.aws.greengrass.model.PutComponentMetricRequest;
import software.amazon.awssdk.aws.greengrass.model.PutComponentMetricResponse;
import software.amazon.awssdk.aws.greengrass.model.ServiceError;
import software.amazon.awssdk.aws.greengrass.model.UnauthorizedError;
import software.amazon.awssdk.eventstreamrpc.OperationContinuationHandlerContext;
import software.amazon.awssdk.eventstreamrpc.model.EventStreamJsonMessage;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.inject.Inject;

import static com.aws.greengrass.ipc.common.ExceptionUtil.translateExceptions;
import static com.aws.greengrass.ipc.modules.ComponentMetricIPCService.PUT_COMPONENT_METRIC_SERVICE_NAME;

public class ComponentMetricIPCEventStreamAgent {
    private static final Logger logger = LogManager.getLogger(ComponentMetricIPCEventStreamAgent.class);
    private static final Pattern SERVICE_NAME_REGEX = Pattern.compile("^aws\\.");
    private static final String NON_ALPHANUMERIC_REGEX = "[^A-Za-z0-9]";
    private static final String SERVICE_NAME = "ServiceName";

    @Getter(AccessLevel.PACKAGE)
    private final Map<String, MetricFactory> metricFactoryMap = new HashMap<>();
    private final AuthorizationHandler authorizationHandler;

    @Inject
    ComponentMetricIPCEventStreamAgent(AuthorizationHandler authorizationHandler) {
        this.authorizationHandler = authorizationHandler;
    }

    public PutComponentMetricOperationHandler getPutComponentMetricHandler(
            OperationContinuationHandlerContext context) {
        return new PutComponentMetricOperationHandler(context);
    }

    class PutComponentMetricOperationHandler extends GeneratedAbstractPutComponentMetricOperationHandler {
        private final String serviceName;

        protected PutComponentMetricOperationHandler(OperationContinuationHandlerContext context) {
            super(context);
            serviceName = context.getAuthenticationData().getIdentityLabel();
        }

        @Override
        protected void onStreamClosed() {
            // NA
        }

        @SuppressWarnings({"PMD.PreserveStackTrace", "PMD.AvoidCatchingGenericException"})
        @Override
        public PutComponentMetricResponse handleRequest(PutComponentMetricRequest componentMetricRequest) {
            return translateExceptions(() -> {
                logger.atDebug().kv(SERVICE_NAME, serviceName)
                        .log("Received putComponentMetricRequest from component " + serviceName);

                // Authorize service name for given operation
                String opName = this.getOperationModelContext().getOperationName();
                try {
                    doServiceAuthorization(opName, serviceName);
                } catch (AuthorizationException e) {
                    logger.atError().kv(SERVICE_NAME, serviceName)
                            .log("{} is not authorized to perform operation", serviceName);
                    throw new UnauthorizedError(e.getMessage());
                }

                //validate - metric name length, value is non negative etc etc
                List<software.amazon.awssdk.aws.greengrass.model.Metric> metricList =
                        componentMetricRequest.getMetrics();
                try {
                    validateComponentMetricRequest(opName, serviceName, metricList);
                } catch (IllegalArgumentException e) {
                    logger.atError().kv(SERVICE_NAME, serviceName)
                            .log("invalid component metric request from %s", serviceName);
                    throw new InvalidArgumentsError(e.getMessage());
                } catch (AuthorizationException e) {
                    logger.atError().kv(SERVICE_NAME, serviceName)
                            .log("{} is not authorized to perform operation", serviceName);
                    throw new UnauthorizedError(e.getMessage());
                }

                // Perform translations on metrics List
                try {
                    final String metricNamespace = serviceName;
                    translateAndEmit(metricList, metricNamespace);
                } catch (IllegalArgumentException e) {
                    logger.atError().kv(SERVICE_NAME, serviceName)
                            .log("invalid component metric request from %s", serviceName);
                    throw new InvalidArgumentsError(e.getMessage());
                } catch (Exception ex) {
                    logger.atError().kv(SERVICE_NAME, serviceName)
                            .log("error while emitting metrics from %s", serviceName);
                    throw new ServiceError(ex.getMessage());
                }

                return new PutComponentMetricResponse();
            });
        }

        @Override
        public void handleStreamEvent(EventStreamJsonMessage streamRequestEvent) {
            // NA
        }

        // Translate request metrics to telemetry metrics and emit them
        private void translateAndEmit(List<software.amazon.awssdk.aws.greengrass.model.Metric> componentMetrics,
                                      String metricNamespace) {
            final MetricFactory metricFactory =
                    metricFactoryMap.computeIfAbsent(metricNamespace, k -> new MetricFactory(metricNamespace));

            componentMetrics.forEach(metric -> {
                logger.atDebug().kv(SERVICE_NAME, serviceName)
                        .log("Translating component metric to Telemetry metric" + metric.getName());
                Metric telemetryMetric = getTelemetryMetric(metric, metricNamespace);
                logger.atDebug().kv(SERVICE_NAME, serviceName)
                        .log("Publish Telemetry metric" + telemetryMetric.getName());
                metricFactory.putMetricData(telemetryMetric);
            });
        }
    }


    // Creates telemetry metric object for given request metric
    private Metric getTelemetryMetric(software.amazon.awssdk.aws.greengrass.model.Metric metric,
                                      String metricNamespace) {
        return Metric.builder()
                .namespace(metricNamespace)
                .name(metric.getName())
                .unit(valueOfIgnoreCase(metric.getUnitAsString()))
                .aggregation(TelemetryAggregation.Sum)
                .value(metric.getValue())
                .timestamp(Instant.now().toEpochMilli())
                .build();
    }

    // Translate unit from metric request to telemetry unit
    private TelemetryUnit valueOfIgnoreCase(String unitAsString) {
        if (unitAsString == null || unitAsString.isEmpty()) {
            throw new IllegalArgumentException("Invalid telemetry unit: Found null or empty value");
        }

        final String replacedString = String.join("", unitAsString.split(NON_ALPHANUMERIC_REGEX));
        TelemetryUnit telemetryEnum = EnumUtils.getEnumIgnoreCase(TelemetryUnit.class, replacedString);

        if (telemetryEnum == null || telemetryEnum.toString().isEmpty()) {
            throw new IllegalArgumentException("Invalid telemetry unit: No matching TelemetryUnit type found");
        }
        return telemetryEnum;
    }

    // Validate metric request - check name, unit and value arguments
    // Also authorize request against access control policy
    // throw IllegalArgumentException if request params are invalid
    // throw AuthorizationException if request params don't match access control policy
    private void validateComponentMetricRequest(String opName, String serviceName,
                                                List<software.amazon.awssdk.aws.greengrass.model.Metric> metrics)
            throws AuthorizationException {
        if (Utils.isEmpty(metrics)) {
            throw new IllegalArgumentException(
                    String.format("Null or Empty list of metrics found in PutComponentMetricRequest"));
        }
        for (software.amazon.awssdk.aws.greengrass.model.Metric metric : metrics) {
            if (Utils.isEmpty(metric.getName()) || metric.getName().getBytes(StandardCharsets.UTF_8).length > 32
                    || Utils.isEmpty(metric.getUnitAsString()) || metric.getValue() < 0) {
                throw new IllegalArgumentException(
                        String.format("Invalid argument found in PutComponentMetricRequest"));
            }
            doMetricAuthorization(opName, serviceName, metric.getName());
        }
    }

    // Validate if request is authorized for given operation
    // throws AuthorizationException if not authorized
    private void doMetricAuthorization(String opName, String serviceName, String metricName)
            throws AuthorizationException {
        if (authorizationHandler.isAuthorized(PUT_COMPONENT_METRIC_SERVICE_NAME,
                Permission.builder().operation(opName).principal(serviceName).resource(metricName).build())) {
            return;
        }
        throw new AuthorizationException(
                String.format("Principal %s is not authorized to perform %s:%s with metric name %s", serviceName,
                        PUT_COMPONENT_METRIC_SERVICE_NAME, opName, metricName));
    }

    // Validate if serviceName is of format "aws.*"
    // throws AuthorizationException if not authorized
    private void doServiceAuthorization(String opName, String serviceName) throws AuthorizationException {
        if (SERVICE_NAME_REGEX.matcher(serviceName).find()) {
            return;
        }
        throw new AuthorizationException(String.format("Principal %s is not authorized to perform %s:%s ", serviceName,
                PUT_COMPONENT_METRIC_SERVICE_NAME, opName));
    }
}
