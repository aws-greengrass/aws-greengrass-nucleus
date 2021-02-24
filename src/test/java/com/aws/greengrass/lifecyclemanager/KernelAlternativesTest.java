/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.deployment.DeploymentDirectoryManager;
import com.aws.greengrass.deployment.bootstrap.BootstrapManager;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.Utils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.BOOTSTRAP;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.DEFAULT;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.KERNEL_ACTIVATION;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.KERNEL_ROLLBACK;
import static com.aws.greengrass.lifecyclemanager.KernelAlternatives.LAUNCH_PARAMS_FILE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.io.FileMatchers.anExistingFileOrDirectory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.internal.verification.VerificationModeFactory.times;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class KernelAlternativesTest {
    @TempDir
    Path altsDir;

    private KernelAlternatives kernelAlternatives;
    @Mock
    BootstrapManager bootstrapManager;
    @Mock
    DeploymentDirectoryManager deploymentDirectoryManager;

    @BeforeEach
    void beforeEach() throws IOException {
        NucleusPaths paths = new NucleusPaths();
        paths.setKernelAltsPath(altsDir);
        kernelAlternatives = new KernelAlternatives(paths);
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
        assertThat(kernelAlternatives.getOldDir().toFile(), not(anExistingFileOrDirectory()));
        assertThat(initPath.toFile(), not(anExistingFileOrDirectory()));
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
        assertThat(kernelAlternatives.getOldDir().toFile(), not(anExistingFileOrDirectory()));

        kernelAlternatives.rollbackCompletes();
        assertThat(kernelAlternatives.getBrokenDir().toFile(), not(anExistingFileOrDirectory()));
        assertThat(expectedNewLaunchPath.toFile(), not(anExistingFileOrDirectory()));
    }

    @Test
    void GIVEN_launch_params_THEN_write_to_file() throws Exception {
        Path initPath = createRandomDirectory();
        kernelAlternatives.setupLinkToDirectory(kernelAlternatives.getCurrentDir(), initPath);

        Path expectedLaunchParamsPath = initPath.resolve(LAUNCH_PARAMS_FILE);
        kernelAlternatives.writeLaunchParamsToFile("mock string");

        assertEquals("mock string", new String(Files.readAllBytes(expectedLaunchParamsPath)));
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
