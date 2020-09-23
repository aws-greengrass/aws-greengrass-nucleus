/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.telemetry;

import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.telemetry.impl.Metric;
import com.aws.greengrass.telemetry.impl.MetricFactory;
import com.aws.greengrass.telemetry.impl.TelemetryLoggerMessage;
import com.aws.greengrass.telemetry.impl.config.TelemetryConfig;
import com.aws.greengrass.telemetry.models.TelemetryAggregation;
import com.aws.greengrass.telemetry.models.TelemetryUnit;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import org.junit.jupiter.api.AfterEach;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.aws.greengrass.telemetry.MetricsAggregator.AGGREGATE_METRICS_FILE;
import static com.aws.greengrass.telemetry.MetricsAggregator.AggregatedMetric;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class MetricsAggregatorTest {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String sm = "SystemMetrics";
    private final MetricFactory mf = new MetricFactory(sm);
    private final MetricFactory metricFactory = new MetricFactory(AGGREGATE_METRICS_FILE);
    @TempDir
    protected Path tempRootDir;
    private final MetricsAggregator ma = new MetricsAggregator();

    @BeforeEach
    void setup() {
        TelemetryConfig.getInstance().setRoot(tempRootDir);
    }

    @AfterEach
    void cleanup() {
        TelemetryConfig.getInstance().closeContext();
    }

    @Test
    void GIVEN_system_metrics_WHEN_aggregate_THEN_aggregate_only_the_latest_values()
            throws InterruptedException, IOException {
        //Create a sample file with system metrics so we can test the freshness of the file and logs
        //with respect to the current timestamp
        long lastAgg = Instant.now().toEpochMilli();
        Metric m1 = new Metric(sm, "CpuUsage", TelemetryUnit.Percent, TelemetryAggregation.Sum);
        Metric m2 = new Metric(sm, "SystemMemUsage", TelemetryUnit.Megabytes, TelemetryAggregation.Average);
        Metric m3 = new Metric(sm, "TotalNumberOfFDs", TelemetryUnit.Count, TelemetryAggregation.Maximum);
        mf.putMetricData(m1, 10);
        mf.putMetricData(m2, 2000);
        mf.putMetricData(m3, 4000);
        mf.putMetricData(m1, 20);
        mf.putMetricData(m2, 3000);
        mf.putMetricData(m3, 5000);
        mf.putMetricData(m1, 30);
        mf.putMetricData(m2, 4000);
        mf.putMetricData(m3, 6000);
        Thread.sleep(100);
        long currTimestamp = Instant.now().toEpochMilli();
        ma.aggregateMetrics(lastAgg, currTimestamp);
        Path path = Paths.get(TelemetryConfig.getTelemetryDirectory().toString()).resolve(
                "AggregateMetrics.log");
        List<String> list = Files.readAllLines(path);
        assertEquals(MetricsAggregator.getNamespaceSet().size(), list.size()); // Metrics are aggregated based on the namespace.
        for (String s : list) {
            AggregatedMetric am = mapper.readValue(mapper.readTree(s).get("message").asText(),
                    AggregatedMetric.class);
            if (am.getNamespace().equals(sm)) {
                assertEquals(3, am.getMetrics().size()); // Three system metrics
                for (AggregatedMetric.Metric metrics : am.getMetrics()) {
                    if (metrics.getName().equals("CpuUsage")) {
                        assertEquals((double) 60, metrics.getValue().get("Sum"));
                    } else if (metrics.getName().equals("SystemMemUsage")) {
                        assertEquals((double) 3000, metrics.getValue().get("Average"));
                    } else if (metrics.getName().equals("TotalNumberOfFDs")) {
                        assertEquals((double) 6000, metrics.getValue().get("Maximum"));
                    }
                }
            }
        }
        lastAgg = currTimestamp;
        Thread.sleep(1000);
        currTimestamp = Instant.now().toEpochMilli();
        // Aggregate values within 1 second interval at this timestamp with 1
        ma.aggregateMetrics(lastAgg, currTimestamp);
        list = Files.readAllLines(path);
        assertEquals(2, list.size()); // AggregateMetrics.log is appended with the latest aggregations.
        for (String s : list) {
            GreengrassLogMessage egLog = mapper.readValue(s, GreengrassLogMessage.class);
            AggregatedMetric am = mapper.readValue(egLog.getMessage(),
                    AggregatedMetric.class);
            if (am.getTimestamp() == currTimestamp && am.getNamespace().equals("SystemMetrics")) {
                assertEquals(0, am.getMetrics().size()); // There is no aggregation as there are no latest values
            }
        }
    }

    @Test
    void GIVEN_invalid_metrics_WHEN_aggregate_THEN_parse_them_properly(ExtensionContext exContext) throws IOException,
            InterruptedException {
        //Create a sample file with aggregated metrics so we can test the freshness of the file and logs
        // with respect to the current timestamp
        ignoreExceptionOfType(exContext, MismatchedInputException.class);
        long lastAgg = Instant.now().toEpochMilli();
        Metric m1 = new Metric(sm, "CpuUsage", TelemetryUnit.Percent, TelemetryAggregation.Sum);
        Metric m2 = new Metric(sm, "SystemMemUsage", TelemetryUnit.Megabytes, TelemetryAggregation.Average);
        // Put null data
        mf.putMetricData(m1, null);

        // Put invalid data for average aggregation
        mf.putMetricData(m2, "banana");
        mf.putMetricData(m2, 2000);
        //put invalid metric
        mf.logMetrics(new TelemetryLoggerMessage("alfredo"));
        Thread.sleep(100);
        // Aggregate values within 1 second interval at this timestamp with 1
        ma.aggregateMetrics(lastAgg, Instant.now().toEpochMilli());
        Path path = Paths.get(TelemetryConfig.getTelemetryDirectory().toString()).resolve("AggregateMetrics.log");
        List<String> list = Files.readAllLines(path);
        assertEquals(MetricsAggregator.getNamespaceSet().size(), list.size()); // Metrics are aggregated based on the namespace.
        for (String s : list) {
            AggregatedMetric am = mapper.readValue(mapper.readTree(s).get("message").asText(),
                    AggregatedMetric.class);
            if (am.getNamespace().equals(sm)) {
                assertEquals(2, am.getMetrics().size()); // Two system metrics, one of them is null
                for (AggregatedMetric.Metric metrics : am.getMetrics()) {
                    if (metrics.getName().equals("CpuUsage")) {
                        assertEquals((double) 0, metrics.getValue().get("Sum")); //No valid data point to aggregate
                    } else if (metrics.getName().equals("SystemMemUsage")) {
                        assertEquals((double) 1000, metrics.getValue().get("Average")); // ignore the invalid value
                    }
                }
            }
        }
    }

    @Test
    void GIVEN_aggregated_metrics_WHEN_publish_THEN_collect_only_the_latest_values() throws InterruptedException {
        //Create a sample file with aggregated metrics so we can test the freshness of the file and logs
        // with respect to the current timestamp
        long lastPublish = Instant.now().toEpochMilli();
//        MetricFactory metricFactory = new MetricFactory(AGGREGATE_METRICS_FILE);
        long currentTimestamp = Instant.now().toEpochMilli();
        List<AggregatedMetric.Metric> metricList = new ArrayList<>();
        Map<String, Object> map = new HashMap<>();
        map.put("Average", 4000);
        metricList.add(new AggregatedMetric.Metric("TotalNumberOfFDs", map, TelemetryUnit.Count));
        map.put("Average", 15);
        metricList.add(new AggregatedMetric.Metric("CpuUsage", map, TelemetryUnit.Percent));
        map.put("Average", 9000);
        metricList.add(new AggregatedMetric.Metric("SystemMemUsage", map, TelemetryUnit.Megabytes));
        AggregatedMetric aggregatedMetric = new AggregatedMetric(currentTimestamp, sm, metricList);
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        Thread.sleep(100);
        // Create an instance of the metrics uploader to get the aggregated metrics
        Map<Long, List<AggregatedMetric>> list = ma.getMetricsToPublish(lastPublish, currentTimestamp);

        //We perform aggregation on the aggregated data points at the time of publish and get n additional metrics with
        // the current timestamp where n = no of namespaces. In this test, we have only 1 namespace i.e SystemMetrics
        assertEquals(1, list.get(currentTimestamp).size());
        currentTimestamp = Instant.now().toEpochMilli();
        list = ma.getMetricsToPublish(lastPublish, currentTimestamp);
        lastPublish = currentTimestamp;
        // we only have one list of the metrics collected
        assertEquals(1, list.size());

        //we have 3 entries of the aggregated metrics before this latest TS + 1 entry which is the aggregation of those
        // 3 entries
        assertEquals(4, list.get(currentTimestamp).size());

        Thread.sleep(1000);
        currentTimestamp = Instant.now().toEpochMilli();
        aggregatedMetric = new AggregatedMetric(currentTimestamp, sm, metricList);
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        Thread.sleep(100);
        currentTimestamp = Instant.now().toEpochMilli();
        list = ma.getMetricsToPublish(lastPublish, currentTimestamp);

        // we only have one list of the metrics collected
        assertEquals(1, list.size());

        // Will not collect the first 3 entries as they are stale. Latest 2 + 1 accumulated data point
        assertEquals(3, list.get(currentTimestamp).size());
    }

    @Test
    void GIVEN_invalid_aggregated_metrics_WHEN_publish_THEN_parse_them_properly(ExtensionContext exContext) throws InterruptedException {
        ignoreExceptionOfType(exContext, MismatchedInputException.class);
        //Create a sample file with aggregated metrics so we can test the freshness of the file and logs
        // with respect to the current timestamp
        long lastPublish = Instant.now().toEpochMilli();
        long currentTimestamp = Instant.now().toEpochMilli();
        List<AggregatedMetric.Metric> metricList = new ArrayList<>();
        Map<String, Object> map1 = new HashMap<>();
        map1.put("Average", 4000);
        AggregatedMetric.Metric am = new AggregatedMetric.Metric("TotalNumberOfFDs", map1, TelemetryUnit.Count);
        metricList.add(am);
        Map<String, Object> map2 = new HashMap<>();
        map2.put("Average", 15);
        am = new AggregatedMetric.Metric("CpuUsage", map2, TelemetryUnit.Percent);
        metricList.add(am);
        Map<String, Object> map3 = new HashMap<>();
        map3.put("Average", 9000);
        am = new AggregatedMetric.Metric("SystemMemUsage", map3, TelemetryUnit.Megabytes);
        metricList.add(am);
        AggregatedMetric aggregatedMetric = new AggregatedMetric(currentTimestamp, sm, metricList);
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));

        metricFactory.logMetrics(new TelemetryLoggerMessage("buffaloWildWings")); // will be ignored
        metricFactory.logMetrics(new TelemetryLoggerMessage(null)); // will be ignored
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        Thread.sleep(100);
        currentTimestamp = Instant.now().toEpochMilli();
        Map<Long, List<AggregatedMetric>> metricsMap = ma.getMetricsToPublish(lastPublish, currentTimestamp);

        // The published metrics will not contain the null aggregated metric
        assertFalse(metricsMap.get(currentTimestamp).contains(null));

        // Out of 6 aggregated metrics, only 4 are published plus there is one accumulated point for each namespace
        // during publish,here we have 1 namespace - SystemMetrics
        assertEquals(5, metricsMap.get(currentTimestamp).size());

        //The accumulated data point will always be the last one of the list and has the same ts as publish
        assertEquals(currentTimestamp, metricsMap.get(currentTimestamp).get(4).getTimestamp());

        // There are 3 metrics in the our system metrics namespace.
        assertEquals(3, metricsMap.get(currentTimestamp).get(4).getMetrics().size());
    }
}