package com.aws.iot.evergreen.telemetry;

import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.telemetry.config.TelemetryDataConfig;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class MetricsAggregatorTest {
    @Mock
    Context mockContext;
    @TempDir
    protected Path tempRootDir;
    private ScheduledThreadPoolExecutor ses;
    MetricsAggregator metricsAggregator;
    SystemMetricsEmitter systemMetricsEmitter;
    MockedStatic<MetricsAgent> mockMetricsAgent;
    Map<String, TelemetryDataConfig> configMap = new HashMap<>();
    TelemetryDataConfig systemMetricsConfig = new TelemetryDataConfig("SystemMetrics",2_000,5_000,60_000,"Average");

    @BeforeEach
    public void setup() {
        ses = new ScheduledThreadPoolExecutor(3);
        mockMetricsAgent = Mockito.mockStatic(MetricsAgent.class);
        configMap.put(systemMetricsConfig.getMetricNamespace(),systemMetricsConfig);

        when(mockContext.get(ScheduledExecutorService.class)).thenReturn(ses);
        mockMetricsAgent.when(MetricsAgent::createSampleConfiguration).thenReturn(configMap);

        systemMetricsEmitter = new SystemMetricsEmitter();
        metricsAggregator = new MetricsAggregator();
    }

    @AfterEach
    public void cleanup() {
        ses.shutdownNow();
    }

    @Test
    public void GIVEN_kernel_WHEN_metrics_agent_is_running_THEN_aggregate_metrics() throws InterruptedException {
        systemMetricsEmitter.collectSystemMetrics(mockContext);
        metricsAggregator.aggregateMetrics(mockContext);
        ses.awaitTermination(6000, TimeUnit.MILLISECONDS);
        // add assertions
    }
}


