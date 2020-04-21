/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.integrationtests.kernel;

import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.integrationtests.BaseITCase;
import com.aws.iot.evergreen.kernel.Kernel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericExternalServiceTest extends BaseITCase {

    private Kernel kernel;

    @AfterEach
    void afterEach() {
        kernel.shutdown();
    }

    @Test
    void GIVEN_service_config_with_broken_skipif_config_WHEN_launch_service_THEN_service_moves_to_error_state()
            throws Throwable {
        // GIVEN
        kernel = new Kernel();
        kernel.parseArgs("-i", getClass().getResource("skipif_broken.yaml").toString());

        CountDownLatch testErrored = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
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
    void GIVEN_service_with_timeout_WHEN_timeout_expires_THEN_move_service_to_errored() throws InterruptedException {
        kernel = new Kernel();
        kernel.parseArgs("-i", getClass().getResource("service_timesout.yaml").toString());
        kernel.launch();
        CountDownLatch ServicesAErroredLatch = new CountDownLatch(1);
        CountDownLatch ServicesBErroredLatch = new CountDownLatch(1);
        // service sleeps for 120 seconds during startup and timeout is 1 second, service should transition to errored
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if ("ServiceA".equals(service.getName()) && State.ERRORED.equals(newState)) {
                ServicesAErroredLatch.countDown();
            }
            if ("ServiceB".equals(service.getName()) && State.ERRORED.equals(newState)) {
                ServicesBErroredLatch.countDown();
            }
        });

        assertTrue(ServicesAErroredLatch.await(5, TimeUnit.SECONDS));
        assertTrue(ServicesBErroredLatch.await(5, TimeUnit.SECONDS));
    }
}
