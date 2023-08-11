/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.lifecyclemanager;

import com.aws.greengrass.dependency.State;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.GenericExternalService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KernelShutdownTest extends BaseITCase {

    private Kernel kernel;
    private CountDownLatch mainInstalledLatch;
    @BeforeEach
    void beforeEach() {
        kernel = new Kernel();
        mainInstalledLatch = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (kernel.getMain().equals(service) && newState.equals(State.INSTALLED)) {
                mainInstalledLatch.countDown();
            }
        });
    }

    @AfterEach
    void afterEach() {
        kernel.shutdown();
    }

    @Test
    void WHEN_kernel_shutdown_THEN_services_are_shutdown_in_reverse_dependency_order()
            throws InterruptedException, IOException {
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                getClass().getResource("long_running_services.yaml"));
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

        // wait for main to install
        kernel.launch();
        assertTrue(mainInstalledLatch.await(60, TimeUnit.SECONDS));

        kernel.shutdown(60);
        assertTrue(sleeperBClosed.get());
    }

    @Test
    void WHEN_service_error_AND_kernel_shutdown_THEN_services_are_not_restarted()
            throws IOException, ServiceLoadException, InterruptedException {
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                getClass().getResource("services_startup_with_hard_dep.yaml"));
        AtomicBoolean mainClosed = new AtomicBoolean(false);
        AtomicInteger componentWithDependerStoppingCount = new AtomicInteger(0);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if ("component_with_depender".equals(service.getName()) && newState.equals(State.STOPPING)) {
                componentWithDependerStoppingCount.incrementAndGet();
            }
            if ("main".equals(service.getName()) && newState.isClosable()) {
                mainClosed.set(true);
            }
        });

        kernel.launch();
        assertTrue(mainInstalledLatch.await(60, TimeUnit.SECONDS));

        // shutdown lifecycle process to simulate service error
        ((GenericExternalService) kernel.locate("component_with_depender")).stopAllLifecycleProcesses();
        kernel.shutdown(60);

        assertTrue(mainClosed.get());
        // verify that service only stops once, which means that it did not restart during shutdown
        assertEquals(1, componentWithDependerStoppingCount.get());
    }
}
