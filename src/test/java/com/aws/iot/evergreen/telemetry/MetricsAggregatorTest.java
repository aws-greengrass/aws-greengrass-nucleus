package com.aws.iot.evergreen.telemetry;

import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.concurrent.ScheduledThreadPoolExecutor;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class MetricsAggregatorTest {
    @Mock
    Context mockContext;
    @TempDir
    protected Path tempRootDir;
    private ScheduledThreadPoolExecutor ses;
    MetricsAggregator metricsAggregator;
    SystemMetricsEmitter systemMetricsEmitter;

    @BeforeEach
    public void setup() {
        ses = new ScheduledThreadPoolExecutor(3);
        //when(mockContext.get(ScheduledExecutorService.class)).thenReturn(ses);
        metricsAggregator = new MetricsAggregator();
        systemMetricsEmitter = new SystemMetricsEmitter();
    }

    @AfterEach
    public void cleanup() {
        ses.shutdownNow();
    }

    @Test
    public void GIVEN_kernel_WHEN_metrics_agent_is_running_THEN_aggregate_metrics() throws InterruptedException {
        //mock aggregation tests
    }
}


