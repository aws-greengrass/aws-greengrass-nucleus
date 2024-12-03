/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.deployment.DeploymentDirectoryManager;
import com.aws.greengrass.deployment.bootstrap.BootstrapManager;
import com.aws.greengrass.lifecyclemanager.exceptions.DirectoryValidationException;
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
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.ROLLBACK_BOOTSTRAP;
import static com.aws.greengrass.lifecyclemanager.KernelAlternatives.KERNEL_DISTRIBUTION_DIR;
import static com.aws.greengrass.lifecyclemanager.KernelAlternatives.LAUNCH_PARAMS_FILE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.io.FileMatchers.anExistingFileOrDirectory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.internal.verification.VerificationModeFactory.times;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class KernelAlternativesTest {
    @TempDir
    Path altsDir;
    @Mock
    ComponentManager componentManager;
    private KernelAlternatives kernelAlternatives;
    @Mock
    BootstrapManager bootstrapManager;
    @Mock
    DeploymentDirectoryManager deploymentDirectoryManager;

    @BeforeEach
    void beforeEach() throws IOException {
        NucleusPaths paths = new NucleusPaths("mock_loader_logs.log");
        paths.setKernelAltsPath(altsDir);
        kernelAlternatives = spy(new KernelAlternatives(paths, componentManager));
    }

    @Test
    void GIVEN_old_broken_dir_not_found_WHEN_determine_deployment_stage_THEN_return_default() {
        assertEquals(DEFAULT,
                kernelAlternatives.determineDeploymentStage(bootstrapManager, deploymentDirectoryManager));
    }

    @Test
    void GIVEN_broken_dir_WHEN_determine_deployment_stage_THEN_return_rollback() throws Exception {
        kernelAlternatives.setupLinkToDirectory(kernelAlternatives.getBrokenDir(), createRandomDirectory());
        doReturn(createRandomFile()).when(deploymentDirectoryManager).getRollbackBootstrapTaskFilePath();
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
    void GIVEN_broken_dir_with_pending_rollback_bootstrap_WHEN_determine_deployment_stage_THEN_return_rollback_bootstrap() throws Exception {
        kernelAlternatives.setupLinkToDirectory(kernelAlternatives.getBrokenDir(), createRandomDirectory());
        Path mockFile = createRandomFile();
        doReturn(mockFile).when(deploymentDirectoryManager).getRollbackBootstrapTaskFilePath();
        doReturn(true).when(bootstrapManager).hasNext();
        assertEquals(ROLLBACK_BOOTSTRAP,
                kernelAlternatives.determineDeploymentStage(bootstrapManager, deploymentDirectoryManager));
        verify(bootstrapManager, times(1)).loadBootstrapTaskList(eq(mockFile));
    }

    @Test
    void GIVEN_kernel_update_WHEN_success_THEN_launch_dir_update_correctly() throws Exception {
        Path initPath = createRandomDirectory();
        kernelAlternatives.setupLinkToDirectory(kernelAlternatives.getCurrentDir(), initPath);
        doNothing().when(kernelAlternatives).cleanupLoaderLogs();

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
    void GIVEN_kernel_update_with_same_deployment_id_WHEN_success_THEN_launch_dir_update_correctly() throws Exception {
        // testing the scenario when the existing launch dir and the new launch dir are constructed from the same
        // deployment id
        String mockDeploymentId = "mockDeployment";
        Path launchPath = altsDir.resolve(mockDeploymentId);
        Files.createDirectories(launchPath);
        kernelAlternatives.setupLinkToDirectory(kernelAlternatives.getCurrentDir(), launchPath);
        doNothing().when(kernelAlternatives).cleanupLoaderLogs();

        kernelAlternatives.prepareBootstrap(mockDeploymentId);
        assertEquals(launchPath, Files.readSymbolicLink(kernelAlternatives.getCurrentDir()));
        assertEquals(launchPath, Files.readSymbolicLink(kernelAlternatives.getOldDir()));

        kernelAlternatives.activationSucceeds();
        assertThat(kernelAlternatives.getOldDir().toFile(), not(anExistingFileOrDirectory()));
        assertThat(launchPath.toFile(), anExistingFileOrDirectory());
    }

    @Test
    void GIVEN_initDirPointingWrongLocation_WHEN_redirectInitDir_THEN_dirIsRedirectedCorrectly() throws Exception {
        Path outsidePath = createRandomDirectory();
        Path unpackPath = createRandomDirectory();
        Files.createDirectories(unpackPath.resolve("bin"));
        String loaderName = "loader";
        if (PlatformResolver.isWindows) {
            loaderName = "loader.cmd";
        }
        Files.createFile(unpackPath.resolve("bin").resolve(loaderName));

        Path distroPath = kernelAlternatives.getInitDir().resolve(KERNEL_DISTRIBUTION_DIR);
        Files.createDirectories(kernelAlternatives.getInitDir());
        // current -> init
        kernelAlternatives.setupLinkToDirectory(kernelAlternatives.getCurrentDir(), kernelAlternatives.getInitDir());
        // init/distro -> outsidePath
        kernelAlternatives.setupLinkToDirectory(distroPath, outsidePath);
        assertEquals(kernelAlternatives.getInitDir(), Files.readSymbolicLink(kernelAlternatives.getCurrentDir()));
        assertEquals(outsidePath, Files.readSymbolicLink(distroPath));

        // current -> some random path
        Files.deleteIfExists(kernelAlternatives.getCurrentDir());
        Path random = createRandomDirectory();
        Files.createDirectories(random.resolve(KERNEL_DISTRIBUTION_DIR).resolve("bin"));
        Files.createFile(random.resolve(KERNEL_DISTRIBUTION_DIR).resolve("bin").resolve(loaderName));
        kernelAlternatives.setupLinkToDirectory(kernelAlternatives.getCurrentDir(), random);

        // Relink without changing the current dir location
        kernelAlternatives.relinkInitLaunchDir(unpackPath, false);
        // Ensure we didn't change the current path, only the init path
        assertNotEquals(kernelAlternatives.getInitDir(), Files.readSymbolicLink(kernelAlternatives.getCurrentDir()));
        assertEquals(unpackPath, Files.readSymbolicLink(distroPath));

        // Relink and change the current dir to point to init
        kernelAlternatives.relinkInitLaunchDir(unpackPath, true);
        // Ensure this time we did change the current path
        assertEquals(kernelAlternatives.getInitDir(), Files.readSymbolicLink(kernelAlternatives.getCurrentDir()));
        assertEquals(unpackPath, Files.readSymbolicLink(distroPath));
    }

    @Test
    void GIVEN_kernel_update_WHEN_failure_THEN_launch_dir_rollback_correctly() throws Exception {
        Path initPath = createRandomDirectory();
        kernelAlternatives.setupLinkToDirectory(kernelAlternatives.getCurrentDir(), initPath);
        doNothing().when(kernelAlternatives).cleanupLoaderLogs();

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

    @Test
    void GIVEN_validate_launch_dir_setup_WHEN_current_link_missing_and_exception_THEN_directory_validation_exception() throws IOException {
        // GIVEN
        Path outsidePath = createRandomDirectory();
        Path unpackPath = createRandomDirectory();
        Files.createDirectories(unpackPath.resolve("bin"));
        String loaderName = "loader";
        if (PlatformResolver.isWindows) {
            loaderName = "loader.cmd";
        }
        Files.createFile(unpackPath.resolve("bin").resolve(loaderName));

        Path distroPath = kernelAlternatives.getInitDir().resolve(KERNEL_DISTRIBUTION_DIR);
        Files.createDirectories(kernelAlternatives.getInitDir());
        // current -> init
        kernelAlternatives.setupLinkToDirectory(kernelAlternatives.getCurrentDir(), kernelAlternatives.getInitDir());
        // init/distro -> outsidePath
        kernelAlternatives.setupLinkToDirectory(distroPath, outsidePath);
        assertEquals(kernelAlternatives.getInitDir(), Files.readSymbolicLink(kernelAlternatives.getCurrentDir()));
        assertEquals(outsidePath, Files.readSymbolicLink(distroPath));

        // WHEN
        Files.deleteIfExists(kernelAlternatives.getCurrentDir());
        lenient().doThrow(new IOException("Random test failure"))
                .when(kernelAlternatives).relinkInitLaunchDir(any(Path.class), eq(true));

        // THEN
        DirectoryValidationException ex =  assertThrows(DirectoryValidationException.class,
                () -> kernelAlternatives.validateLaunchDirSetupVerbose());
        assertEquals(ex.getMessage(), "Unable to relink init launch directory");
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
