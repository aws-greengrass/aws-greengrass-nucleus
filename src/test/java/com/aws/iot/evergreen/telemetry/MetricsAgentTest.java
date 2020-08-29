/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *   SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.telemetry;

import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.testcommons.testutilities.EGServiceTestUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;

import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class MetricsAgentTest extends EGServiceTestUtil {
    @Mock
    private Kernel mockKernel;
    private MetricsAgent metricsAgent;
    @TempDir
    protected Path tempRootDir;
    private ScheduledThreadPoolExecutor ses;

    @BeforeEach
    public void setup() {
        serviceFullName = "MetricsAgent";
        initializeMockedConfig();
        ses =new ScheduledThreadPoolExecutor(3);
        mockKernel.launch();
        // Create the service that you want to test
        metricsAgent = new MetricsAgent(config);
        metricsAgent.postInject();

        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);
        when(metricsAgent.getState()).thenReturn(State.STARTING);
        System.setProperty("root",tempRootDir.toAbsolutePath().toString());

    }

    @AfterEach
    public void cleanup() {
        ses.shutdownNow();
        metricsAgent.shutdown();
    }

    @Test
    public void GIVEN_kernel_WHEN_MetricsAgent_starts_THEN_write_SystemMetrics_to_the_file_specified() {
        String testLogFile = "Lasagna";
        try {
            Field field = SystemMetricsEmitter.class.getDeclaredField("SYSTEM_METRICS_STORE");
            field.setAccessible(true);
            try{
                field.set(null,testLogFile);
            }
            catch(IllegalAccessException e) {
                fail("Error setting the metric store name.\n" + e.getMessage());
            }

        } catch(NoSuchFieldException e) {
            fail("Error setting the metric store name.\n" + e.getMessage());
        }

        assertEquals(metricsAgent.getState(),State.STARTING);

        metricsAgent.startup();
        when(metricsAgent.getState()).thenReturn(State.RUNNING);
        assertEquals(metricsAgent.getState(),State.RUNNING);

        File logFile = new File(tempRootDir + "/Telemetry/" + testLogFile + ".log");
        assertTrue(logFile.exists());
    }

    @Test
    public void GIVEN_kernel_WHEN_MetricsAgent_starts_THEN_write_SystemMetrics_in_specified_intervals() {
        int testInterval = 2;
        try {
            Field field = SystemMetricsEmitter.class.getDeclaredField("SYSTEM_METRICS_PERIOD");
            field.setAccessible(true);
            try{
                field.set(null,testInterval);
            }
            catch(IllegalAccessException e) {
                fail("Error setting the metric store name.\n" + e.getMessage());
            }
        } catch(NoSuchFieldException e) {
            fail("Error setting the metric store name.\n" + e.getMessage());
        }
        metricsAgent.startup();
        try {
            ses.awaitTermination(3,TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            fail("Error in termination await." +  e.getMessage());
        }
        // Metrics store name is set to "SystemMetrics" by default.
        File logFile = new File(tempRootDir + "/Telemetry/SystemMetrics.log");
        assertTrue(logFile.exists());
        // Count the number of lines in the log file.
        // 3 system metrics are emitted twice (0th and 2nd seconds) in 3 seconds without any delay => 6
        try {
            assertEquals(Files.lines(logFile.toPath()).count(),6);
        } catch (IOException e) {
            fail("An error occured when reading a file" + e.getMessage());
        }
    }
}