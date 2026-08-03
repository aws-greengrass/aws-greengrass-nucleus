/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.builtin.services.factoryreset;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.KernelLifecycle;
import com.aws.greengrass.tes.TokenExchangeService;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.NucleusPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;
import static com.aws.greengrass.lifecyclemanager.Kernel.FACTORY_RESET_TLOG_FILE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class FactoryResetAgentTest {

    @TempDir
    Path tempDir;

    @Mock
    Kernel kernel;

    @Mock
    KernelLifecycle kernelLifecycle;

    @Mock
    DeviceConfiguration deviceConfiguration;

    @Mock
    TokenExchangeService tokenExchangeService;

    @Mock
    NucleusPaths nucleusPaths;

    FactoryResetAgent agent;

    @BeforeEach
    void setup() throws Exception {
        agent = new FactoryResetAgent();
        agent.setKernel(kernel);
        agent.setKernelLifecycle(kernelLifecycle);
        agent.setDeviceConfiguration(deviceConfiguration);
        agent.setTokenExchangeService(tokenExchangeService);
        agent.setNucleusPaths(nucleusPaths);

        // Point all nucleus paths to temp directories
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        when(nucleusPaths.configPath()).thenReturn(configDir);
        when(nucleusPaths.rootPath()).thenReturn(tempDir);

        // Create empty directories for paths that get wiped
        Path deploymentsDir = tempDir.resolve("deployments");
        Path workDir = tempDir.resolve("work");
        Path pluginsDir = tempDir.resolve("plugins");
        Files.createDirectories(deploymentsDir);
        Files.createDirectories(workDir);
        Files.createDirectories(pluginsDir);

        when(nucleusPaths.deploymentPath()).thenReturn(deploymentsDir);
        when(nucleusPaths.workPath()).thenReturn(workDir);
        when(nucleusPaths.pluginPath()).thenReturn(pluginsDir);
        when(nucleusPaths.telemetryPath()).thenReturn(tempDir.resolve("telemetry"));
        when(nucleusPaths.logStorePath()).thenReturn(tempDir.resolve("logs"));
        when(nucleusPaths.ipcSocketPath()).thenReturn(tempDir.resolve("ipc.socket"));

        // Stub DeviceConfiguration for tryDeleteCoreDevice — use mock Topics
        // (the TES-based deleteCoreDevice will fail gracefully due to no real credentials; that's fine)
        Topic thingNameTopic = mock(Topic.class);
        Topic regionTopic = mock(Topic.class);
        lenient().when(deviceConfiguration.getThingName()).thenReturn(thingNameTopic);
        lenient().when(deviceConfiguration.getAWSRegion()).thenReturn(regionTopic);
    }

    @Test
    void GIVEN_snapshot_missing_WHEN_perform_factory_reset_THEN_throws_illegal_state() {
        // No factory-reset.tlog in configPath → should throw before doing anything
        assertThrows(IllegalStateException.class, () -> agent.performFactoryReset());
    }

    @Test
    void GIVEN_snapshot_present_WHEN_perform_factory_reset_THEN_config_restored_and_kernel_restarted()
            throws Exception {
        // Arrange: create a factory-reset.tlog snapshot
        Path configDir = nucleusPaths.configPath();
        Path snapshotPath = configDir.resolve(FACTORY_RESET_TLOG_FILE);
        Path configTlog = configDir.resolve("config.tlog");
        Path configTlogBackup = configDir.resolve("config.tlog~");
        Path effectiveConfig = configDir.resolve("effectiveConfig.yaml");

        Files.write(snapshotPath, "snapshot-content".getBytes(StandardCharsets.UTF_8));
        Files.write(configTlogBackup, "old-backup".getBytes(StandardCharsets.UTF_8));
        Files.write(effectiveConfig, "old-effective-config".getBytes(StandardCharsets.UTF_8));

        // Create some deployed content that should be wiped
        Path deploymentsDir = nucleusPaths.deploymentPath();
        Files.write(deploymentsDir.resolve("deployment1.json"), "{}".getBytes(StandardCharsets.UTF_8));

        // Act
        agent.performFactoryReset();

        // Assert: config.tlog was restored from snapshot
        assertTrue(Files.exists(configTlog), "config.tlog should be restored");
        assertFalse(Files.exists(configTlogBackup), "config.tlog~ should be deleted");
        assertFalse(Files.exists(effectiveConfig), "effectiveConfig.yaml should be deleted");

        // Assert: deployments directory was wiped
        assertFalse(Files.exists(deploymentsDir), "deployments/ should be deleted");

        // Assert: softShutdown was called to close the tlog writer
        verify(kernelLifecycle).softShutdown(0);

        // Assert: nucleus was restarted
        verify(kernel).shutdown(30, REQUEST_RESTART);
    }

    @Test
    void GIVEN_snapshot_present_WHEN_perform_factory_reset_THEN_deployment_and_work_dirs_wiped() throws Exception {
        // Arrange
        Path configDir = nucleusPaths.configPath();
        Files.write(configDir.resolve(FACTORY_RESET_TLOG_FILE), "snapshot".getBytes(StandardCharsets.UTF_8));

        Path workDir = nucleusPaths.workPath();
        Files.write(workDir.resolve("component-data.txt"), "runtime-data".getBytes(StandardCharsets.UTF_8));

        Path pluginsDir = nucleusPaths.pluginPath();
        Path untrustedDir = pluginsDir.resolve("untrusted");
        Files.createDirectories(untrustedDir);
        Files.write(untrustedDir.resolve("plugin.jar"), "jar-bytes".getBytes(StandardCharsets.UTF_8));

        // Act
        agent.performFactoryReset();

        // Assert: work/ and plugins/untrusted/ were wiped
        assertFalse(Files.exists(workDir), "work/ should be deleted");
        assertFalse(Files.exists(untrustedDir), "plugins/untrusted/ should be deleted");

        // Kernel still restarted
        verify(kernel).shutdown(30, REQUEST_RESTART);
    }
}
