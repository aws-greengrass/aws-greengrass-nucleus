package com.aws.iot.evergreen.telemetry;

import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.telemetry.impl.MetricDataPoint;
import com.aws.iot.evergreen.telemetry.impl.MetricFactory;
import com.aws.iot.evergreen.telemetry.impl.TelemetryLoggerMessage;
import com.aws.iot.evergreen.telemetry.models.TelemetryAggregation;
import com.aws.iot.evergreen.telemetry.models.TelemetryMetricName;
import com.aws.iot.evergreen.telemetry.models.TelemetryNamespace;
import com.aws.iot.evergreen.telemetry.models.TelemetryUnit;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MetricsAggregator {
    protected static final String AGGREGATE_METRICS_FILE = "AggregateMetrics";
    private static final int MILLI_SECONDS = 1000;
    MetricFactory metricFactory = new MetricFactory(AGGREGATE_METRICS_FILE);
    public static final Logger logger = LogManager.getLogger(MetricsAggregator.class);

    /**
     * This method stores the aggregated data points of the metrics emitted over the interval.
     *
     * @param aggregationIntervalSec periodic interval in seconds for the aggregating the metrics
     * @param currentTimestamp timestamp at which the aggregate is initiated
     */
    public void aggregateMetrics(int aggregationIntervalSec, long currentTimestamp) {
        int aggregationIntervalMilliSec = aggregationIntervalSec * MILLI_SECONDS;
        for (TelemetryNamespace namespace : TelemetryNamespace.values()) {
            AggregatedMetric aggMetrics = new AggregatedMetric();
            HashMap<TelemetryMetricName, List<MetricDataPoint>> metrics = new HashMap<>();
            try {
                Stream<Path> paths = Files
                        .walk(MetricFactory.getTelemetryDirectory())
                        .filter(Files::isRegularFile)
                        .filter(path -> {
                            Object fileName = null;
                            if (path != null) {
                                fileName = path.getFileName();
                            }
                            if (fileName == null) {
                                fileName = "";
                            }
                            return fileName.toString().startsWith(namespace.toString());
                        });
                paths.forEach((path) -> {
                    /*
                     Read from the file at Telemetry/namespace*.log
                     Read only modified files and aggregate only new values based on the last aggregated time.
                     */
                    if (currentTimestamp - new File(path.toString()).lastModified() <= aggregationIntervalMilliSec) {
                        try {
                            for (String log :
                                    Files.lines(Paths.get(path.toString())).collect(Collectors.toList())) {
                                /*
                                [0]  [1] [2]        [3]          [4]         [5]             [6]
                                2020 Aug 28 12:08:21,520-0700 [TRACE] (pool-3-thread-4) Metrics-KernelComponents:

                                [7]
                                {"M":{"NS": "KernelComponents","N":"NumberOfComponentsStopping","U":"Count"},
                                "V":0,"TS":1598598501520}. {}
                                 */
                                MetricDataPoint mdp = new ObjectMapper()
                                        .readValue(log.split(" ")[7], MetricDataPoint.class);
                                if (mdp.getMetric() != null
                                        && currentTimestamp - mdp.getTimestamp() <= aggregationIntervalMilliSec) {
                                        metrics.computeIfAbsent(mdp.getMetric().getMetricName(),
                                                k -> new ArrayList<>()).add(mdp);
                                }
                            }
                        } catch (IOException e) {
                            logger.atError().log(e);
                        }
                    }
                });
                aggMetrics.setMetricNamespace(namespace);
                aggMetrics.setTimestamp(currentTimestamp);
                aggMetrics.setMetrics(doAggregation(metrics));
                metricFactory.logMetrics(new TelemetryLoggerMessage(aggMetrics));
            } catch (IOException e) {
                logger.atError().log(e);
            }
        }
    }

    private List<Metric> doAggregation(Map<TelemetryMetricName, List<MetricDataPoint>> metrics) {
        List<Metric> aggMetrics = new ArrayList<>();
        for (Map.Entry<TelemetryMetricName, List<MetricDataPoint>> metric : metrics.entrySet()) {
            TelemetryMetricName metricName = metric.getKey();
            List<MetricDataPoint> mdp = metric.getValue();
            TelemetryAggregation telemetryAggregation = mdp.get(0).getMetric().getMetricAggregation();
            Object aggregation = 0;
            if (telemetryAggregation.equals(TelemetryAggregation.Average)) {
                aggregation = mdp
                        .stream()
                        .filter(Objects::nonNull)
                        .mapToDouble(a -> format(a.getValue()))
                        .sum();
                if (!mdp.isEmpty()) {
                    aggregation = (double) aggregation / mdp.size();
                }
            } else if (telemetryAggregation.equals(TelemetryAggregation.Sum)) {
                aggregation = mdp
                        .stream()
                        .filter(Objects::nonNull)
                        .mapToDouble(a -> format(a.getValue()))
                        .sum();
            } else if (telemetryAggregation.equals(TelemetryAggregation.Maximum)) {
                aggregation = mdp
                        .stream()
                        .filter(Objects::nonNull)
                        .mapToDouble(a -> format(a.getValue()))
                        .max()
                        .getAsDouble();
            } else if (telemetryAggregation.equals(TelemetryAggregation.Minimum)) {
                aggregation = mdp
                        .stream()
                        .filter(Objects::nonNull)
                        .mapToDouble(a -> format(a.getValue()))
                        .min()
                        .getAsDouble();
            }

            Metric m = Metric.builder()
                    .metricName(metricName)
                    .metricUnit(mdp.get(0).getMetric().getMetricUnit())
                    .value(aggregation)
                    .build();
            aggMetrics.add(m);
        }
        return aggMetrics;
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
    }

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

    /**
     * Helper function to process the value of the metric object during aggregation.
     * @param value metric data point value.
     * @return converted value of the object to double. Returns 0 if invalid.
     */
    public double format(Object value) {
        double val = 0;
        if (value != null) {
            try {
                 val = NumberFormat.getInstance().parse(value.toString()).doubleValue();
                return val;
            } catch (ParseException e) {
                logger.atError().log("Error parsing the metric value: " + e);
            }
        }
        return val;
    }
}
