/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.telemetry;

import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.EvergreenStructuredLogMessage;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.telemetry.impl.Metric;
import com.aws.iot.evergreen.telemetry.impl.MetricFactory;
import com.aws.iot.evergreen.telemetry.impl.TelemetryLoggerMessage;
import com.aws.iot.evergreen.telemetry.impl.config.TelemetryConfig;
import com.aws.iot.evergreen.telemetry.models.TelemetryAggregation;
import com.aws.iot.evergreen.telemetry.models.TelemetryMetricName;
import com.aws.iot.evergreen.telemetry.models.TelemetryNamespace;
import com.aws.iot.evergreen.telemetry.models.TelemetryUnit;
import com.aws.iot.evergreen.util.Coerce;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

public class MetricsAggregator {
    public static final Logger logger = LogManager.getLogger(MetricsAggregator.class);
    protected static final String AGGREGATE_METRICS_FILE = "AggregateMetrics";
    private final ObjectMapper objectMapper = new ObjectMapper();
    MetricFactory metricFactory = new MetricFactory(AGGREGATE_METRICS_FILE);

    /**
     * This method stores the aggregated data points of the metrics emitted over the interval.
     *
     * @param lastAgg       timestamp at which the last aggregation was done.
     * @param currTimestamp timestamp at which the current aggregation is initiated.
     */
    protected void aggregateMetrics(long lastAgg, long currTimestamp) {
        for (TelemetryNamespace namespace : TelemetryNamespace.values()) {
            AggregatedMetric aggMetrics = new AggregatedMetric();
            HashMap<TelemetryMetricName, List<Metric>> metrics = new HashMap<>();
            // Read from the Telemetry/namespace*.log file.
            // TODO : Read only those files that are modified after the last aggregation.
            // file.lastModified() behavior is platform dependent.
            try (Stream<Path> paths = Files
                    .walk(TelemetryConfig.getTelemetryDirectory())
                    .filter(Files::isRegularFile)
                    .filter((path) -> Coerce.toString(path.getFileName()).startsWith(namespace.toString()))
            ) {
                paths.forEach(path -> {
                    try (Stream<String> logs = Files.lines(path)) {
                        logs.forEach((log) -> {
                            try {
                                /* {"thread":"pool-3-thread-4","level":"TRACE","eventType":null,"message":"{\"NS\":

                                \"SystemMetrics\",\"N\":\"TotalNumberOfFDs\",\"U\":\"Count\",\"A\":\"Average\",\"V\"

                                :4583,\"TS\":1600127641506}","contexts":{},"loggerName":"Metrics-SystemMetrics",

                                "timestamp":1600127641506,"cause":null} */
                                EvergreenStructuredLogMessage egLog = objectMapper.readValue(log,
                                        EvergreenStructuredLogMessage.class);
                                Metric mdp = objectMapper.readValue(egLog.getMessage(), Metric.class);
                                // Avoid the metrics that are emitted at/after the currTimestamp and before the
                                // aggregation interval
                                if (mdp != null && currTimestamp > mdp.getTimestamp() && mdp.getTimestamp()
                                        >= lastAgg) {
                                    metrics.computeIfAbsent(mdp.getName(), k -> new ArrayList<>()).add(mdp);
                                }
                            } catch (IOException e) {
                                logger.atError().cause(e).log("Unable to parse the metric log.");
                            }
                        });
                    } catch (IOException e) {
                        logger.atError().cause(e).log("Unable to parse the emitted metric log file.");
                    }
                });
            } catch (IOException e) {
                logger.atError().cause(e).log("Unable to read metric files from the directory");
            }
            aggMetrics.setMetricNamespace(namespace);
            aggMetrics.setTimestamp(currTimestamp);
            aggMetrics.setMetrics(doAggregation(metrics));
            metricFactory.logMetrics(new TelemetryLoggerMessage(aggMetrics));
        }
    }


    private List<AggregatedMetric.Metric> doAggregation(Map<TelemetryMetricName, List<Metric>> metrics) {
        List<AggregatedMetric.Metric> aggMetrics = new ArrayList<>();
        for (Map.Entry<TelemetryMetricName, List<Metric>> metric : metrics.entrySet()) {
            TelemetryMetricName metricName = metric.getKey();
            List<Metric> mdp = metric.getValue();
            TelemetryAggregation telemetryAggregation = mdp.get(0).getAggregation();
            double aggregation = 0;
            switch (telemetryAggregation) {
                case Average:
                    aggregation = mdp
                            .stream()
                            .filter(Objects::nonNull)
                            .mapToDouble(a -> Coerce.toDouble(a.getValue()))
                            .sum();
                    if (!mdp.isEmpty()) {
                        aggregation = aggregation / mdp.size();
                    }
                    break;
                case Sum:
                    aggregation = mdp
                            .stream()
                            .filter(Objects::nonNull)
                            .mapToDouble(a -> Coerce.toDouble(a.getValue()))
                            .sum();
                    break;
                case Maximum:
                    aggregation = mdp
                            .stream()
                            .filter(Objects::nonNull)
                            .mapToDouble(a -> Coerce.toDouble(a.getValue()))
                            .max()
                            .getAsDouble();
                    break;
                case Minimum:
                    aggregation = mdp
                            .stream()
                            .filter(Objects::nonNull)
                            .mapToDouble(a -> Coerce.toDouble(a.getValue()))
                            .min()
                            .getAsDouble();
                    break;
                default:
                    logger.atError().log("Unknown aggregation type: {}", telemetryAggregation);
                    break;
            }
            AggregatedMetric.Metric m = AggregatedMetric.Metric.builder()
                    .metricName(metricName)
                    .metricUnit(mdp.get(0).getUnit())
                    .value(aggregation)
                    .build();
            aggMetrics.add(m);
        }
        return aggMetrics;
    }

    /**
     * This function returns the set of all the aggregated metric data points that are to be published to the cloud
     * since the last upload.
     *
     * @param lastPublish   timestamp at which the last publish was done.
     * @param currTimestamp timestamp at which the current publish is initiated.
     */
    protected Map<Long, List<MetricsAggregator.AggregatedMetric>> getMetricsToPublish(long lastPublish,
                                                                                      long currTimestamp) {
        Map<Long, List<MetricsAggregator.AggregatedMetric>> aggUploadMetrics = new HashMap<>();
        // Read from the Telemetry/AggregatedMetrics.log file.
        // TODO : Read only those files that are modified after the last publish.
        try (Stream<Path> paths = Files
                .walk(TelemetryConfig.getTelemetryDirectory())
                .filter(Files::isRegularFile)
                .filter((path) -> Coerce.toString(path.getFileName()).startsWith(AGGREGATE_METRICS_FILE))) {
            paths.forEach(path -> {
                try (Stream<String> logs = Files.lines(path)) {
                    logs.forEach(log -> {
                        try {
                            /* {"thread":"main","level":"TRACE","eventType":null,

                            "message":"{\"TS\":1599617227533,\"NS\":\"SystemMetrics\",\"M\":[{\"N\":\"CpuUsage\",

                            \"V\":60.0,\"U\":\"Percent\"},{\"N\":\"TotalNumberOfFDs\",\"V\":6000.0,\"U\":\"Count\"},

                            {\"N\":\"SystemMemUsage\",\"V\":3000.0,\"U\":\"Megabytes\"}]}","contexts":{},"loggerName":

                            "Metrics-AggregateMetrics","timestamp":1599617227595,"cause":null} */
                            EvergreenStructuredLogMessage egLog = objectMapper.readValue(log,
                                    EvergreenStructuredLogMessage.class);
                            MetricsAggregator.AggregatedMetric am = objectMapper.readValue(egLog.getMessage(),
                                    MetricsAggregator.AggregatedMetric.class);
                            // Avoid the metrics that are aggregated at/after the currTimestamp and before the
                            // upload interval
                            if (am != null && currTimestamp > am.getTimestamp() && am.getTimestamp() >= lastPublish) {
                                aggUploadMetrics.computeIfAbsent(currTimestamp, k -> new ArrayList<>()).add(am);
                            }
                        } catch (JsonProcessingException e) {
                            logger.atError().cause(e).log("Unable to parse the aggregated metric log.");
                        }
                    });
                } catch (IOException e) {
                    logger.atError().cause(e).log("Unable to parse the aggregated metric log file.");
                }
            });
        } catch (IOException e) {
            logger.atError().cause(e).log("Unable to read the aggregated metric files from the directory");
        }
        aggUploadMetrics.putIfAbsent(currTimestamp, Collections.EMPTY_LIST);
        return aggUploadMetrics;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AggregatedMetric {
        @JsonProperty("TS")
        private Long timestamp;
        @JsonProperty("NS")
        private TelemetryNamespace metricNamespace;
        @JsonProperty("M")
        private List<Metric> metrics;

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        public static class Metric {
            @JsonProperty("N")
            private TelemetryMetricName metricName;
            @JsonProperty("V")
            private Object value;
            @JsonProperty("U")
            private TelemetryUnit metricUnit;
        }
    }
}
