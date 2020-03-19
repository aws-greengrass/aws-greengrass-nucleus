package com.aws.iot.evergreen.integrationtests.kernel;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.WhatHappened;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.integrationtests.AbstractBaseITCase;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.testcommons.extensions.PerformanceReporting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(PerformanceReporting.class)
class KernelShutdownTest extends AbstractBaseITCase {

    private Kernel kernel;

    @BeforeEach
    void beforeEach() {
        kernel = new Kernel();
        kernel.parseArgs("-i", getClass().getResource("long_running_services.yaml").toString());
        kernel.launch();
    }

    @Test
    void WHEN_kernel_shutdown_THEN_services_are_shutdown_in_reverse_dependecy_order() throws InterruptedException {
        AtomicBoolean mainClosed = new AtomicBoolean(false);
        AtomicBoolean sleeperAClosed = new AtomicBoolean(false);
        AtomicBoolean sleeperBClosed = new AtomicBoolean(false);

        kernel.context.addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.isClosable()) {
                mainClosed.set(true);
            }
            // Only count main as started if its dependency (new_service) has already been started
            if (mainClosed.get()) {
                if (service.getName().equals("sleeperA") && newState.isClosable()) {
                    sleeperAClosed.set(true);
                }
            }
            if (sleeperAClosed.get()) {
                if (service.getName().equals("sleeperB") && newState.isClosable()) {
                    sleeperBClosed.set(true);
                }
            }
        });

        CountDownLatch mainRunningLatch = new CountDownLatch(1);
        kernel.getMain().getStateTopic().subscribe((WhatHappened what, Topic t) -> {
            if (((State) t.getOnce()).isRunning()) {
                mainRunningLatch.countDown();
            }
        });

        //wait for main to run
        assertTrue(mainRunningLatch.await(60, TimeUnit.SECONDS));
        kernel.shutdown(60);
        assertTrue(sleeperBClosed.get());
    }
}
