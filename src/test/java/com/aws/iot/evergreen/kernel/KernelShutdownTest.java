package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.WhatHappened;
import com.aws.iot.evergreen.dependency.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
        Queue<EvergreenService> servicesInFinishedOrder = new LinkedList<>();
        EvergreenService.GlobalStateChangeListener listener = (service, state) -> {
            if (State.FINISHED.equals(service.getState()) && (service instanceof GenericExternalService)) {
                servicesInFinishedOrder.offer(service);
            }
        };
        kernel.context.addGlobalStateChangeListener(listener);
        kernel.shutdown(60);
        // service should moved to finished based on dependency order
        assertEquals("main", servicesInFinishedOrder.remove().getName());
        assertEquals("sleeperA", servicesInFinishedOrder.remove().getName());
        assertEquals("sleeperB", servicesInFinishedOrder.remove().getName());
    }
}
