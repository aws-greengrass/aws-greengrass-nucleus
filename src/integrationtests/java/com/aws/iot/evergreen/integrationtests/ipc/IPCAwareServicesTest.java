/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.integrationtests.ipc;

import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.testcommons.extensions.PerformanceReporting;
import com.aws.iot.evergreen.util.Exec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(PerformanceReporting.class)
class IPCAwareServicesTest {

    private static final String SAMPLE_IPC_AWARE_SERVICE_NAME = "main";

    @TempDir
    static Path tempRootDir;

    private Kernel kernel;

    @BeforeEach
    void setup() {
        // set a POM_DIR env var for Exec so that ipc_aware_main.yaml can use it to locate pom.xml
        Exec.setDefaultEnv("POM_DIR", System.getProperty("user.dir"));

        // start kernel
        kernel = new Kernel();
        kernel.parseArgs("-r", tempRootDir.toString(), "-i",
                getClass().getResource("ipc_aware_main.yaml").toString());
        kernel.launch();
    }

    @AfterEach
    void teardown() {
        kernel.shutdown();
    }

    @Test
    void GIVEN_ipc_aware_service_WHEN_report_state_as_running_THEN_kernel_updates_state_as_running()
            throws Exception {
        CountDownLatch serviceRunning = new CountDownLatch(1);
        EvergreenService.GlobalStateChangeListener listener = (service, state) -> {
            if (SAMPLE_IPC_AWARE_SERVICE_NAME.equals(service.getName()) && State.RUNNING.equals(service.getState())) {
                serviceRunning.countDown();
            }
        };
        kernel.context.addGlobalStateChangeListener(listener);

        // waiting for main to transition to running
        boolean isRunning = serviceRunning.await(60, TimeUnit.SECONDS);
        assertTrue(isRunning);
    }

}
