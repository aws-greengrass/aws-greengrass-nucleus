/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.telemetry;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.telemetry.impl.Metric;
import com.aws.greengrass.telemetry.impl.MetricFactory;
import com.aws.greengrass.telemetry.impl.TelemetryLoggerMessage;
import com.aws.greengrass.telemetry.impl.config.TelemetryConfig;
import com.aws.greengrass.telemetry.models.TelemetryUnit;
import com.aws.greengrass.util.Coerce;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.inject.Inject;

public class MetricsAggregator {
    public static final Logger logger = LogManager.getLogger(MetricsAggregator.class);
    protected static final String AGGREGATE_METRICS_FILE = "AggregateMetrics";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MetricFactory metricFactory = new MetricFactory(AGGREGATE_METRICS_FILE);
    @Getter(AccessLevel.PACKAGE)
    private final NamespaceSet namespaceSet;

    @Inject
    public MetricsAggregator(NamespaceSet ns) {
        this.namespaceSet = ns;
    }

    /**
     * This method performs aggregation on the metrics emitted over the aggregation interval and writes them to a file.
     *
     * @param lastAgg       timestamp at which the last aggregation was done.
     * @param currTimestamp timestamp at which the current aggregation is initiated.
     */
    protected void aggregateMetrics(long lastAgg, long currTimestamp) {
        for (String namespace : getNamespaceSet().getNamespaces()) {
            AggregatedMetric aggMetrics = new AggregatedMetric();
            HashMap<String, List<Metric>> metrics = new HashMap<>();
            // Read from the Telemetry/namespace*.log file.
            // TODO : Read only those files that are modified after the last aggregation.
            // file.lastModified() behavior is platform dependent.
            try (Stream<Path> paths = Files
                    .walk(TelemetryConfig.getTelemetryDirectory())
                    .filter(Files::isRegularFile)
                    .filter((path) -> Coerce.toString(path.getFileName()).startsWith(namespace))
            ) {
                paths.forEach(path -> {
                    try (Stream<String> logs = Files.lines(path)) {
                        logs.forEach((log) -> {
                            try {
                                /* {"thread":"pool-3-thread-4","level":"TRACE","eventType":null,"message":"{\"NS\":

                                \"SystemMetrics\",\"N\":\"TotalNumberOfFDs\",\"U\":\"Count\",\"A\":\"Average\",\"V\"

                                :4583,\"TS\":1600127641506}","contexts":{},"loggerName":"Metrics-SystemMetrics",

                                "timestamp":1600127641506,"cause":null} */
                                GreengrassLogMessage egLog = objectMapper.readValue(log,
                                        GreengrassLogMessage.class);
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
            aggMetrics.setNamespace(namespace);
            aggMetrics.setTimestamp(currTimestamp);
            aggMetrics.setMetrics(doAggregation(metrics));
            metricFactory.logMetrics(new TelemetryLoggerMessage(aggMetrics));
        }
    }

    /**
     * This function takes in the map of metrics with metric name as key and returns a list of metrics with aggregation.
     * Example:
     * Input:
     * NumOfComponentsInstalled
     * |___GreengrassComponents,NumOfComponentsInstalled,Count,Average,10,1234567890
     * |___GreengrassComponents,NumOfComponentsInstalled,Count,Average,15,1234567891
     * NumOfComponentsBroken
     * |___GreengrassComponents,NumOfComponentsBroken,Count,Average,10,1234567890
     * |___GreengrassComponents,NumOfComponentsBroken,Count,Average,20,1234567891
     * Output:
     * |___N -  NumOfComponentsInstalled,Average - 12.5,U - Count
     * |___N -  NumOfComponentsBroken,Average - 15,U - Count
     *
     * @param map metric name -> metric
     * @return a list of {@link AggregatedMetric.Metric}
     */
    private List<AggregatedMetric.Metric> doAggregation(Map<String, List<Metric>> map) {
        List<AggregatedMetric.Metric> aggMetrics = new ArrayList<>();
        for (Map.Entry<String, List<Metric>> metric : map.entrySet()) {
            String metricName = metric.getKey();
            List<Metric> metrics = metric.getValue();
            String aggregationType = Coerce.toString(metrics.get(0).getAggregation());
            List<Double> values = new ArrayList<>();
            for (Metric m : metrics) {
                values.add(Coerce.toDouble(m.getValue()));
            }
            double aggregation = values.isEmpty() ? 0 : getAggregatedValue(values, aggregationType);
            Map<String, Object> value = new HashMap<>();
            value.put(aggregationType, aggregation);
            AggregatedMetric.Metric m = AggregatedMetric.Metric.builder()
                    .name(metricName)
                    .unit(metrics.get(0).getUnit())
                    .value(value)
                    .build();
            aggMetrics.add(m);
        }
        return aggMetrics;
    }

    /**
     * This function returns the set of all the aggregated metric data points that are to be published to the cloud
     * since the last upload. This also includes one extra aggregated point for each namespace which is the aggregation
     * of aggregated points in that publish interval.
     *
     * @param lastPublish   timestamp at which the last publish was done.
     * @param currTimestamp timestamp at which the current publish is initiated.
     */
    protected Map<Long, List<AggregatedMetric>> getMetricsToPublish(long lastPublish, long currTimestamp) {
        Map<Long, List<AggregatedMetric>> aggUploadMetrics = new HashMap<>();
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
                            GreengrassLogMessage egLog = objectMapper.readValue(log,
                                    GreengrassLogMessage.class);
                            AggregatedMetric am = objectMapper.readValue(egLog.getMessage(),
                                    AggregatedMetric.class);
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
        aggUploadMetrics.putIfAbsent(currTimestamp, new ArrayList<>());
        //Along with the aggregated data points, we need to collect an additional data point for each metric which is
        // like the aggregation of aggregated data points.
        aggUploadMetrics.compute(currTimestamp, (k, v) -> {
            v.addAll(getAggForThePublishInterval(aggUploadMetrics.get(currTimestamp), currTimestamp));
            return v;
        });
        return aggUploadMetrics;
    }

    /**
     * This function takes a list of aggregated metrics and returns their aggregation in a list(Aggregation of
     * aggregated metrics). This is published to the cloud along with the aggregated metric points
     * Example:
     * Input:
     * TS:123456
     * NS:GreengrassComponents
     * |___N -  NumOfComponentsInstalled,Average - 20,U - Count
     * |___N -  NumOfComponentsBroken,Average - 5,U - Count
     * TS:123457
     * NS:GreengrassComponents
     * |___N -  NumOfComponentsInstalled,Average - 10,U - Count
     * |___N -  NumOfComponentsBroken,Average - 15,U - Count
     * Output:
     * TS:123457
     * NS:GreengrassComponents
     * |___N -  NumOfComponentsInstalled,Average - 15,U - Count
     * |___N -  NumOfComponentsBroken,Average - 10,U - Count
     *
     * @param aggList list of {@link AggregatedMetric}
     * @return a list of {@link AggregatedMetric}
     */
    private List<AggregatedMetric> getAggForThePublishInterval(List<AggregatedMetric> aggList, long currTimestamp) {
        List<AggregatedMetric> list = new ArrayList<>();
        for (String namespace : getNamespaceSet().getNamespaces()) {
            HashMap<String, List<AggregatedMetric.Metric>> metrics = new HashMap<>();
            AggregatedMetric newAgg = new AggregatedMetric();
            for (AggregatedMetric am : aggList) {
                if (am.getNamespace().equals(namespace)) {
                    for (AggregatedMetric.Metric m : am.getMetrics()) {
                        metrics.computeIfAbsent(m.getName(), k -> new ArrayList<>()).add(m);
                    }
                }
            }
            newAgg.setNamespace("Acc-" + namespace);
            newAgg.setTimestamp(currTimestamp);
            newAgg.setMetrics(doAggregationForPublish(metrics));
            list.add(newAgg);
        }
        return list;
    }

    /**
     * This function takes in the map of aggregated metrics with metric name as key and returns a list of metrics with
     * aggregation.
     * Input:
     * NumOfComponentsInstalled
     * |___N - NumOfComponentsInstalled,Average - 10,U - Count
     * |___N - NumOfComponentsInstalled,Average - 15,U - Count
     * NumOfComponentsBroken
     * |___N - NumOfComponentsBroken,Average - 10,U - Count
     * |___N - NumOfComponentsBroken,Average - 20,U - Count
     * Output:
     * |___N - NumOfComponentsInstalled,Average - 12.5,U - Count
     * |___N - NumOfComponentsBroken,Average - 15,U - Count
     *
     * @param map metric name -> aggregated metric
     * @return list of {@link AggregatedMetric.Metric }
     */
    private List<AggregatedMetric.Metric> doAggregationForPublish(Map<String, List<AggregatedMetric.Metric>> map) {
        List<AggregatedMetric.Metric> aggMetrics = new ArrayList<>();
        for (Map.Entry<String, List<AggregatedMetric.Metric>> metric : map.entrySet()) {
            List<AggregatedMetric.Metric> metrics = metric.getValue();
            List<Double> values = new ArrayList<>();
            metrics.get(0).getValue().forEach((aggType, aggValue) -> {
                metrics.forEach((v) -> values.add(Coerce.toDouble(v.getValue().get(aggType))));
                Map<String, Object> value = new HashMap<>();
                value.put(aggType, values.isEmpty() ? 0 : getAggregatedValue(values, aggType));
                AggregatedMetric.Metric m = AggregatedMetric.Metric.builder()
                        .name(metric.getKey())
                        .unit(metrics.get(0).getUnit())
                        .value(value)
                        .build();
                aggMetrics.add(m);
            });
        }
        return aggMetrics;
    }

    /**
     * This method performs aggregation on the list of values of the metrics.
     *
     * @param values          list of the values extracted from the metrics
     * @param aggregationType string value of {@link com.aws.greengrass.telemetry.models.TelemetryAggregation}
     * @return returns an aggregated value for the entire list.
     */
    private double getAggregatedValue(List<Double> values, String aggregationType) {
        double aggregation = 0;
        switch (aggregationType) {
            case "Average":
                aggregation = values.stream().mapToDouble(Coerce::toDouble).sum();
                if (!values.isEmpty()) {
                    aggregation = aggregation / values.size();
                }
                break;
            case "Sum":
                aggregation = values.stream().mapToDouble(Coerce::toDouble).sum();
                break;
            case "Maximum":
                aggregation = values.stream().mapToDouble(Coerce::toDouble).max().getAsDouble();
                break;
            case "Minimum":
                aggregation = values.stream().mapToDouble(Coerce::toDouble).min().getAsDouble();
                break;
            default:
                logger.atError().log("Unknown aggregation type: {}", aggregationType);
                break;
        }
        return aggregation;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AggregatedMetric {
        @JsonProperty("TS")
        private Long timestamp;
        @JsonProperty("NS")
        private String namespace;
        @JsonProperty("M")
        private List<Metric> metrics;

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        public static class Metric {
            @JsonProperty("N")
            private String name;
            private Map<String, Object> value = new HashMap<>();
            @JsonProperty("U")
            private TelemetryUnit unit;

            @JsonAnyGetter
            public Map<String, Object> getValue() {
                return value;
            }

            public void setValue(Map<String, Object> value) {
                this.value = value;
            }

            @JsonAnySetter
            public void jsonAggregationValue(final String name, final Object value) {
                this.value.put(name, value);
            }
        }
    }
}
