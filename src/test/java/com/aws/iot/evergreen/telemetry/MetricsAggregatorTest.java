package com.aws.iot.evergreen.telemetry;

import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.telemetry.aggregation.AggregatedMetric;
import com.aws.iot.evergreen.telemetry.config.TelemetryDataConfig;
import com.aws.iot.evergreen.telemetry.impl.MetricDataPoint;
import com.aws.iot.evergreen.telemetry.models.TelemetryAggregation;
import com.aws.iot.evergreen.telemetry.models.TelemetryMetricName;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.joda.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.aws.iot.evergreen.telemetry.MetricsAgent.createSampleConfiguration;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class MetricsAggregatorTest {
    @Mock
    private Kernel mockKernel;
    @TempDir
    protected Path tempRootDir;

    @BeforeEach
    public void setup() {
        mockKernel.launch();
        System.setProperty("root",tempRootDir.toAbsolutePath().toString());
    }

    @Test
    public void GIVEN_telemetry_data_config_WHEN_metricsAgent_is_running_THEN_parse_the_config_file(){
        Map<String, TelemetryDataConfig> telemetryDataConfig = createSampleConfiguration();
        AggregatedMetric am = new AggregatedMetric();
        List<String> logs;
        MetricDataPoint mdp;
        HashMap<String, List<MetricDataPoint>> metrics = new HashMap<>();
        for (Map.Entry<String, TelemetryDataConfig> config: telemetryDataConfig.entrySet()){
            String metricNamespace = config.getKey();
            TelemetryDataConfig metricConfig = config.getValue();
            String sampleLogFile = "src/test/resources/com/aws/iot/evergreen/Telemetry/sample_logs/" +
                    metricNamespace +".log";
            try {
                logs = Files.lines(Paths.get(sampleLogFile)).collect(Collectors.toList());
                for(String log:logs){
                    mdp = new ObjectMapper().readValue(log.split(" ")[7], MetricDataPoint.class);
                    am.setMetricNamespace(mdp.getMetric().getMetricNamespace());
                    am.setTimestamp(Instant.now().getMillis());
                    metrics.computeIfAbsent(mdp.getMetric().getMetricName().toString(),
                            k -> new ArrayList<>()).add(mdp);
                }
            } catch (IOException e) {
                System.out.println(e);
            }
            am.setMetric(doAggregation(metrics, TelemetryAggregation.valueOf(metricConfig.getAggregationType())));

        }
        try {
            System.out.println(new ObjectMapper().writeValueAsString(am));
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
    }

    private List<AggregatedMetric.Metric> doAggregation(HashMap<String, List<MetricDataPoint>> metrics,
                                                    TelemetryAggregation telemetryAggregation){
        List<AggregatedMetric.Metric> aMet = new ArrayList<>();
        Object aggregation = 0;
        for(Map.Entry<String, List<MetricDataPoint>> metric : metrics.entrySet()) {
            List<MetricDataPoint> mdp = metric.getValue();
            if (telemetryAggregation.equals(TelemetryAggregation.Average)) {
                aggregation = mdp
                        .stream()
                        .mapToDouble(a -> Double.parseDouble(a.getValue().toString()))
                        .sum();
                if(mdp.size()>0) {
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
            AggregatedMetric.Metric ma = AggregatedMetric.Metric.builder()
                        .metricName(TelemetryMetricName.valueOf(metric.getKey()))
                        .metricUnit(mdp.get(0).getMetric().getMetricUnit())
                        .value(aggregation)
                        .build();
            aMet.add(ma);
        }
        return aMet;
    }
}


