package com.aws.iot.evergreen.integrationtests.kernel;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.WhatHappened;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.GenericExternalService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.testcommons.extensions.PerformanceReporting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(PerformanceReporting.class)
class KernelShutdownTest {

    private static Kernel kernel;

    @TempDir
    Path tempRootDir;

    @BeforeEach
    void setup() {
        kernel = new Kernel();
        kernel.parseArgs("-r", tempRootDir.toString(), "-log", "stdout", "-i", getClass().getResource(
                "long_running_services.yaml").toString());
        kernel.launch();
    }

    @Test
    void WHEN_kernel_shutdown_THEN_services_are_shutdown_in_reverse_dependecy_order() throws InterruptedException {
        CountDownLatch mainClosed = new CountDownLatch(1);
        CountDownLatch sleeperAClosed = new CountDownLatch(1);
        CountDownLatch sleeperBClosed = new CountDownLatch(1);

        kernel.context.addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.isClosable()) {
                mainClosed.countDown();
            }
            // Only count main as started if its dependency (new_service) has already been started
            if (mainClosed.getCount() == 0) {
                if (service.getName().equals("sleeperA") && newState.isClosable()) {
                    sleeperAClosed.countDown();
                }
            }
            if (sleeperAClosed.getCount() == 0) {
                if (service.getName().equals("sleeperB") && newState.isClosable()) {
                    sleeperBClosed.countDown();
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
        assertTrue(sleeperBClosed.getCount()==0);
    }
}
