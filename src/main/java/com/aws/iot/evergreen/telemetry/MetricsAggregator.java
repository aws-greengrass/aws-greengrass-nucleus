package com.aws.iot.evergreen.telemetry;

import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.telemetry.aggregation.AggregatedMetric;
import com.aws.iot.evergreen.telemetry.api.MetricDataBuilder;
import com.aws.iot.evergreen.telemetry.config.TelemetryDataConfig;
import com.aws.iot.evergreen.telemetry.impl.MetricDataPoint;
import com.aws.iot.evergreen.telemetry.models.TelemetryAggregation;
import com.aws.iot.evergreen.telemetry.models.TelemetryMetricName;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.joda.time.Instant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.aws.iot.evergreen.telemetry.MetricsAgent.createSampleConfiguration;

public class MetricsAggregator {
    private static final Logger logger = LogManager.getLogger(MetricsAggregator.class);

    /**
     * Aggregate metrics based on the telemetry config.
     * @param context use this to schedule thread pool.
     */
    public void aggregateMetrics(Context context) {
        // TODO read from a telemetry config file.
        Map<String, TelemetryDataConfig> telemetryDataConfig = createSampleConfiguration();
        for (Map.Entry<String, TelemetryDataConfig> config: telemetryDataConfig.entrySet()) {
            TelemetryDataConfig metricConfig = config.getValue();
            ScheduledExecutorService executor = context.get(ScheduledExecutorService.class);
            executor.scheduleAtFixedRate(readLogsAndAggregate(metricConfig), 0, metricConfig.getAggregateFrequency(),
                    TimeUnit.SECONDS);
        }
    }

    private Runnable readLogsAndAggregate(TelemetryDataConfig config) {
        return () -> {
            /*
             read from the file at Telemetry/namespace.log
             Does it always have the same name?
             Also, aggregate only the new values. Achieve this with timestamp? Then maintain a timestamp?
             */
            String sampleLogFile = "src/test/resources/com/aws/iot/evergreen/Telemetry/sample_logs/"
                    + config.getMetricNamespace() + ".log";
            logger.atError().log(System.getProperty("user.dir"));
            AggregatedMetric aggMetrics = new AggregatedMetric();
            HashMap<TelemetryMetricName, List<MetricDataPoint>> metrics = new HashMap<>();
            try {
                List<String> logs = Files.lines(Paths.get(sampleLogFile)).collect(Collectors.toList());
                for (String log:logs) {
                    /*
                    [0]  [1] [2] [3]                [4]         [5]             [6]                         [7]
                    2020 Aug 28 12:08:21,520-0700 [TRACE] (pool-3-thread-4) Metrics-KernelComponents: {"M":{"NS":
                    "KernelComponents","N":"NumberOfComponentsStopping","U":"Count"},"V":0,"TS":1598598501520}. {}
                     */
                    MetricDataPoint mdp = new ObjectMapper().readValue(log.split(" ")[7], MetricDataPoint.class);
                    aggMetrics.setMetricNamespace(mdp.getMetric().getMetricNamespace());
                    aggMetrics.setTimestamp(Instant.now().getMillis());
                    metrics.computeIfAbsent(mdp.getMetric().getMetricName(),
                            k -> new ArrayList<>()).add(mdp);
                }
            } catch (IOException e) {
                logger.atError().log("Error in the file operation:" + e);
            }
            aggMetrics.setMetric(doAggregation(metrics, TelemetryAggregation.valueOf(config.getAggregationType())));
            try {
                /*
                    Writing to memory for now. But write to a file?
                 */
                logger.atInfo().log(new ObjectMapper().writeValueAsString(aggMetrics));
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
        };
    }

    private List<AggregatedMetric.Metric> doAggregation(HashMap<TelemetryMetricName, List<MetricDataPoint>> metrics,
                                                        TelemetryAggregation telemetryAggregation) {
        List<AggregatedMetric.Metric> aggMetrics = new ArrayList<>();
        for (Map.Entry<TelemetryMetricName, List<MetricDataPoint>> metric : metrics.entrySet()) {
            TelemetryMetricName metricName = metric.getKey();
            List<MetricDataPoint> mdp = metric.getValue();
            Object aggregation = null;
            if (telemetryAggregation.equals(TelemetryAggregation.Average)) {
                aggregation = mdp
                        .stream()
                        .mapToDouble(a -> Double.parseDouble(a.getValue().toString()))
                        .sum();
                if (mdp.size() > 0) {
                    aggregation = (double) aggregation / mdp.size();
                }
            } else if (telemetryAggregation.equals(TelemetryAggregation.Sum)) {
                aggregation = mdp
                        .stream()
                        .mapToDouble(a -> Double.parseDouble(a.getValue().toString()))
                        .sum();
            } else if (telemetryAggregation.equals(TelemetryAggregation.Maximum)) {
                aggregation = mdp
                        .stream()
                        .mapToDouble(a -> Double.parseDouble(a.getValue().toString()))
                        .max();
            } else if (telemetryAggregation.equals(TelemetryAggregation.Minimum)) {
                aggregation = mdp
                        .stream()
                        .mapToDouble(a -> Double.parseDouble(a.getValue().toString()))
                        .min();
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
