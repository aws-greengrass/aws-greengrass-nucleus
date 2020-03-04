package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.WhatHappened;
import com.aws.iot.evergreen.dependency.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class KernelShutdownTest {

    private static Kernel kernel;

    @BeforeEach
    public void setup() {
        kernel = new Kernel();
        String tdir = System.getProperty("user.dir");
        kernel.parseArgs("-r", tdir, "-log", "stdout", "-i", KernelShutdownTest.class.getResource("long_running_services.yaml").toString());
        kernel.launch();
    }

    @Test
    public void WHEN_kernel_shutdown_THEN_services_are_shutdown_in_reverse_dependecy_order() throws InterruptedException {

        CountDownLatch mainRunningLatch = new CountDownLatch(1);
        kernel.getMain().getStateTopic().subscribe((WhatHappened what, Topic t) -> {
            if (((State) t.getOnce()).isRunning()) {
                mainRunningLatch.countDown();
            }
        });
        //wait for main to run
        assertTrue(mainRunningLatch.await(60, TimeUnit.SECONDS));
        kernel.shutdown(60);

        List<EvergreenService> genericExternalServices = kernel.orderedDependencies().stream()
                .filter(e -> e instanceof GenericExternalService).collect(Collectors.toList());

        assertTrue(genericExternalServices.stream().allMatch(e -> e.getState().isClosable()));

        //sorting genericExternalServices in descending order based on when they reached terminal state
        Collections.sort(genericExternalServices, (s1, s2) ->
                Long.compare(s1.getStateTopic().getModtime(), s1.getStateTopic().getModtime()));

        // service should moved to terminal state based on dependency order
        assertEquals("main", genericExternalServices.get(2).getName());
        assertEquals("sleeperA", genericExternalServices.get(1).getName());
        assertEquals("sleeperB", genericExternalServices.get(0).getName());
    }
}
