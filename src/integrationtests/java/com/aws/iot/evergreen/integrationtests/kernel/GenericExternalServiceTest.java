/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.integrationtests.kernel;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.kernel.Kernel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericExternalServiceTest {

    @TempDir
    Path tempRootDir;


    @BeforeEach
    void before(TestInfo testInfo) {
        System.out.println("Running test: " + testInfo.getDisplayName());
        System.setProperty("log.store", "CONSOLE");
        System.setProperty("log.fmt", "TEXT");
    }

    @Test
    void GIVEN_service_config_with_broken_skipif_config_WHEN_launch_service_THEN_service_moves_to_error_state()
            throws Throwable {
        // GIVEN
        Kernel kernel = new Kernel();
        kernel.parseArgs("-r", tempRootDir.toString(), "-i",
                getClass().getResource("skipif_broken.yaml").toString());

        CountDownLatch testErrored = new CountDownLatch(1);
        kernel.context.addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("test") && newState.equals(State.ERRORED)) {
                testErrored.countDown();
            }
        });

        // WHEN
        kernel.launch();

        // THEN
        assertTrue(testErrored.await(60, TimeUnit.SECONDS));
    }
}
