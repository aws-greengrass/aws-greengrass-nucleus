/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.integrationtests.kernel;

import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.integrationtests.BaseITCase;
import com.aws.iot.evergreen.kernel.Kernel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericExternalServiceTest extends BaseITCase {


    @Test
    void GIVEN_service_config_with_broken_skipif_config_WHEN_launch_service_THEN_service_moves_to_error_state()
            throws Throwable {
        // GIVEN
        Kernel kernel = new Kernel();
        kernel.parseArgs("-i", getClass().getResource("skipif_broken.yaml").toString());

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

    @Test
    void GIVEN_service_with_startup_timeout_WHEN_do_not_startup_within_timeout_THEN_move_service_to_errored()
            throws InterruptedException {
        Kernel kernel = new Kernel();
        kernel.parseArgs("-i", getClass().getResource("service_timesout.yaml").toString());
        kernel.launch();
        CountDownLatch ServiceAErroredLatch = new CountDownLatch(1);
        // service sleeps for 120 seconds during startup and timeout is 5 seconds, service should transition to errored
        kernel.context.addGlobalStateChangeListener((service, oldState, newState) -> {
            if("ServiceA".equals(service.getName()) && State.ERRORED.equals(newState)){
                ServiceAErroredLatch.countDown();
            }
        });

        assertTrue(ServiceAErroredLatch.await(30, TimeUnit.SECONDS));
        kernel.shutdown();
    }
}
