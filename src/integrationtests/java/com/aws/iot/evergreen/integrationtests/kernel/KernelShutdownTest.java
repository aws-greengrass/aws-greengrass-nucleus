package com.aws.iot.evergreen.integrationtests.kernel;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.WhatHappened;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.integrationtests.BaseITCase;
import com.aws.iot.evergreen.kernel.Kernel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

class KernelShutdownTest extends BaseITCase {

    private Kernel kernel;

    @BeforeEach
    void beforeEach() {
        kernel = new Kernel();
        kernel.parseArgs("-i", getClass().getResource("long_running_services.yaml").toString());
        kernel.launch();
    }

    @AfterEach
    void afterEach() {
        kernel.shutdown();
    }

    @Test
    void WHEN_kernel_shutdown_THEN_services_are_shutdown_in_reverse_dependency_order() throws InterruptedException {
        AtomicBoolean mainClosed = new AtomicBoolean(false);
        AtomicBoolean sleeperAClosed = new AtomicBoolean(false);
        AtomicBoolean sleeperBClosed = new AtomicBoolean(false);

        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if ("main".equals(service.getName()) && newState.isClosable()) {
                mainClosed.set(true);
            }
            // Only count main as started if its dependency (new_service) has already been started
            if (mainClosed.get() && "sleeperA".equals(service.getName()) && newState.isClosable()) {
                sleeperAClosed.set(true);
            }
            if (sleeperAClosed.get() && "sleeperB".equals(service.getName()) && newState.isClosable()) {
                sleeperBClosed.set(true);
            }
        });

        CountDownLatch mainRunningLatch = new CountDownLatch(1);
        kernel.getMain().addStateSubscriber((WhatHappened what, Topic t) -> {
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
