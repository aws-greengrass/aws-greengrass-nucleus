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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.telemetry.MetricsAggregator.AGGREGATE_METRICS_FILE;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class MetricsAggregatorTest {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String GREENGRASS_COMPONENTS_NS = "GreengrassComponents";
    private static final String STREAM_MANAGER_NS = "aws.greengrass.StreamManager";
    private final MetricFactory greengrassComponentsMetricsFactory = new MetricFactory(GREENGRASS_COMPONENTS_NS);
    private final MetricFactory streamManagerMetricsFactory = new MetricFactory(STREAM_MANAGER_NS);
    private final MetricFactory aggregatedMetricFactory = new MetricFactory(AGGREGATE_METRICS_FILE);
    private final MetricsAggregator metricsAggregator = new MetricsAggregator();
    @TempDir
    protected Path tempRootDir;

    @BeforeEach
    void setup() {
        TelemetryConfig.getInstance().setRoot(tempRootDir);
    }

    @AfterEach
    void cleanup() {
        TelemetryConfig.getInstance().closeContext();
    }

    @Test
    void GIVEN_kernel_metrics_WHEN_aggregate_THEN_aggregate_only_the_latest_values()
            throws InterruptedException, IOException {
        //Create a sample file with system metrics so we can test the freshness of the file and logs
        //with respect to the current timestamp
        long lastAgg = Instant.now().toEpochMilli();
        Metric m1 = new Metric(GREENGRASS_COMPONENTS_NS, "A", TelemetryUnit.Percent, TelemetryAggregation.Sum);
        Metric m2 = new Metric(GREENGRASS_COMPONENTS_NS, "B", TelemetryUnit.Megabytes, TelemetryAggregation.Average);
        Metric m3 = new Metric(GREENGRASS_COMPONENTS_NS, "C", TelemetryUnit.Count, TelemetryAggregation.Maximum);
        greengrassComponentsMetricsFactory.putMetricData(m1, 10);
        greengrassComponentsMetricsFactory.putMetricData(m2, 2000);
        greengrassComponentsMetricsFactory.putMetricData(m3, 4000);
        greengrassComponentsMetricsFactory.putMetricData(m1, 20);
        greengrassComponentsMetricsFactory.putMetricData(m2, 3000);
        greengrassComponentsMetricsFactory.putMetricData(m3, 5000);
        greengrassComponentsMetricsFactory.putMetricData(m1, 30);
        greengrassComponentsMetricsFactory.putMetricData(m2, 4000);
        greengrassComponentsMetricsFactory.putMetricData(m3, 6000);
        TimeUnit.MILLISECONDS.sleep(100);
        long currTimestamp = Instant.now().toEpochMilli();
        metricsAggregator.aggregateMetrics(lastAgg, currTimestamp);
        Path path = TelemetryConfig.getTelemetryDirectory().resolve("AggregateMetrics.log");
        List<String> aggregatedMetricLogs = Files.readAllLines(path);
        assertEquals(1, aggregatedMetricLogs.size()); // Metrics are aggregated based on the namespace.
        for (String aggregatedMetricLog : aggregatedMetricLogs) {
            AggregatedNamespaceData am = mapper.readValue(mapper.readTree(aggregatedMetricLog).get("message").asText(),
                    AggregatedNamespaceData.class);
            if (am.getNamespace().equals(GREENGRASS_COMPONENTS_NS)) {
                assertEquals(3, am.getMetrics().size()); // Three system metrics
                for (AggregatedMetric metrics : am.getMetrics()) {
                    switch (metrics.getName()) {
                        case "A":
                            assertEquals((double) 60, metrics.getValue().get("Sum"));
                            break;
                        case "B":
                            assertEquals((double) 3000, metrics.getValue().get("Average"));
                            break;
                        case "C":
                            assertEquals((double) 6000, metrics.getValue().get("Maximum"));
                            break;
                        default:
                            Assertions.fail("Should not get any other metric name");
                            break;
                    }
                }
            }
        }
        lastAgg = currTimestamp;
        TimeUnit.SECONDS.sleep(1);
        currTimestamp = Instant.now().toEpochMilli();
        // Aggregate values within 1 second interval at this timestamp with 1
        metricsAggregator.aggregateMetrics(lastAgg, currTimestamp);
        aggregatedMetricLogs = Files.readAllLines(path);
        assertEquals(1, aggregatedMetricLogs.size()); // AggregateMetrics.log is appended
        //with the latest aggregations.
        for (String aggregatedMetricLog : aggregatedMetricLogs) {
            GreengrassLogMessage egLog = mapper.readValue(aggregatedMetricLog, GreengrassLogMessage.class);
            AggregatedNamespaceData am = mapper.readValue(egLog.getMessage(), AggregatedNamespaceData.class);
            if (am.getTimestamp() == currTimestamp && am.getNamespace().equals(GREENGRASS_COMPONENTS_NS)) {
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
        Metric m1 = new Metric(GREENGRASS_COMPONENTS_NS, "A", TelemetryUnit.Percent, TelemetryAggregation.Sum);
        Metric m2 = new Metric(GREENGRASS_COMPONENTS_NS, "B", TelemetryUnit.Megabytes, TelemetryAggregation.Average);
        // Put null data
        greengrassComponentsMetricsFactory.putMetricData(m1, null);

        // Put invalid data for average aggregation
        greengrassComponentsMetricsFactory.putMetricData(m2, "banana");
        greengrassComponentsMetricsFactory.putMetricData(m2, 2000);
        //put invalid metric
        greengrassComponentsMetricsFactory.logMetrics(new TelemetryLoggerMessage("alfredo"));
        TimeUnit.MILLISECONDS.sleep(100);
        // Aggregate values within 1 second interval at this timestamp with 1
        metricsAggregator.aggregateMetrics(lastAgg, Instant.now().toEpochMilli());
        Path path = TelemetryConfig.getTelemetryDirectory().resolve("AggregateMetrics.log");
        List<String> aggregatedMetricLogs = Files.readAllLines(path);
        assertEquals(1, aggregatedMetricLogs.size()); // Metrics are aggregated based on the namespace.
        for (String aggregatedMetricLog : aggregatedMetricLogs) {
            GreengrassLogMessage egLog = mapper.readValue(aggregatedMetricLog, GreengrassLogMessage.class);
            AggregatedNamespaceData am = mapper.readValue(egLog.getMessage(), AggregatedNamespaceData.class);
            if (am.getNamespace().equals(GREENGRASS_COMPONENTS_NS)) {
                assertEquals(2, am.getMetrics().size()); // Two system metrics, one of them is null
                for (AggregatedMetric metrics : am.getMetrics()) {
                    if (metrics.getName().equals("A")) {
                        assertEquals((double) 0, metrics.getValue().get("Sum")); //No valid data point to aggregate
                    } else if (metrics.getName().equals("B")) {
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
        long currentTimestamp = Instant.now().toEpochMilli();
        List<AggregatedMetric> metricList = new ArrayList<>();
        Map<String, Object> map = new HashMap<>();
        map.put("Average", 4000);
        metricList.add(new AggregatedMetric("C", map, TelemetryUnit.Count));
        map.put("Average", 15);
        metricList.add(new AggregatedMetric("A", map, TelemetryUnit.Percent));
        map.put("Average", 9000);
        metricList.add(new AggregatedMetric("B", map, TelemetryUnit.Megabytes));
        AggregatedNamespaceData aggregatedMetric = new AggregatedNamespaceData(currentTimestamp, GREENGRASS_COMPONENTS_NS, metricList);
        aggregatedMetricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        aggregatedMetricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        aggregatedMetricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        TimeUnit.MILLISECONDS.sleep(100);
        // Create an instance of the metrics uploader to get the aggregated metrics
        Map<Long, List<AggregatedNamespaceData>> aggregatedNamespaceDataMap = metricsAggregator.getMetricsToPublish(lastPublish, currentTimestamp);

        //We perform aggregation on the aggregated data points at the time of publish and get n additional metrics with
        // the current timestamp where n = no of namespaces. But here, we have empty aggregations. so they are not added
        assertEquals(0, aggregatedNamespaceDataMap.size());
        currentTimestamp = Instant.now().toEpochMilli();
        aggregatedNamespaceDataMap = metricsAggregator.getMetricsToPublish(lastPublish, currentTimestamp);
        lastPublish = currentTimestamp;
        // we only have one list of the metrics collected
        assertEquals(1, aggregatedNamespaceDataMap.size());

        //we have 3 entries of the aggregated metrics before this latest TS + one metric for each namespace
        assertEquals(3 + 1, aggregatedNamespaceDataMap.get(currentTimestamp).size());

        TimeUnit.SECONDS.sleep(1);
        currentTimestamp = Instant.now().toEpochMilli();
        aggregatedMetric = new AggregatedNamespaceData(currentTimestamp, GREENGRASS_COMPONENTS_NS, metricList);
        aggregatedMetricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        aggregatedMetricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        TimeUnit.MILLISECONDS.sleep(100);
        currentTimestamp = Instant.now().toEpochMilli();
        aggregatedNamespaceDataMap = metricsAggregator.getMetricsToPublish(lastPublish, currentTimestamp);

        // we only have one list of the metrics collected
        assertEquals(1, aggregatedNamespaceDataMap.size());

        // Will not collect the first 3 entries as they are stale. Latest 2 + n accumulated data point
        assertEquals(2 + 1, aggregatedNamespaceDataMap.get(currentTimestamp).size());
    }

    @Test
    void GIVEN_invalid_aggregated_metrics_WHEN_publish_THEN_parse_them_properly(ExtensionContext exContext) throws InterruptedException {
        ignoreExceptionOfType(exContext, MismatchedInputException.class);
        //Create a sample file with aggregated metrics so we can test the freshness of the file and logs
        // with respect to the current timestamp
        long lastPublish = Instant.now().toEpochMilli();
        long currentTimestamp = Instant.now().toEpochMilli();
        List<AggregatedMetric> metricList = new ArrayList<>();
        Map<String, Object> map1 = new HashMap<>();
        map1.put("Average", 4000);
        AggregatedMetric am = new AggregatedMetric("C", map1, TelemetryUnit.Count);
        metricList.add(am);
        Map<String, Object> map2 = new HashMap<>();
        map2.put("Average", 15);
        am = new AggregatedMetric("A", map2, TelemetryUnit.Percent);
        metricList.add(am);
        Map<String, Object> map3 = new HashMap<>();
        map3.put("Average", 9000);
        am = new AggregatedMetric("B", map3, TelemetryUnit.Megabytes);
        metricList.add(am);
        AggregatedNamespaceData aggregatedMetric = new AggregatedNamespaceData(currentTimestamp, GREENGRASS_COMPONENTS_NS, metricList);
        aggregatedMetricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        aggregatedMetricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        aggregatedMetricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));

        aggregatedMetricFactory.logMetrics(new TelemetryLoggerMessage("buffaloWildWings")); // will be ignored
        aggregatedMetricFactory.logMetrics(new TelemetryLoggerMessage(null)); // will be ignored
        aggregatedMetricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        TimeUnit.MILLISECONDS.sleep(100);
        currentTimestamp = Instant.now().toEpochMilli();
        Map<Long, List<AggregatedNamespaceData>> metricsMap = metricsAggregator.getMetricsToPublish(lastPublish, currentTimestamp);

        // The published metrics will not contain the null aggregated metric
        assertFalse(metricsMap.get(currentTimestamp).contains(null));

        // Out of 6 aggregated metrics, only 4 are published plus there is one accumulated point for each namespace
        assertEquals(4 + 1, metricsMap.get(currentTimestamp).size());

        //The accumulated data points will always be at end the of the list and has the same ts as publish. Acc data
        // points begin from index 4 as first 4 are aggregated metrics
        assertEquals(currentTimestamp, metricsMap.get(currentTimestamp)
                .get(metricsMap.get(currentTimestamp).size()-1).getTimestamp());
        for (AggregatedNamespaceData amet : metricsMap.get(currentTimestamp)) {
            if (amet.getNamespace().equals(GREENGRASS_COMPONENTS_NS)) {
                // There are 3 metrics in GreengrassComponents namespace.
                assertEquals(3, amet.getMetrics().size());
            }
        }
    }

    @Test
    void GIVEN_stream_manager_metrics_WHEN_aggregate_THEN_aggregate_only_the_latest_values()
            throws InterruptedException, IOException {
        //Create a sample file with stream manager metrics so we can test the freshness of the file and logs
        //with respect to the current timestamp
        long lastAgg = Instant.now().toEpochMilli();
        Metric m1 = new Metric(STREAM_MANAGER_NS, "BytesUploadedToS3", TelemetryUnit.Count, TelemetryAggregation.Sum);
        Metric m2 = new Metric(STREAM_MANAGER_NS, "BytesUploadedToKinesis", TelemetryUnit.Count, TelemetryAggregation.Sum);
        Metric m3 = new Metric(STREAM_MANAGER_NS, "BytesUploadedToIotAnalytics", TelemetryUnit.Count, TelemetryAggregation.Sum);
        streamManagerMetricsFactory.putMetricData(m1, 10);
        streamManagerMetricsFactory.putMetricData(m2, 2000);
        streamManagerMetricsFactory.putMetricData(m3, 4000);
        streamManagerMetricsFactory.putMetricData(m1, 20);
        streamManagerMetricsFactory.putMetricData(m2, 3000);
        streamManagerMetricsFactory.putMetricData(m3, 5000);
        streamManagerMetricsFactory.putMetricData(m1, 30);
        streamManagerMetricsFactory.putMetricData(m2, 4000);
        streamManagerMetricsFactory.putMetricData(m3, 6000);
        TimeUnit.MILLISECONDS.sleep(100);
        long currTimestamp = Instant.now().toEpochMilli();

        // Need to delete these files since during the unit tests, these get created. It does not happen when the component calls the API.
        // This is a bug in the greengrass logger where it creates additional loggers on every dot (.)
        metricsAggregator.aggregateMetrics(lastAgg, currTimestamp);
        Path path = TelemetryConfig.getTelemetryDirectory().resolve("AggregateMetrics.log");
        List<String> aggregatedMetricLogs = Files.readAllLines(path);
        assertEquals(1, aggregatedMetricLogs.size()); // Metrics are aggregated based on the namespace.
        for (String aggregatedMetricLog : aggregatedMetricLogs) {
            AggregatedNamespaceData am = mapper.readValue(mapper.readTree(aggregatedMetricLog).get("message").asText(),
                                                          AggregatedNamespaceData.class);
            if (am.getNamespace().equals(STREAM_MANAGER_NS)) {
                assertEquals(3, am.getMetrics().size());
                for (AggregatedMetric metrics : am.getMetrics()) {
                    switch (metrics.getName()) {
                        case "BytesUploadedToS3":
                            assertEquals((double) 60, metrics.getValue().get("Sum"));
                            break;
                        case "BytesUploadedToKinesis":
                            assertEquals((double) 9000, metrics.getValue().get("Sum"));
                            break;
                        case "BytesUploadedToIotAnalytics":
                            assertEquals((double) 15000, metrics.getValue().get("Sum"));
                            break;
                        default:
                            Assertions.fail("Should not get any other metric name");
                            break;
                    }
                }
            }
        }
        lastAgg = currTimestamp;
        TimeUnit.SECONDS.sleep(1);
        currTimestamp = Instant.now().toEpochMilli();
        // Aggregate values within 1 second interval at this timestamp with 1
        metricsAggregator.aggregateMetrics(lastAgg, currTimestamp);
        aggregatedMetricLogs = Files.readAllLines(path);
        assertEquals(1, aggregatedMetricLogs.size()); // AggregateMetrics.log is appended
        //with the latest aggregations.
        for (String aggregatedMetricLog : aggregatedMetricLogs) {
            GreengrassLogMessage egLog = mapper.readValue(aggregatedMetricLog, GreengrassLogMessage.class);
            AggregatedNamespaceData am = mapper.readValue(egLog.getMessage(), AggregatedNamespaceData.class);
            if (am.getTimestamp() == currTimestamp && am.getNamespace().equals(STREAM_MANAGER_NS)) {
                assertEquals(0, am.getMetrics().size()); // There is no aggregation as there are no latest values
            }
        }
    }


    @Test
    void GIVEN_aggregated_metrics_for_stream_manager_WHEN_publish_THEN_does_not_send_accumulated_metric() throws InterruptedException {
        //Create a sample file with aggregated metrics so we can test the freshness of the file and logs
        // with respect to the current timestamp
        long lastPublish = Instant.now().toEpochMilli();
        long currentTimestamp = Instant.now().toEpochMilli();
        List<AggregatedMetric> metricList = new ArrayList<>();
        Map<String, Object> map = new HashMap<>();
        map.put("Sum", 4000);
        metricList.add(new AggregatedMetric("BytesUploadedToS3", map, TelemetryUnit.Count));
        map.put("Sum", 15);
        metricList.add(new AggregatedMetric("BytesUploadedToKinesis", map, TelemetryUnit.Count));
        map.put("Sum", 9000);
        metricList.add(new AggregatedMetric("BytesUploadedToIotAnalytics", map, TelemetryUnit.Count));
        AggregatedNamespaceData aggregatedMetric = new AggregatedNamespaceData(currentTimestamp, STREAM_MANAGER_NS, metricList);
        aggregatedMetricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        aggregatedMetricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        aggregatedMetricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        TimeUnit.MILLISECONDS.sleep(100);
        // Create an instance of the metrics uploader to get the aggregated metrics
        Map<Long, List<AggregatedNamespaceData>> aggregatedNamespaceDataMap = metricsAggregator.getMetricsToPublish(lastPublish, currentTimestamp);

        //We perform aggregation on the aggregated data points at the time of publish and get n additional metrics with
        // the current timestamp where n = no of namespaces. But here, we have empty aggregations. so they are not added
        assertEquals(0, aggregatedNamespaceDataMap.size());
        currentTimestamp = Instant.now().toEpochMilli();
        aggregatedNamespaceDataMap = metricsAggregator.getMetricsToPublish(lastPublish, currentTimestamp);
        // we only have one list of the metrics collected
        assertEquals(1, aggregatedNamespaceDataMap.size());

        //we have 3 entries of the aggregated metrics before this latest TS
        assertEquals(3, aggregatedNamespaceDataMap.get(currentTimestamp).size());
    }
}
