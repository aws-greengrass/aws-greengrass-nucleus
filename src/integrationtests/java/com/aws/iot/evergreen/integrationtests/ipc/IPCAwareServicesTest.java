/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.integrationtests.ipc;

import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.integrationtests.BaseITCase;
import com.aws.iot.evergreen.kernel.GlobalStateChangeListener;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.util.Exec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class IPCAwareServicesTest extends BaseITCase {

    private static final String SAMPLE_IPC_AWARE_SERVICE_NAME = "main";

    private Kernel kernel;

    @BeforeEach
    void setup() {
        // set a POM_DIR env var for Exec so that ipc_aware_main.yaml can use it to locate pom.xml
        Exec.setDefaultEnv("POM_DIR", System.getProperty("user.dir"));

        // start kernel
        kernel = new Kernel();
        kernel.parseArgs("-i", this.getClass().getResource("ipc_aware_main.yaml").toString());
    }

    @AfterEach
    void teardown() {
        kernel.shutdown();
    }

    @Test
    void GIVEN_ipc_aware_service_WHEN_report_state_as_running_THEN_kernel_updates_state_as_running() throws Exception {
        CountDownLatch serviceRunning = new CountDownLatch(1);
        GlobalStateChangeListener listener = (service, oldState, newState, latest) -> {
            if (SAMPLE_IPC_AWARE_SERVICE_NAME.equals(service.getName()) && State.RUNNING.equals(newState)) {
                serviceRunning.countDown();
            }
        };
        kernel.context.addGlobalStateChangeListener(listener);
        kernel.launch();

        // waiting for main to transition to running
        boolean isRunning = serviceRunning.await(60, TimeUnit.SECONDS);
        assertTrue(isRunning);
    }
}
