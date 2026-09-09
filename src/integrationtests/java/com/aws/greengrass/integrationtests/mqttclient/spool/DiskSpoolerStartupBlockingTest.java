/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.mqttclient.spool;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.ImplementsService;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.PluginService;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.spool.CloudMessageSpool;
import com.aws.greengrass.mqttclient.spool.SpoolMessage;
import com.aws.greengrass.mqttclient.v5.Publish;
import com.aws.greengrass.mqttclient.v5.QOS;
import com.aws.greengrass.util.EncryptionUtilsTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test verifying the fix for GitHub issue #1832:
 * DiskSpooler's per-message load now runs on a background thread, so user component startup
 * is no longer blocked by it.
 *
 * <p>The test uses a {@link SlowMockDiskSpoolerService} that simulates a large number of persisted
 * messages with a per-message delay (simulating SQLite I/O on constrained hardware). It asserts
 * that a user component with no dependency on the spooler reaches RUNNING immediately, and that
 * publishing while the background load is still in progress neither blocks the publisher nor
 * other components' lifecycle transitions.</p>
 *
 * @see <a href="https://github.com/aws-greengrass/aws-greengrass-nucleus/issues/1832">Issue #1832</a>
 */
class DiskSpoolerStartupBlockingTest extends BaseITCase {

    private static final int NUM_SPOOLED_MESSAGES = 1000;
    private static final long PER_MESSAGE_DELAY_MS = 50; // 50ms per message = ~50s total sync time
    private static final int COMPONENT_STARTUP_TIMEOUT_SECONDS = 15;
    private static final int PUBLISH_TIMEOUT_SECONDS = 2;
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
     * Verifies that a publish while the background load is in progress does not wait for the load. On a
     * cloud-configured device, publishes come from the IPC event-loop thread (PublishToIoTCore) and from
     * lifecycle threads holding the global lifecycle lock (Fleet Status), so a publish that waited for the
     * load would stall IPC and other components' state transitions.
     */
    @SuppressWarnings("PMD.CloseResource") // MqttClient is owned by the kernel and closed on kernel.shutdown()
    @Test
    void GIVEN_large_persisted_spooler_queue_WHEN_publish_during_load_THEN_publish_and_lifecycle_do_not_block(
            ExtensionContext context) throws Exception {
        // The endpoint is fake, so services that subscribe to IoT Core (e.g. IoT Jobs) time out and log it.
        ignoreExceptionUltimateCauseOfType(context, TimeoutException.class);
        SlowMockDiskSpoolerService.messageCount = NUM_SPOOLED_MESSAGES;
        SlowMockDiskSpoolerService.perMessageDelayMs = PER_MESSAGE_DELAY_MS;

        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                this.getClass().getResource("disk_spooler_startup_blocking.yaml"));
        // Dummy cloud config so publishes reach the spool. The endpoint is fake, so nothing actually connects,
        // but the MQTT client still needs readable certificate files to be created.
        Path certPath = tempRootDir.resolve("cert.pem");
        Path keyPath = tempRootDir.resolve("key.pem");
        KeyPair keyPair = EncryptionUtilsTest.generateCertificateFile(2048, true, certPath, false).getRight();
        EncryptionUtilsTest.writePemFile("PRIVATE KEY", keyPair.getPrivate().getEncoded(), keyPath);
        DeviceConfiguration deviceConfiguration = kernel.getContext().get(DeviceConfiguration.class);
        deviceConfiguration.getThingName().withValue("ThingName");
        deviceConfiguration.getIotDataEndpoint().withValue("xxxxxx-ats.iot.us-east-1.amazonaws.com");
        deviceConfiguration.getIotCredentialEndpoint().withValue("xxxxxx.credentials.iot.us-east-1.amazonaws.com");
        deviceConfiguration.getPrivateKeyFilePath().withValue(keyPath.toString());
        deviceConfiguration.getCertificateFilePath().withValue(certPath.toString());
        deviceConfiguration.getRootCAFilePath().withValue(certPath.toString());
        deviceConfiguration.getAWSRegion().withValue("us-east-1");

        CountDownLatch componentRunningLatch = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals(USER_COMPONENT_NAME) && newState.equals(State.RUNNING)) {
                componentRunningLatch.countDown();
            }
        });
        kernel.launch();
        assertTrue(componentRunningLatch.await(COMPONENT_STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "UserComponent should reach RUNNING while the DiskSpooler background load is in progress");
        assertFalse(SlowMockDiskSpoolerService.syncCompleted.get(),
                "Spooler background load should still be in progress");

        // The publish only spools the message, so it must return promptly instead of waiting for the load.
        MqttClient mqttClient = kernel.getContext().get(MqttClient.class);
        Publish publish = Publish.builder().topic("test/spooler/during-load").qos(QOS.AT_LEAST_ONCE)
                .payload("during-load".getBytes(StandardCharsets.UTF_8)).build();
        assertTimeoutPreemptively(Duration.ofSeconds(PUBLISH_TIMEOUT_SECONDS), () -> mqttClient.publish(publish),
                "Publishing during the DiskSpooler background load must not wait for the load");

        // An unrelated component can still go through a lifecycle transition while the load runs.
        CountDownLatch restartedLatch = new CountDownLatch(1);
        AtomicBoolean leftRunning = new AtomicBoolean(false);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals(USER_COMPONENT_NAME)) {
                if (oldState.equals(State.RUNNING)) {
                    leftRunning.set(true);
                } else if (leftRunning.get() && newState.equals(State.RUNNING)) {
                    restartedLatch.countDown();
                }
            }
        });
        GreengrassService userComponent = kernel.locate(USER_COMPONENT_NAME);
        userComponent.requestRestart();
        assertTrue(restartedLatch.await(COMPONENT_STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "UserComponent restart should complete while the DiskSpooler background load is in progress");
        assertFalse(SlowMockDiskSpoolerService.syncCompleted.get(),
                "Spooler background load should still be in progress after the publish and restart");
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
