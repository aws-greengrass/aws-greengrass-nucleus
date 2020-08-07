/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.deployment.DeploymentDirectoryManager;
import com.aws.iot.evergreen.deployment.bootstrap.BootstrapManager;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.util.Utils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentStage.BOOTSTRAP;
import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentStage.DEFAULT;
import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentStage.KERNEL_ACTIVATION;
import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentStage.KERNEL_ROLLBACK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.internal.verification.VerificationModeFactory.times;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class KernelAlternativesTest {
    @TempDir
    Path altsDir;

    private KernelAlternatives kernelAlternatives;
    @Mock
    BootstrapManager bootstrapManager;
    @Mock
    DeploymentDirectoryManager deploymentDirectoryManager;

    @BeforeEach
    void beforeEach() {
        kernelAlternatives = new KernelAlternatives(altsDir);
    }

    @Test
    void GIVEN_old_broken_dir_not_found_WHEN_determine_deployment_stage_THEN_return_default() {
        assertEquals(DEFAULT,
                kernelAlternatives.determineDeploymentStage(bootstrapManager, deploymentDirectoryManager));
    }

    @Test
    void GIVEN_broken_dir_WHEN_determine_deployment_stage_THEN_return_rollback() throws Exception {
        kernelAlternatives.setupLinkToDirectory(kernelAlternatives.getBrokenDir(), createRandomDirectory());
        assertEquals(KERNEL_ROLLBACK,
                kernelAlternatives.determineDeploymentStage(bootstrapManager, deploymentDirectoryManager));
    }

    @Test
    void GIVEN_old_dir_with_no_pending_bootstrap_WHEN_determine_deployment_stage_THEN_return_activation() throws Exception {
        kernelAlternatives.setupLinkToDirectory(kernelAlternatives.getOldDir(), createRandomDirectory());
        doReturn(createRandomFile()).when(deploymentDirectoryManager).getBootstrapTaskFilePath();
        doReturn(false).when(bootstrapManager).hasNext();
        assertEquals(KERNEL_ACTIVATION,
                kernelAlternatives.determineDeploymentStage(bootstrapManager, deploymentDirectoryManager));
    }

    @Test
    void GIVEN_old_dir_with_pending_bootstrap_WHEN_determine_deployment_stage_THEN_return_bootstrap() throws Exception {
        kernelAlternatives.setupLinkToDirectory(kernelAlternatives.getOldDir(), createRandomDirectory());
        Path mockFile = createRandomFile();
        doReturn(mockFile).when(deploymentDirectoryManager).getBootstrapTaskFilePath();
        doReturn(true).when(bootstrapManager).hasNext();
        assertEquals(BOOTSTRAP,
                kernelAlternatives.determineDeploymentStage(bootstrapManager, deploymentDirectoryManager));
        verify(bootstrapManager, times(1)).loadBootstrapTaskList(eq(mockFile));
    }

    @Test
    void GIVEN_kernel_update_WHEN_success_THEN_launch_dir_update_correctly() throws Exception {
        Path initPath = createRandomDirectory();
        kernelAlternatives.setupLinkToDirectory(kernelAlternatives.getCurrentDir(), initPath);

        String mockDeploymentId = "mockDeployment";
        kernelAlternatives.prepareBootstrap(mockDeploymentId);
        Path expectedNewLaunchPath = altsDir.resolve(mockDeploymentId);
        assertEquals(expectedNewLaunchPath, Files.readSymbolicLink(kernelAlternatives.getCurrentDir()));
        assertEquals(initPath, Files.readSymbolicLink(kernelAlternatives.getOldDir()));

        kernelAlternatives.activationSucceeds();
        assertFalse(Files.exists(kernelAlternatives.getOldDir()));
        assertFalse(Files.exists(initPath));
    }

    @Test
    void GIVEN_kernel_update_WHEN_failure_THEN_launch_dir_rollback_correctly() throws Exception {
        Path initPath = createRandomDirectory();
        kernelAlternatives.setupLinkToDirectory(kernelAlternatives.getCurrentDir(), initPath);

        String mockDeploymentId = "mockDeployment";
        Path expectedNewLaunchPath = altsDir.resolve(mockDeploymentId);
        kernelAlternatives.prepareBootstrap(mockDeploymentId);
        kernelAlternatives.prepareRollback();
        assertEquals(expectedNewLaunchPath, Files.readSymbolicLink(kernelAlternatives.getBrokenDir()));
        assertEquals(initPath, Files.readSymbolicLink(kernelAlternatives.getCurrentDir()));
        assertFalse(Files.exists(kernelAlternatives.getOldDir()));

        kernelAlternatives.rollbackCompletes();
        assertFalse(Files.exists(kernelAlternatives.getBrokenDir()));
        assertFalse(Files.exists(expectedNewLaunchPath));
    }

    private Path createRandomDirectory() throws IOException {
        Path path = altsDir.resolve(Utils.generateRandomString(4));
        Utils.createPaths(path);
        return path;
    }

    private Path createRandomFile() throws IOException {
        Path path = altsDir.resolve(Utils.generateRandomString(4));
        Files.createFile(path);
        return path;
    }
}
