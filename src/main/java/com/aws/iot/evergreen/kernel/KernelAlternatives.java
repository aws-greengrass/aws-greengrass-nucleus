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
import lombok.AccessLevel;
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

    private final Path altsDir;
    // Symlink to the current launch directory
    @Getter(AccessLevel.MODULE)
    private Path currentDir;
    // Symlink to the old launch directory during kernel update
    @Getter(AccessLevel.MODULE)
    private Path oldDir;
    // Symlink to the broken new launch directory during kernel update
    @Getter(AccessLevel.MODULE)
    private Path brokenDir;

    /**
     * Constructor for KernelAlternatives, which manages the alternative launch directory of Kernel.
     *
     * @param kernelAltsPath alternative launch directory of Kernel
     */
    public KernelAlternatives(Path kernelAltsPath) {
        this.altsDir = kernelAltsPath.toAbsolutePath();
        this.currentDir = kernelAltsPath.resolve(CURRENT_DIR).toAbsolutePath();
        this.oldDir = kernelAltsPath.resolve(OLD_DIR).toAbsolutePath();
        this.brokenDir = kernelAltsPath.resolve(BROKEN_DIR).toAbsolutePath();
    }

    /**
     * Determine if Kernel is in update workflow from deployments and return deployment stage.
     *
     * @param bootstrapManager BootstrapManager instance to manage pending bootstrap tasks
     * @param deploymentDirectoryManager DeploymentDirectoryManager instance to manage persisted deployment information
     * @return DeploymentStage
     */
    public Deployment.DeploymentStage determineDeploymentStage(BootstrapManager bootstrapManager,
                                                               DeploymentDirectoryManager deploymentDirectoryManager) {
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
        Utils.deleteFileRecursively(Files.readSymbolicLink(oldDir).toFile());
        Files.delete(oldDir);
    }

    /**
     * Set up files and directories in order to rollback Kernel to the previous configuration.
     *
     * @throws IOException if file or directory changes fail
     */
    public void prepareRollback() throws IOException {
        if (!Files.exists(oldDir)) {
            logger.atWarn().log("Cannot find the old launch directory to rollback to.");
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
        if (!Files.exists(brokenDir)) {
            return;
        }
        Utils.deleteFileRecursively(Files.readSymbolicLink(brokenDir).toFile());
        Files.delete(brokenDir);
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
        setupLinkToDirectory(currentDir, newLaunchDir);
        logger.atInfo().log("Finish setup of launch directory for new Kernel");
    }

    /**
     * Set up a link to the directory.
     *
     * @param link link to create
     * @param directory path to link to
     * @throws IOException on I/O error
     */
    public void setupLinkToDirectory(Path link, Path directory) throws IOException {
        Files.deleteIfExists(link);
        Files.createSymbolicLink(link, directory);
    }
}
