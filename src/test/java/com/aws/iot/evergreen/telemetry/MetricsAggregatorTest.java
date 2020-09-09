package com.aws.iot.evergreen.telemetry;

import com.aws.iot.evergreen.telemetry.api.MetricDataBuilder;
import com.aws.iot.evergreen.telemetry.impl.Metric;
import com.aws.iot.evergreen.telemetry.impl.MetricFactory;
import com.aws.iot.evergreen.telemetry.impl.config.TelemetryConfig;
import com.aws.iot.evergreen.telemetry.models.TelemetryAggregation;
import com.aws.iot.evergreen.telemetry.models.TelemetryMetricName;
import com.aws.iot.evergreen.telemetry.models.TelemetryNamespace;
import com.aws.iot.evergreen.telemetry.models.TelemetryUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
public class MetricsAggregatorTest {
    @TempDir
    protected Path tempRootDir;
    private ObjectMapper mapper;

    @BeforeEach
    public void setup() {
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());
        mapper = new ObjectMapper();
    }

    @Test
    public void GIVEN_system_metrics_WHEN_aggregate_THEN_aggregate_only_the_latest_values()
            throws InterruptedException, IOException {
        //Create a sample file with system metrics so we can test the freshness of the file and logs
        //with respect to the current timestamp
        Metric m = new Metric(TelemetryNamespace.SystemMetrics, TelemetryMetricName.CpuUsage,
                TelemetryUnit.Percent, TelemetryAggregation.Sum);
        MetricDataBuilder mdb1 = new MetricFactory(TelemetryNamespace.SystemMetrics.toString()).addMetric(m);
        m = new Metric(TelemetryNamespace.SystemMetrics, TelemetryMetricName.SystemMemUsage,
                TelemetryUnit.Megabytes, TelemetryAggregation.Average);
        MetricDataBuilder mdb2 = new MetricFactory(TelemetryNamespace.SystemMetrics.toString()).addMetric(m);
        m = new Metric(TelemetryNamespace.SystemMetrics, TelemetryMetricName.TotalNumberOfFDs,
                TelemetryUnit.Count, TelemetryAggregation.Maximum);
        MetricDataBuilder mdb3 = new MetricFactory(TelemetryNamespace.SystemMetrics.toString()).addMetric(m);
        mdb1.putMetricData(10).emit();
        mdb2.putMetricData(2000).emit();
        mdb3.putMetricData(4000).emit();
        mdb1.putMetricData(20).emit();
        mdb2.putMetricData(3000).emit();
        mdb3.putMetricData(5000).emit();
        mdb1.putMetricData(30).emit();
        mdb2.putMetricData(4000).emit();
        mdb3.putMetricData(6000).emit();
        MetricsAggregator ma = new MetricsAggregator();
        // Aggregate values within 1 second interval at this timestamp with 1
        ma.aggregateMetrics(1, Instant.now().toEpochMilli());
        String path = TelemetryConfig.getTelemetryDirectory().toString() + "/AggregateMetrics.log";
        List<String> list = Files.lines(Paths.get(path)).collect(Collectors.toList());
        assertEquals(list.size(), TelemetryNamespace.values().length); // Metrics are aggregated based on the namespace.
        for (String s : list) {
            System.out.println(s);
            MetricsAggregator.AggregatedMetric am = mapper.readValue(mapper.readTree(s).get("message").asText(),
                    MetricsAggregator.AggregatedMetric.class);
            if (am.getMetricNamespace().equals(TelemetryNamespace.SystemMetrics)) {
                assertEquals(am.getMetrics().size(), 3); // Three system metrics
                for (MetricsAggregator.Metric metrics : am.getMetrics()) {
                    if (metrics.getMetricName().equals(TelemetryMetricName.CpuUsage)) {
                        assertEquals(metrics.getValue(), (double) 60);
                    } else if (metrics.getMetricName().equals(TelemetryMetricName.SystemMemUsage)) {
                        assertEquals(metrics.getValue(), (double) 3000);
                    } else if (metrics.getMetricName().equals(TelemetryMetricName.TotalNumberOfFDs)) {
                        assertEquals((double) metrics.getValue(), (double) 6000);
                    }
                }
            }
        }
        Thread.sleep(1000);
        long currentTimestamp = Instant.now().toEpochMilli();
        // Aggregate values within 1 second interval at this timestamp with 1
        ma.aggregateMetrics(1, currentTimestamp);
        list = Files.lines(Paths.get(path)).collect(Collectors.toList());
        assertEquals(list.size(), 8); // AggregateMetrics.log is appended with the latest aggregations.
        for (String s : list) {
            MetricsAggregator.AggregatedMetric am = mapper.readValue(mapper.readTree(s).get("message").asText(),
                    MetricsAggregator.AggregatedMetric.class);
            if (am.getTimestamp() == currentTimestamp && am.getMetricNamespace().equals(TelemetryNamespace.SystemMetrics)) {
                assertEquals(am.getMetrics().size(), 0); // There is no aggregation as there are no latest values
            }
        }
    }

    @Test
    public void GIVEN_invalid_metrics_WHEN_aggregate_THEN_parse_them_properly() throws IOException {
        //Create a sample file with aggregated metrics so we can test the freshness of the file and logs
        // with respect to the current timestamp

        Metric m = new Metric(TelemetryNamespace.SystemMetrics, TelemetryMetricName.CpuUsage,
                TelemetryUnit.Percent, TelemetryAggregation.Sum);
        MetricDataBuilder mdb1 = new MetricFactory(TelemetryNamespace.SystemMetrics.toString()).addMetric(m);
        m = new Metric(TelemetryNamespace.SystemMetrics, TelemetryMetricName.SystemMemUsage,
                TelemetryUnit.Megabytes, TelemetryAggregation.Average);
        MetricDataBuilder mdb2 = new MetricFactory(TelemetryNamespace.SystemMetrics.toString()).addMetric(m);

        // Add null metric
        new MetricFactory(TelemetryNamespace.SystemMetrics.toString()).addMetric(null);

        // Put null data
        mdb1.putMetricData(null).emit();

        // Put invalid data for average aggregation
        mdb2.putMetricData("banana").emit();
        mdb2.putMetricData(2000).emit();

        MetricsAggregator ma = new MetricsAggregator();
        // Aggregate values within 1 second interval at this timestamp with 1
        ma.aggregateMetrics(1, Instant.now().toEpochMilli());
        String path = TelemetryConfig.getTelemetryDirectory().toString() + "/AggregateMetrics.log";
        List<String> list = Files.lines(Paths.get(path)).collect(Collectors.toList());
        assertEquals(list.size(), TelemetryNamespace.values().length); // Metrics are aggregated based on the namespace.
        for (String s : list) {
            MetricsAggregator.AggregatedMetric am = mapper.readValue(mapper.readTree(s).get("message").asText(),
                    MetricsAggregator.AggregatedMetric.class);
            if (am.getMetricNamespace().equals(TelemetryNamespace.SystemMetrics)) {
                assertEquals(am.getMetrics().size(), 2); // Two system metrics, one of them is null
                for (MetricsAggregator.Metric metrics : am.getMetrics()) {
                    if (metrics.getMetricName().equals(TelemetryMetricName.CpuUsage)) {
                        assertEquals(metrics.getValue(), (double) 0); //No valid data point to aggregate
                    } else if (metrics.getMetricName().equals(TelemetryMetricName.SystemMemUsage)) {
                        assertEquals(metrics.getValue(), (double) 1000); // ignore the invalid value
                    }
                }
            }
        }
    }

}