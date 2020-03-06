/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.integrationtests.ipc;

import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.testcommons.extensions.PerformanceReporting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(PerformanceReporting.class)
public class IPCAwareServicesTest {

    public static Kernel kernel;

    @BeforeEach
    public void setup() {
        kernel = new Kernel();
        String tdir = System.getProperty("user.dir");
        kernel.parseArgs("-r", tdir, "-log", "stdout", "-i",
                IPCAwareServicesTest.class.getResource("ipc_aware_main.yaml").toString());
        kernel.launch();
    }

    @AfterEach
    public void teardown() {
        kernel.shutdown();
    }

    @Test
    public void GIVEN_ipc_aware_service_WHEN_report_state_as_running_THEN_kernel_updates_state_as_running()
            throws Exception {
        CountDownLatch serviceRunning = new CountDownLatch(1);
        EvergreenService.GlobalStateChangeListener listener = (service, state) -> {
            if ("main".equals(service.getName()) && State.RUNNING.equals(service.getState())) {
                serviceRunning.countDown();
            }
        };
        kernel.context.addGlobalStateChangeListener(listener);
        //waiting for main to transition to running
        boolean isRunning = serviceRunning.await(30, TimeUnit.SECONDS);
        assertTrue(isRunning);
    }

}
