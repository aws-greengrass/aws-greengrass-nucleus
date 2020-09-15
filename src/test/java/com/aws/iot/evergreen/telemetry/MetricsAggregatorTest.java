/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.telemetry;

import com.aws.iot.evergreen.logging.impl.EvergreenStructuredLogMessage;
import com.aws.iot.evergreen.telemetry.impl.Metric;
import com.aws.iot.evergreen.telemetry.impl.MetricFactory;
import com.aws.iot.evergreen.telemetry.impl.TelemetryLoggerMessage;
import com.aws.iot.evergreen.telemetry.impl.config.TelemetryConfig;
import com.aws.iot.evergreen.telemetry.models.TelemetryAggregation;
import com.aws.iot.evergreen.telemetry.models.TelemetryMetricName;
import com.aws.iot.evergreen.telemetry.models.TelemetryNamespace;
import com.aws.iot.evergreen.telemetry.models.TelemetryUnit;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.aws.iot.evergreen.telemetry.MetricsAggregator.AGGREGATE_METRICS_FILE;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class MetricsAggregatorTest {
    private static final ObjectMapper mapper = new ObjectMapper();
    @TempDir
    protected Path tempRootDir;

    @BeforeEach
    public void setup() {
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());
    }

    @Test
    public void GIVEN_system_metrics_WHEN_aggregate_THEN_aggregate_only_the_latest_values()
            throws InterruptedException, IOException {
        //Create a sample file with system metrics so we can test the freshness of the file and logs
        //with respect to the current timestamp
        long lastAgg = Instant.now().toEpochMilli();
        Metric m1 = new Metric(TelemetryNamespace.SystemMetrics, TelemetryMetricName.CpuUsage,
                TelemetryUnit.Percent, TelemetryAggregation.Sum);
        MetricFactory mf = new MetricFactory(TelemetryNamespace.SystemMetrics.toString());
        Metric m2 = new Metric(TelemetryNamespace.SystemMetrics, TelemetryMetricName.SystemMemUsage,
                TelemetryUnit.Megabytes, TelemetryAggregation.Average);
        Metric m3 = new Metric(TelemetryNamespace.SystemMetrics, TelemetryMetricName.TotalNumberOfFDs,
                TelemetryUnit.Count, TelemetryAggregation.Maximum);
        mf.putMetricData(m1,10);
        mf.putMetricData(m2,2000);
        mf.putMetricData(m3,4000);
        mf.putMetricData(m1,20);
        mf.putMetricData(m2,3000);
        mf.putMetricData(m3,5000);
        mf.putMetricData(m1,30);
        mf.putMetricData(m2,4000);
        mf.putMetricData(m3,6000);
        MetricsAggregator ma = new MetricsAggregator();
        long currTimestamp = Instant.now().toEpochMilli();
        ma.aggregateMetrics(lastAgg, currTimestamp);
        Path path = Paths.get(TelemetryConfig.getTelemetryDirectory().toString()).resolve("AggregateMetrics.log");
        List<String> list = Files.lines(path).collect(Collectors.toList());
        assertEquals(TelemetryNamespace.values().length, list.size()); // Metrics are aggregated based on the namespace.
        for (String s : list) {
            MetricsAggregator.AggregatedMetric am = mapper.readValue(mapper.readTree(s).get("message").asText(),
                    MetricsAggregator.AggregatedMetric.class);
            if (am.getMetricNamespace().equals(TelemetryNamespace.SystemMetrics)) {
                assertEquals(3, am.getMetrics().size()); // Three system metrics
                for (MetricsAggregator.AggregatedMetric.Metric metrics : am.getMetrics()) {
                    if (metrics.getMetricName().equals(TelemetryMetricName.CpuUsage)) {
                        assertEquals((double) 60, metrics.getValue());
                    } else if (metrics.getMetricName().equals(TelemetryMetricName.SystemMemUsage)) {
                        assertEquals((double) 3000, metrics.getValue());
                    } else if (metrics.getMetricName().equals(TelemetryMetricName.TotalNumberOfFDs)) {
                        assertEquals((double) 6000, (double) metrics.getValue());
                    }
                }
            }
        }
        lastAgg = currTimestamp;
        Thread.sleep(1000);
        long currentTimestamp = Instant.now().toEpochMilli();
        // Aggregate values within 1 second interval at this timestamp with 1
        ma.aggregateMetrics(lastAgg, currentTimestamp);
        list = Files.lines(path).collect(Collectors.toList());
        assertEquals(8, list.size()); // AggregateMetrics.log is appended with the latest aggregations.
        for (String s : list) {
            EvergreenStructuredLogMessage egLog = mapper.readValue(s, EvergreenStructuredLogMessage.class);
            MetricsAggregator.AggregatedMetric am = mapper.readValue(egLog.getMessage(),
                    MetricsAggregator.AggregatedMetric.class);
            if (am.getTimestamp() == currentTimestamp && am.getMetricNamespace().equals(TelemetryNamespace.SystemMetrics)) {
                assertEquals(0, am.getMetrics().size()); // There is no aggregation as there are no latest values
            }
        }
    }

    @Test
    public void GIVEN_invalid_metrics_WHEN_aggregate_THEN_parse_them_properly() throws IOException {
        //Create a sample file with aggregated metrics so we can test the freshness of the file and logs
        // with respect to the current timestamp
        long lastAgg = Instant.now().toEpochMilli();
        Metric m1 = new Metric(TelemetryNamespace.SystemMetrics, TelemetryMetricName.CpuUsage,
                TelemetryUnit.Percent, TelemetryAggregation.Sum);
        MetricFactory mf = new MetricFactory(TelemetryNamespace.SystemMetrics.toString());
        Metric m2 = new Metric(TelemetryNamespace.SystemMetrics, TelemetryMetricName.SystemMemUsage,
                TelemetryUnit.Megabytes, TelemetryAggregation.Average);

        // Put null data
        mf.putMetricData(m1,null);

        // Put invalid data for average aggregation
        mf.putMetricData(m2,"banana");
        mf.putMetricData(m2,2000);
        MetricsAggregator ma = new MetricsAggregator();
        // Aggregate values within 1 second interval at this timestamp with 1
        ma.aggregateMetrics(lastAgg, Instant.now().toEpochMilli());
        Path path = Paths.get(TelemetryConfig.getTelemetryDirectory().toString()).resolve("AggregateMetrics.log");
        List<String> list = Files.lines(path).collect(Collectors.toList());
        assertEquals(TelemetryNamespace.values().length, list.size()); // Metrics are aggregated based on the namespace.
        for (String s : list) {
            MetricsAggregator.AggregatedMetric am = mapper.readValue(mapper.readTree(s).get("message").asText(),
                    MetricsAggregator.AggregatedMetric.class);
            if (am.getMetricNamespace().equals(TelemetryNamespace.SystemMetrics)) {
                assertEquals(2, am.getMetrics().size()); // Two system metrics, one of them is null
                for (MetricsAggregator.AggregatedMetric.Metric metrics : am.getMetrics()) {
                    if (metrics.getMetricName().equals(TelemetryMetricName.CpuUsage)) {
                        assertEquals((double) 0, metrics.getValue()); //No valid data point to aggregate
                    } else if (metrics.getMetricName().equals(TelemetryMetricName.SystemMemUsage)) {
                        assertEquals((double) 1000, metrics.getValue()); // ignore the invalid value
                    }
                }
            }
        }
    }

    @Test
    public void GIVEN_aggregated_metrics_WHEN_publish_THEN_collect_only_the_lates_values() throws InterruptedException {
        //Create a sample file with aggregated metrics so we can test the freshness of the file and logs
        // with respect to the current timestamp
        long lastPublish = Instant.now().toEpochMilli();
        MetricFactory metricFactory = new MetricFactory(AGGREGATE_METRICS_FILE);
        long currentTimestamp = Instant.now().toEpochMilli();
        List<MetricsAggregator.AggregatedMetric.Metric> metricList = new ArrayList<>();
        metricList.add(new MetricsAggregator.AggregatedMetric.Metric(TelemetryMetricName.TotalNumberOfFDs, 4000, TelemetryUnit.Count));
        metricList.add(new MetricsAggregator.AggregatedMetric.Metric(TelemetryMetricName.CpuUsage, 15, TelemetryUnit.Percent));
        metricList.add(new MetricsAggregator.AggregatedMetric.Metric(TelemetryMetricName.SystemMemUsage, 9000, TelemetryUnit.Megabytes));
        MetricsAggregator.AggregatedMetric aggregatedMetric = new MetricsAggregator.AggregatedMetric
                (currentTimestamp, TelemetryNamespace.SystemMetrics, metricList);
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        // Create an instance of the metrics uploader to get the aggregated metrics
        MetricsAggregator ma = new MetricsAggregator();
        Map<Long, List<MetricsAggregator.AggregatedMetric>> list = ma.getMetricsToPublish(lastPublish, currentTimestamp);

        //we don't want to collect those metrics that are emitted at the same time of collection
        assertEquals(0, list.get(currentTimestamp).size());
        currentTimestamp = Instant.now().toEpochMilli();
        list = ma.getMetricsToPublish(lastPublish, currentTimestamp);
        lastPublish = currentTimestamp;
        // we only have one list of the metrics collected
        assertEquals(1, list.size());

        //we have 3 entries of the aggregated metrics before this latest TS
        assertEquals(3, list.get(currentTimestamp).size());

        Thread.sleep(1000);
        currentTimestamp = Instant.now().toEpochMilli();
        aggregatedMetric = new MetricsAggregator.AggregatedMetric
                (currentTimestamp, TelemetryNamespace.SystemMetrics, metricList);
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        currentTimestamp = Instant.now().toEpochMilli();
        list = ma.getMetricsToPublish(lastPublish, currentTimestamp);

        // we only have one list of the metrics collected
        assertEquals(1, list.size());

        // Will not collect the first 3 entries as they are stale
        assertEquals(2, list.get(currentTimestamp).size());
    }

    @Test
    public void GIVEN_invalid_aggregated_metrics_WHEN_publish_THEN_parse_them_properly(ExtensionContext context) {
        ignoreExceptionOfType(context, MismatchedInputException.class);
        //Create a sample file with aggregated metrics so we can test the freshness of the file and logs
        // with respect to the current timestamp
        long lastPublish = Instant.now().toEpochMilli();
        MetricFactory metricFactory = new MetricFactory(AGGREGATE_METRICS_FILE);
        long currentTimestamp = Instant.now().toEpochMilli();
        List<MetricsAggregator.AggregatedMetric.Metric> metricList = new ArrayList<>();
        metricList.add(new MetricsAggregator.AggregatedMetric.Metric(TelemetryMetricName.TotalNumberOfFDs, 4000, TelemetryUnit.Count));
        metricList.add(new MetricsAggregator.AggregatedMetric.Metric(TelemetryMetricName.CpuUsage, 15, TelemetryUnit.Percent));
        metricList.add(new MetricsAggregator.AggregatedMetric.Metric(TelemetryMetricName.SystemMemUsage, 9000, TelemetryUnit.Megabytes));
        MetricsAggregator.AggregatedMetric aggregatedMetric = new MetricsAggregator.AggregatedMetric
                (currentTimestamp, TelemetryNamespace.SystemMetrics, metricList);
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));

        metricFactory.logMetrics(new TelemetryLoggerMessage("buffaloWildWings")); // will be ignored
        metricFactory.logMetrics(new TelemetryLoggerMessage(null)); // will be ignored
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        MetricsAggregator ma = new MetricsAggregator();
        currentTimestamp = Instant.now().toEpochMilli();
        Map<Long, List<MetricsAggregator.AggregatedMetric>> list = ma.getMetricsToPublish(lastPublish, currentTimestamp);

        // The published metrics will not contain the null aggregated metric
        assertFalse(list.get(currentTimestamp).contains(null));

        // Out of 6 aggregated metrics, only 4 are published
        assertEquals(4, list.get(currentTimestamp).size());
    }
}