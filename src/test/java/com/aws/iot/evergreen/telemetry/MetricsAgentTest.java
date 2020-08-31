/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *   SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.telemetry;

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

import java.nio.file.Path;
import java.util.concurrent.ScheduledThreadPoolExecutor;

@SuppressWarnings({"PMD.LooseCoupling", "PMD.TestClassWithoutTestCases"})
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

        //when(context.get(ScheduledExecutorService.class)).thenReturn(ses);
        //when(metricsAgent.getState()).thenReturn(State.STARTING);
        System.setProperty("root",tempRootDir.toAbsolutePath().toString());

    }

    @AfterEach
    public void cleanup() {
        ses.shutdownNow();
        metricsAgent.shutdown();
    }

    @Test
    public void GIVEN_kernel_WHEN_MetricsAgent_starts_THEN_read_telemetry_config_file() {
        //mock reading from a config file
    }


}