/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.mqttclient.spool;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.ImplementsService;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.PluginService;
import com.aws.greengrass.mqttclient.spool.CloudMessageSpool;
import com.aws.greengrass.mqttclient.spool.SpoolMessage;
import com.aws.greengrass.mqttclient.v5.Publish;
import com.aws.greengrass.mqttclient.v5.QOS;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test verifying the fix for GitHub issue #1832:
 * DiskSpooler's per-message load now runs on a background thread, so user component startup
 * is no longer blocked by it.
 *
 * <p>The test uses a {@link SlowMockDiskSpoolerService} that simulates a large number of persisted
 * messages with a per-message delay (simulating SQLite I/O on constrained hardware). It asserts
 * that a user component with no dependency on the spooler reaches RUNNING immediately, while the
 * background load is still in progress.</p>
 *
 * @see <a href="https://github.com/aws-greengrass/aws-greengrass-nucleus/issues/1832">Issue #1832</a>
 */
class DiskSpoolerStartupBlockingTest extends BaseITCase {

    private static final int NUM_SPOOLED_MESSAGES = 1000;
    private static final long PER_MESSAGE_DELAY_MS = 50; // 50ms per message = ~50s total sync time
    private static final int COMPONENT_STARTUP_TIMEOUT_SECONDS = 15;
    private static final String USER_COMPONENT_NAME = "UserComponent";

    private Kernel kernel;
    private String originalScanProperty;

    @BeforeEach
    void beforeEach() {
        kernel = new Kernel();
        originalScanProperty = System.getProperty("aws.greengrass.scanSelfClasspath");
        System.setProperty("aws.greengrass.scanSelfClasspath", "true");
        SlowMockDiskSpoolerService.reset();
    }

    @AfterEach
    void afterEach() {
        if (kernel != null) {
            kernel.shutdown();
        }
        if (originalScanProperty == null) {
            System.clearProperty("aws.greengrass.scanSelfClasspath");
        } else {
            System.setProperty("aws.greengrass.scanSelfClasspath", originalScanProperty);
        }
    }

    /**
     * Verifies the fix for issue #1832: user components start immediately even when the DiskSpooler
     * has a large backlog of persisted messages to load.
     *
     * <p>The per-message load runs on a background thread. The kernel's component lifecycle
     * evaluation proceeds without waiting for it. The user component should reach RUNNING
     * within seconds, while the background load is still in progress.</p>
     */
    @Test
    void GIVEN_large_persisted_spooler_queue_WHEN_kernel_starts_THEN_user_component_starts_immediately()
            throws Exception {
        SlowMockDiskSpoolerService.messageCount = NUM_SPOOLED_MESSAGES;
        SlowMockDiskSpoolerService.perMessageDelayMs = PER_MESSAGE_DELAY_MS;

        CountDownLatch componentRunningLatch = new CountDownLatch(1);

        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                this.getClass().getResource("disk_spooler_startup_blocking.yaml"));

        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals(USER_COMPONENT_NAME) && newState.equals(State.RUNNING)) {
                componentRunningLatch.countDown();
            }
        });

        kernel.launch();

        // UserComponent should reach RUNNING quickly because the slow per-message load
        // runs on a background thread and does not block kernel initialization.
        boolean componentStartedQuickly = componentRunningLatch.await(
                COMPONENT_STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(componentStartedQuickly,
                "UserComponent should reach RUNNING within " + COMPONENT_STARTUP_TIMEOUT_SECONDS
                        + "s. The DiskSpooler background load must not block component startup.");

        // The background load should have started but NOT yet completed (it takes ~50s total).
        assertTrue(SlowMockDiskSpoolerService.syncStarted.get(),
                "Spooler background load should have started during kernel initialization");
        assertFalse(SlowMockDiskSpoolerService.syncCompleted.get(),
                "Spooler background load should still be in progress when the component is already RUNNING");
    }

    /**
     * Mock DiskSpooler plugin that simulates slow per-message reads.
     */
    @ImplementsService(name = "aws.greengrass.DiskSpooler", autostart = false)
    public static class SlowMockDiskSpoolerService extends PluginService implements CloudMessageSpool {

        static volatile int messageCount = 1000;
        static volatile long perMessageDelayMs = 50;

        static final AtomicBoolean syncStarted = new AtomicBoolean(false);
        static final AtomicBoolean syncCompleted = new AtomicBoolean(false);
        static final AtomicLong getMessageByIdCallCount = new AtomicLong(0);

        static void reset() {
            syncStarted.set(false);
            syncCompleted.set(false);
            getMessageByIdCallCount.set(0);
        }

        public SlowMockDiskSpoolerService(Topics topics) {
            super(topics);
        }

        @Override
        public SpoolMessage getMessageById(long id) {
            long callCount = getMessageByIdCallCount.incrementAndGet();
            if (callCount == 1) {
                syncStarted.set(true);
            }

            try {
                Thread.sleep(perMessageDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (callCount >= messageCount) {
                syncCompleted.set(true);
            }

            byte[] payload = String.format("msg-%d", id).getBytes(StandardCharsets.UTF_8);
            Publish publish = Publish.builder()
                    .topic("test/spooler/startup")
                    .qos(QOS.AT_LEAST_ONCE)
                    .payload(payload)
                    .build();
            return SpoolMessage.builder()
                    .id(id)
                    .request(publish)
                    .build();
        }

        @Override
        public void removeMessageById(long id) {
        }

        @Override
        public void add(long id, SpoolMessage message) throws IOException {
        }

        @Override
        public Iterable<Long> getAllMessageIds() throws IOException {
            List<Long> ids = new ArrayList<>(messageCount);
            for (long i = 0; i < messageCount; i++) {
                ids.add(i);
            }
            return ids;
        }

        @Override
        public void initializeSpooler() throws IOException {
        }
    }
}
