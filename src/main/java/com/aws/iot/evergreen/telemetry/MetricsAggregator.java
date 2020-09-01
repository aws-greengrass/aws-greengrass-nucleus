package com.aws.iot.evergreen.telemetry;

import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.telemetry.aggregation.AggregatedMetric;
import com.aws.iot.evergreen.telemetry.config.TelemetryDataConfig;
import com.aws.iot.evergreen.telemetry.impl.MetricDataPoint;
import com.aws.iot.evergreen.telemetry.models.TelemetryAggregation;
import com.aws.iot.evergreen.telemetry.models.TelemetryMetricName;
import com.aws.iot.evergreen.telemetry.models.TelemetryNamespace;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.aws.iot.evergreen.telemetry.MetricsAgent.createSampleConfiguration;

public class MetricsAggregator {
    public static final Logger logger = LogManager.getLogger(MetricsAggregator.class);
    public static final String LOG_FILES_PATH = System.getProperty("user.dir") + "/Telemetry/";

    /**
     * Aggregate metrics based on the telemetry config.
     * @param context use this to schedule thread pool.
     */
    public void aggregateMetrics(Context context) {
        // TODO read from a telemetry config file.
        for (Map.Entry<String, TelemetryDataConfig> config: createSampleConfiguration().entrySet()) {
            TelemetryDataConfig metricConfig = config.getValue();
            ScheduledExecutorService executor = context.get(ScheduledExecutorService.class);
            executor.scheduleAtFixedRate(readLogsAndAggregate(metricConfig), 0, metricConfig.getAggregateFrequency(),
                    TimeUnit.MILLISECONDS);
        }
    }

    private Runnable readLogsAndAggregate(TelemetryDataConfig config) {
        return () -> {
            long currentTimestamp = Instant.now().toEpochMilli();
            AggregatedMetric aggMetrics = new AggregatedMetric();
            HashMap<TelemetryMetricName, List<MetricDataPoint>> metrics = new HashMap<>();
            try {
                Stream<Path> paths = Files
                        .walk(Paths.get(LOG_FILES_PATH))
                        .filter(Files::isRegularFile);
                paths.forEach((path) -> {
                    /*
                     Read from the file at Telemetry/namespace*.log
                     Read only modified files and aggregate only new values based on the last aggregated time.
                     */
                    Object fileName = null;
                    if (path != null) {
                        fileName = path.getFileName();
                    }
                    if (fileName == null) {
                        fileName = "";
                    }
                    if (fileName.toString().matches(config.getMetricNamespace() + "(.*)")
                            && currentTimestamp - new File(path.toString()).lastModified()
                            <= config.getAggregateFrequency()) {
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
                                if (currentTimestamp - mdp.getTimestamp() <= config.getAggregateFrequency()) {
                                    metrics.computeIfAbsent(mdp.getMetric().getMetricName(),
                                            k -> new ArrayList<>()).add(mdp);
                                }
                            }
                        } catch (IOException e) {
                            logger.atError().log("Failed to read the telemetry logs for aggregation." + e);
                        }
                    }

                });
                aggMetrics.setMetricNamespace(TelemetryNamespace.valueOf(config.getMetricNamespace()));
                aggMetrics.setTimestamp(currentTimestamp);
                aggMetrics.setMetric(doAggregation(metrics, TelemetryAggregation.valueOf(config.getAggregationType())));
                /*
                    Writing to memory for now. But write to a file?
                 */
                logger.atInfo().log(new ObjectMapper().writeValueAsString(aggMetrics));
            } catch (IOException e) {
                logger.atError().log("Failed to aggregate metrics." + e);
            }
        };
    }

    private List<AggregatedMetric.Metric> doAggregation(Map<TelemetryMetricName, List<MetricDataPoint>> metrics,
                                                        TelemetryAggregation telemetryAggregation) {
        List<AggregatedMetric.Metric> aggMetrics = new ArrayList<>();
        for (Map.Entry<TelemetryMetricName, List<MetricDataPoint>> metric : metrics.entrySet()) {
            TelemetryMetricName metricName = metric.getKey();
            List<MetricDataPoint> mdp = metric.getValue();
            Object aggregation = null;
            switch (telemetryAggregation) {
                case Average:
                    aggregation = mdp
                            .stream()
                            .mapToDouble(a -> Double.parseDouble(a.getValue().toString()))
                            .sum();
                    if (!mdp.isEmpty()) {
                        aggregation = (double) aggregation / mdp.size();
                    }
                    break;
                case Sum:
                    aggregation = mdp
                            .stream()
                            .mapToDouble(a -> Double.parseDouble(a.getValue().toString()))
                            .sum();
                    break;
                case Maximum:
                    aggregation = mdp
                            .stream()
                            .mapToDouble(a -> Double.parseDouble(a.getValue().toString()))
                            .max();
                    break;
                case Minimum:
                    aggregation = mdp
                            .stream()
                            .mapToDouble(a -> Double.parseDouble(a.getValue().toString()))
                            .min();
                    break;
                default:
                    logger.atError().log("Unknown aggregation type: " + telemetryAggregation);
                    break;
            }

            AggregatedMetric.Metric m = AggregatedMetric.Metric.builder()
                    .metricName(metricName)
                    .metricUnit(mdp.get(0).getMetric().getMetricUnit())
                    .value(aggregation)
                    .build();
            aggMetrics.add(m);
        }
        return aggMetrics;
    }
}
