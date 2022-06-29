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
import com.aws.greengrass.util.Coerce;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public class MetricsAggregator {
    public static final Logger logger = LogManager.getLogger(MetricsAggregator.class);
    protected static final String AGGREGATE_METRICS_FILE = "AggregateMetrics";
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final MetricFactory metricFactory = new MetricFactory(AGGREGATE_METRICS_FILE);

    /**
     * Read namespaces from files.
     * Telemetry log files format : fileName + "_%d{yyyy_MM_dd_HH}_%i" + "." + prefix
     *
     * @return namespace set
     */
    public static Set<String> getNamespaceSet() {
        Set<String> namespaces = new HashSet<>();
        try (Stream<Path> paths = Files
                .walk(TelemetryConfig.getTelemetryDirectory())
                .filter(Files::isRegularFile)) {
            paths.forEach((p) -> {
                String fileName = Coerce.toString(p.getFileName()).split(".log")[0];
                if (fileName.contains("_")) {
                    fileName = fileName.split("_")[0];
                }
                if (!fileName.equalsIgnoreCase(AGGREGATE_METRICS_FILE)) {
                    namespaces.add(fileName);
                }
            });
        } catch (IOException e) {
            logger.atError().cause(e).log("Unable to read files from the telemetry directory");
        }
        return namespaces;
    }

    /**
     * This method performs aggregation on the metrics emitted over the aggregation interval and writes them to a file.
     *
     * @param lastAgg       timestamp at which the last aggregation was done.
     * @param currTimestamp timestamp at which the current aggregation is initiated.
     */
    protected void aggregateMetrics(long lastAgg, long currTimestamp) {
        for (String namespace : getNamespaceSet()) {
            AggregatedNamespaceData aggMetrics = new AggregatedNamespaceData();
            HashMap<String, List<Metric>> metrics = new HashMap<>();
            // Read from the Telemetry/namespace*.log file.
            // TODO: [P41214521] Read only those files that are modified after the last aggregation.
            // file.lastModified() behavior is platform dependent.
            // filter only files with given namespace that end in ".log"
            try (Stream<Path> paths = Files
                    .walk(TelemetryConfig.getTelemetryDirectory())
                    .filter(Files::isRegularFile)
                    .filter((path) -> Coerce.toString(path.getFileName()).startsWith(namespace)
                            && Coerce.toString(path.getFileName()).endsWith(".log"))
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

            // No aggregation if the metrics are empty
            if (!metrics.isEmpty()) {
                aggMetrics.setNamespace(namespace);
                aggMetrics.setTimestamp(currTimestamp);
                aggMetrics.setMetrics(doAggregation(metrics));
                metricFactory.logMetrics(new TelemetryLoggerMessage(aggMetrics));
            }
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
     * @return a list of {@link AggregatedMetric}
     */
    private List<AggregatedMetric> doAggregation(Map<String, List<Metric>> map) {
        List<AggregatedMetric> aggMetrics = new ArrayList<>();
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
            AggregatedMetric m = AggregatedMetric.builder()
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
    protected Map<Long, List<AggregatedNamespaceData>> getMetricsToPublish(long lastPublish, long currTimestamp) {
        Map<Long, List<AggregatedNamespaceData>> aggUploadMetrics = new HashMap<>();
        // Read from the Telemetry/AggregatedMetrics.log file.
        // TODO: [P41214521] Read only those files that are modified after the last publish.
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
                            AggregatedNamespaceData am = objectMapper.readValue(egLog.getMessage(),
                                    AggregatedNamespaceData.class);
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
        // Along with the aggregated data points, we need to collect an additional data point for each metric which is
        // like the aggregation of aggregated data points.
        // TODO: [P41214598] Get accumulated data points during aggregation and cache it to the disk.
        aggUploadMetrics.computeIfPresent(currTimestamp, (k, v) -> {
            v.addAll(getAggForThePublishInterval(aggUploadMetrics.get(currTimestamp), currTimestamp));
            return v;
        });

        // TODO: [P41214636] Verify the aggregation type of v2 metrics. As of now, all the v1
        //  metrics have "Sum" aggregation type and so is the cloud validation.
        // The following code changes any aggregation type of the metrics to "Sum" only in the final result to keep it
        // compatible with v1 and UATs for now. However, metrics are still defined and aggregated with on their own
        // aggregation type.
        aggUploadMetrics.forEach((k, v) -> {
            v.forEach(nsd -> {
                nsd.getMetrics().forEach(m -> {
                    Map<String, Object> value = new HashMap<>();
                    m.getValue().values().forEach((val) -> {
                        value.put("Sum", val);
                        m.setValue(value);
                    });
                });
            });
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
     * @param aggList list of {@link AggregatedNamespaceData}
     * @return a list of {@link AggregatedNamespaceData}
     */
    private List<AggregatedNamespaceData> getAggForThePublishInterval(List<AggregatedNamespaceData> aggList,
                                                                      long currTimestamp) {
        List<AggregatedNamespaceData> list = new ArrayList<>();
        for (String namespace : getNamespaceSet()) {
            HashMap<String, List<AggregatedMetric>> metrics = new HashMap<>();
            AggregatedNamespaceData newAgg = new AggregatedNamespaceData();
            for (AggregatedNamespaceData am : aggList) {
                if (am.getNamespace().equals(namespace)) {
                    for (AggregatedMetric m : am.getMetrics()) {
                        metrics.computeIfAbsent(m.getName(), k -> new ArrayList<>()).add(m);
                    }
                }
            }
            // No accumulation for system metrics.
            // No aggregation if the metrics are empty.
            if (!metrics.isEmpty() && !namespace.equals(SystemMetricsEmitter.NAMESPACE)) {
                newAgg.setNamespace(namespace);
                newAgg.setTimestamp(currTimestamp);
                newAgg.setMetrics(doAggregationForPublish(metrics));
                list.add(newAgg);
            }
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
     * @return list of {@link AggregatedMetric }
     */
    private List<AggregatedMetric> doAggregationForPublish(Map<String, List<AggregatedMetric>> map) {
        List<AggregatedMetric> aggMetrics = new ArrayList<>();
        for (Map.Entry<String, List<AggregatedMetric>> metric : map.entrySet()) {
            List<AggregatedMetric> metrics = metric.getValue();
            List<Double> values = new ArrayList<>();
            metrics.get(0).getValue().forEach((aggType, aggValue) -> {
                metrics.forEach((v) -> values.add(Coerce.toDouble(v.getValue().get(aggType))));
                Map<String, Object> value = new HashMap<>();
                value.put(aggType, values.isEmpty() ? 0 : getAggregatedValue(values, aggType));
                AggregatedMetric m = AggregatedMetric.builder()
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
}

