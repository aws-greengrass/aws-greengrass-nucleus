/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.deployment.DeploymentDirectoryManager;
import com.aws.iot.evergreen.deployment.bootstrap.BootstrapManager;
import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Utils;
import lombok.Getter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.aws.iot.evergreen.deployment.DeploymentDirectoryManager.getSafeFileName;
import static com.aws.iot.evergreen.util.Utils.copyFolderRecursively;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class KernelAlternatives {
    private static final Logger logger = LogManager.getLogger(BootstrapManager.class);

    private static final String CURRENT_DIR = "current";
    private static final String OLD_DIR = "old";
    private static final String BROKEN_DIR = "broken";
    private static final String PREVIOUS_SUCCESS_DIR = "previousSuccess";
    private static final String PREVIOUS_FAILURE_DIR = "previousFailure";

    private final Path altsDir;
    // Symlink to the current launch directory
    @Getter
    private Path currentDir;
    // Symlink to the old launch directory during kernel update
    @Getter
    private Path oldDir;
    // Symlink to the broken new launch directory during kernel update
    @Getter
    private Path brokenDir;
    // Symlink to the previous working launch directory after kernel update
    private final Path previousSuccessDir;
    // Symlink to the broken new launch directory after rollback from kernel update
    private final Path previousFailureDir;

    private final BootstrapManager bootstrapManager;
    private final DeploymentDirectoryManager deploymentDirectoryManager;

    /**
     * Constructor for KernelAlternatives, which manages the alternative launch directory of Kernel.
     *
     * @param bootstrapManager BootstrapManager instance to manage pending bootstrap tasks
     * @param deploymentDirectoryManager DeploymentDirectoryManager instance to manage persisted deployment information
     * @param kernelAltsPath alternative launch directory of Kernel
     */
    public KernelAlternatives(BootstrapManager bootstrapManager, DeploymentDirectoryManager deploymentDirectoryManager,
                              Path kernelAltsPath) {
        this.altsDir = kernelAltsPath.toAbsolutePath();
        this.currentDir = kernelAltsPath.resolve(CURRENT_DIR).toAbsolutePath();
        this.oldDir = kernelAltsPath.resolve(OLD_DIR).toAbsolutePath();
        this.brokenDir = kernelAltsPath.resolve(BROKEN_DIR).toAbsolutePath();
        this.previousSuccessDir = kernelAltsPath.resolve(PREVIOUS_SUCCESS_DIR).toAbsolutePath();
        this.previousFailureDir = kernelAltsPath.resolve(PREVIOUS_FAILURE_DIR).toAbsolutePath();
        this.bootstrapManager = bootstrapManager;
        this.deploymentDirectoryManager = deploymentDirectoryManager;
    }

    /**
     * Determine if Kernel is in update workflow from deployments and return deployment stage.
     *
     * @return DeploymentStage
     */
    public Deployment.DeploymentStage determineDeploymentStage() {
        // TODO: validate if any directory is corrupted
        if (oldDir.toFile().exists()) {
            try {
                Path persistedBootstrapTasks = deploymentDirectoryManager.getBootstrapTaskFilePath();
                if (!persistedBootstrapTasks.toFile().exists()) {
                    return Deployment.DeploymentStage.KERNEL_ACTIVATION;
                }
                bootstrapManager.loadBootstrapTaskList(persistedBootstrapTasks);
                if (bootstrapManager.hasNext()) {
                    return Deployment.DeploymentStage.BOOTSTRAP;
                }
            } catch (IOException | ClassNotFoundException e) {
                logger.atWarn().setCause(e).log("Bootstrap task list not found or unable to read the file");
            }
            return Deployment.DeploymentStage.KERNEL_ACTIVATION;
        } else if (brokenDir.toFile().exists()) {
            return Deployment.DeploymentStage.KERNEL_ROLLBACK;
        }
        return Deployment.DeploymentStage.DEFAULT;
    }

    /**
     * Clean up files and directories if Kernel update deployments succeeds.
     *
     * @throws IOException if file or directory changes fail
     */
    public void activationSucceeds() throws IOException {
        cleanupAltDir(previousSuccessDir);
        cleanupAltDir(previousFailureDir);
        Files.createSymbolicLink(previousSuccessDir, Files.readSymbolicLink(oldDir).toAbsolutePath());
        Files.delete(oldDir);
    }

    /**
     * Set up files and directories in order to rollback Kernel to the previous configuration.
     *
     * @throws IOException if file or directory changes fail
     */
    public void prepareRollback() throws IOException {
        if (!Files.exists(oldDir)) {
            return;
        }
        Files.deleteIfExists(brokenDir);
        Files.createSymbolicLink(brokenDir, Files.readSymbolicLink(currentDir).toAbsolutePath());
        Files.delete(currentDir);
        Files.createSymbolicLink(currentDir, Files.readSymbolicLink(oldDir).toAbsolutePath());
    }

    /**
     * Clean up files and directories if Kernel update rollback completes.
     *
     * @throws IOException if file or directory changes fail
     */
    public void rollbackCompletes() throws IOException {
        cleanupAltDir(previousFailureDir);
        Files.createSymbolicLink(previousFailureDir, Files.readSymbolicLink(brokenDir).toAbsolutePath());
        Files.deleteIfExists(brokenDir);
    }

    /**
     * Set up files and directories in order to run bootstrap steps before activating new Kernel configuration.
     *
     * @param deploymentId deployment ID which associates with the bootstrap task list
     * @throws IOException if file or directory changes fail
     */
    public void prepareBootstrap(String deploymentId) throws IOException {
        logger.atInfo().log("Setting up launch directory for new Kernel");
        Path newLaunchDir = altsDir.resolve(getSafeFileName(deploymentId)).toAbsolutePath();
        Path existingLaunchDir = Files.readSymbolicLink(currentDir).toAbsolutePath();
        copyFolderRecursively(existingLaunchDir, newLaunchDir, REPLACE_EXISTING, NOFOLLOW_LINKS, COPY_ATTRIBUTES);
        Files.deleteIfExists(oldDir);
        Files.createSymbolicLink(oldDir, existingLaunchDir);
        Files.deleteIfExists(currentDir);
        Files.createSymbolicLink(currentDir, newLaunchDir);
        logger.atInfo().log("Finish setup of launch directory for new Kernel");
    }

    /**
     * Clean up files and directories, and remove symlink references.
     *
     * @param path file path to cleanup
     * @throws IOException if unable to delete
     */
    public void cleanupAltDir(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Utils.deleteFileRecursively(Files.readSymbolicLink(path).toAbsolutePath().toFile());
        Files.delete(path);
    }
}
