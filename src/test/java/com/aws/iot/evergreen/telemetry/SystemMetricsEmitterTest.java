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

@SuppressWarnings({"PMD.LooseCoupling", "PMD.TestClassWithoutTestCases"})
@ExtendWith({MockitoExtension.class, EGExtension.class})
public class SystemMetricsEmitterTest {
    @Mock
    Context mockContext;
    @TempDir
    protected Path tempRootDir;
    private ScheduledThreadPoolExecutor ses;
    SystemMetricsEmitter systemMetricsEmitter;

    @BeforeEach
    public void setup() {
        ses = new ScheduledThreadPoolExecutor(3);
        when(mockContext.get(ScheduledExecutorService.class)).thenReturn(ses);
        systemMetricsEmitter = new SystemMetricsEmitter();
    }

    @AfterEach
    public void cleanup() {
        ses.shutdownNow();
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
        systemMetricsEmitter.collectSystemMetrics(mockContext);
        File logFile = new File(System.getProperty("user.dir") + "/Telemetry/" + testLogFile + ".log");
        assertTrue(logFile.exists());
    }

    @Test
    public void GIVEN_kernel_WHEN_MetricsAgent_starts_THEN_write_SystemMetrics_in_specified_intervals() {
        int emitInterval = 2000;
        // Metrics store name is set to "SystemMetrics" by default. Delete the file if it already exists.
        File logFile = new File(System.getProperty("user.dir") + "/Telemetry/SystemMetrics.log");
        logFile.delete();
        try {
            Field field = SystemMetricsEmitter.class.getDeclaredField("SYSTEM_METRICS_PERIOD");
            field.setAccessible(true);
            try{
                field.set(null,emitInterval);
            }
            catch (IllegalAccessException e) {
                fail("Error setting the metric store name.\n" + e.getMessage());
            }
        } catch (NoSuchFieldException e) {
            fail("Error setting the metric store name.\n" + e.getMessage());
        }
        systemMetricsEmitter.collectSystemMetrics(mockContext);
        try {
            ses.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            fail("Error in termination await." +  e.getMessage());
        }

        assertTrue(logFile.exists());
        // Count the number of lines in the log file.
        // 3 system metrics are emitted twice (0th and 2nd seconds) in 3 seconds without any delay => 6
        try {
            assertEquals(Files.lines(logFile.toPath()).count(),6);
        } catch (IOException e) {
            fail("An error occurred when reading a file" + e.getMessage());
        }
    }
}
