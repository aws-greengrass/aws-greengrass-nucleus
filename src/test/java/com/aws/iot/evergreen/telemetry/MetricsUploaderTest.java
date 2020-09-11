package com.aws.iot.evergreen.telemetry;

import com.aws.iot.evergreen.telemetry.impl.MetricFactory;
import com.aws.iot.evergreen.telemetry.impl.TelemetryLoggerMessage;
import com.aws.iot.evergreen.telemetry.models.TelemetryMetricName;
import com.aws.iot.evergreen.telemetry.models.TelemetryNamespace;
import com.aws.iot.evergreen.telemetry.models.TelemetryUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.aws.iot.evergreen.telemetry.MetricsAggregator.AGGREGATE_METRICS_FILE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@ExtendWith(MockitoExtension.class)
public class MetricsUploaderTest {
    @TempDir
    protected Path tempRootDir;

    @BeforeEach
    public void setup() {
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());
    }

    @Test
    public void GIVEN_aggregated_metrics_WHEN_publish_THEN_collect_only_the_lates_values() throws InterruptedException {
        //Create a sample file with aggregated metrics so we can test the freshness of the file and logs
        // with respect to the current timestamp
        long lastPublish = Instant.now().toEpochMilli();
        MetricFactory metricFactory = new MetricFactory(AGGREGATE_METRICS_FILE);
        long currentTimestamp = Instant.now().toEpochMilli();
        List<MetricsAggregator.Metric> metricList = new ArrayList<>();
        metricList.add(new MetricsAggregator.Metric(TelemetryMetricName.TotalNumberOfFDs, 4000, TelemetryUnit.Count));
        metricList.add(new MetricsAggregator.Metric(TelemetryMetricName.CpuUsage, 15, TelemetryUnit.Percent));
        metricList.add(new MetricsAggregator.Metric(TelemetryMetricName.SystemMemUsage, 9000, TelemetryUnit.Megabytes));
        MetricsAggregator.AggregatedMetric aggregatedMetric = new MetricsAggregator.AggregatedMetric
                (currentTimestamp, TelemetryNamespace.SystemMetrics, metricList);
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        // Create an instance of the metrics uploader to get the aggregated metrics
        MetricsUploader mu = new MetricsUploader();
        Map<Long, List<MetricsAggregator.AggregatedMetric>> list = mu.getAggregatedMetrics(lastPublish, currentTimestamp);

        //we don't want to collect those metrics that are emitted at the same time of collection
        assertEquals(list.get(currentTimestamp).size(), 0);
        currentTimestamp = Instant.now().toEpochMilli();
        list = mu.getAggregatedMetrics(lastPublish, currentTimestamp);
        lastPublish = currentTimestamp;
        // we only have one list of the metrics collected
        assertEquals(list.size(), 1);

        //we have 3 entries of the aggregated metrics before this latest TS
        assertEquals(list.get(currentTimestamp).size(), 3);

        Thread.sleep(1000);
        currentTimestamp = Instant.now().toEpochMilli();
        aggregatedMetric = new MetricsAggregator.AggregatedMetric
                (currentTimestamp, TelemetryNamespace.SystemMetrics, metricList);
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        currentTimestamp = Instant.now().toEpochMilli();
        list = mu.getAggregatedMetrics(lastPublish, currentTimestamp);

        // we only have one list of the metrics collected
        assertEquals(list.size(), 1);

        // Will not collect the first 3 entries as they are stale
        assertEquals(list.get(currentTimestamp).size(), 2);
    }

    @Test
    public void GIVEN_invalid_aggregated_metrics_WHEN_publish_THEN_parse_them_properly() {
        //Create a sample file with aggregated metrics so we can test the freshness of the file and logs
        // with respect to the current timestamp
        long lastPublish = Instant.now().toEpochMilli();
        MetricFactory metricFactory = new MetricFactory(AGGREGATE_METRICS_FILE);
        long currentTimestamp = Instant.now().toEpochMilli();
        List<MetricsAggregator.Metric> metricList = new ArrayList<>();
        metricList.add(new MetricsAggregator.Metric(TelemetryMetricName.TotalNumberOfFDs, 4000, TelemetryUnit.Count));
        metricList.add(new MetricsAggregator.Metric(TelemetryMetricName.CpuUsage, 15, TelemetryUnit.Percent));
        metricList.add(new MetricsAggregator.Metric(TelemetryMetricName.SystemMemUsage, 9000, TelemetryUnit.Megabytes));
        MetricsAggregator.AggregatedMetric aggregatedMetric = new MetricsAggregator.AggregatedMetric
                (currentTimestamp, TelemetryNamespace.SystemMetrics, metricList);
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));

        metricFactory.logMetrics(new TelemetryLoggerMessage("buffaloWildWings")); // will be ignored
        metricFactory.logMetrics(new TelemetryLoggerMessage(null)); // will be ignored
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        MetricsUploader mu = new MetricsUploader();
        currentTimestamp = Instant.now().toEpochMilli();
        Map<Long, List<MetricsAggregator.AggregatedMetric>> list = mu.getAggregatedMetrics(lastPublish, currentTimestamp);

        // The published metrics will not contain the null aggregated metric
        assertFalse(list.get(currentTimestamp).contains(null));

        // Out of 6 aggregated metrics, only 4 are published
        assertEquals(list.get(currentTimestamp).size(), 4);

    }
}
