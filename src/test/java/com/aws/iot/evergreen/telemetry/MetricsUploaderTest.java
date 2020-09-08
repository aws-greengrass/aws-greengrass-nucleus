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
        Map<Long, List<MetricsAggregator.AggregatedMetric>> list = mu.getAggregatedMetrics(2, currentTimestamp);
        assertEquals(list.size(), 1); // we only have one list of the metrics collected
        assertEquals(list.get(currentTimestamp).size(), 3); //we have 3 entries of the aggregated metrics

        Thread.sleep(1000);
        currentTimestamp = Instant.now().toEpochMilli();
        aggregatedMetric = new MetricsAggregator.AggregatedMetric
                (currentTimestamp, TelemetryNamespace.SystemMetrics, metricList);
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        metricFactory.logMetrics(new TelemetryLoggerMessage(aggregatedMetric));
        list = mu.getAggregatedMetrics(1, currentTimestamp);
        assertEquals(list.size(), 1);
        assertEquals(list.get(currentTimestamp).size(), 2); // does not calculate the first 3 entries as they are stale
    }

    @Test
    public void GIVEN_invalid_aggregated_metrics_WHEN_publish_THEN_parse_them_properly() {
        //Create a sample file with aggregated metrics so we can test the freshness of the file and logs
        // with respect to the current timestamp

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
        Map<Long, List<MetricsAggregator.AggregatedMetric>> list = mu.getAggregatedMetrics(2, currentTimestamp);
        assertEquals(list.size(), 1); // current timestamp entry
        assertEquals(list.get(currentTimestamp).size(), 4);
        assertFalse(list.get(currentTimestamp).contains(null)); // for the invalid entry
    }
}
