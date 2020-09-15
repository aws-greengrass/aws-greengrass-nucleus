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
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.AccessLevel;
import lombok.Getter;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.aws.iot.evergreen.deployment.DeploymentDirectoryManager.getSafeFileName;
import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentStage.BOOTSTRAP;
import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentStage.DEFAULT;
import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentStage.KERNEL_ACTIVATION;
import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentStage.KERNEL_ROLLBACK;
import static com.aws.iot.evergreen.util.Utils.copyFolderRecursively;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class KernelAlternatives {
    private static final Logger logger = LogManager.getLogger(KernelAlternatives.class);

    private static final String CURRENT_DIR = "current";
    private static final String OLD_DIR = "old";
    private static final String BROKEN_DIR = "broken";

    private static final String INITIAL_SETUP_DIR = "init";
    private static final String KERNEL_DISTRIBUTION_DIR = "distro";
    private static final String SYSTEMD_SERVICE_FILE = "greengrass.service";
    private static final String SYSTEMD_SERVICE_TEMPLATE = "greengrass.service.template";
    private static final String KERNEL_BIN_DIR = "bin";
    private static final String KERNEL_LIB_DIR = "lib";
    private static final String LOADER_PID_FILE = "loader.pid";
    private static final String LOADER_FILE = "loader";

    private final Path altsDir;
    // Symlink to the current launch directory
    @Getter(AccessLevel.PACKAGE)
    private Path currentDir;
    // Symlink to the old launch directory during kernel update
    @Getter(AccessLevel.PACKAGE)
    private Path oldDir;
    // Symlink to the broken new launch directory during kernel update
    @Getter(AccessLevel.PACKAGE)
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

        try {
            setupInitLaunchDirIfAbsent();
        } catch (IOException e) {
            logger.atWarn().log(e.getMessage());
        }
    }

    /**
     * Get pid file for loader.
     *
     * @return path to pid file
     */
    public Path getLoaderPidPath() {
        return altsDir.resolve(LOADER_PID_FILE);
    }

    /**
     * Get loader file.
     *
     * @return path to loader file
     */
    public Path getLoaderPath() {
        return currentDir.resolve(KERNEL_DISTRIBUTION_DIR).resolve(KERNEL_BIN_DIR).resolve(LOADER_FILE);
    }

    public Path getServiceTemplatePath() {
        return currentDir.resolve(KERNEL_DISTRIBUTION_DIR).resolve(KERNEL_BIN_DIR).resolve(SYSTEMD_SERVICE_TEMPLATE);
    }

    public Path getServiceConfigPath() {
        return currentDir.resolve(KERNEL_DISTRIBUTION_DIR).resolve(KERNEL_BIN_DIR).resolve(SYSTEMD_SERVICE_FILE);
    }

    public boolean isLaunchDirSetup() {
        // TODO: check for file and directory corruptions
        return currentDir.toFile().exists();
    }

    /**
     * Create launch directory in the initial setup.
     *
     * @throws IOException on I/O error
     * @throws URISyntaxException if unable to determine source path of the Jar file
     */
    public void setupInitLaunchDirIfAbsent() throws IOException {
        if (isLaunchDirSetup()) {
            return;
        }
        Path unpackDir;
        try {
            unpackDir = locateCurrentKernelUnpackDir();
        } catch (IOException | URISyntaxException e) {
            logger.atWarn().log(e.getMessage());
            return;
        }
        Path initialLaunchDir = altsDir.resolve(INITIAL_SETUP_DIR);
        Utils.createPaths(initialLaunchDir);

        setupLinkToDirectory(initialLaunchDir.resolve(KERNEL_DISTRIBUTION_DIR), unpackDir);
        setupLinkToDirectory(currentDir, initialLaunchDir);
    }

    /**
     * Locate launch directory of Kernel, assuming unpack directory tree as below.
     * ├── bin
     * │   ├── greengrass.service.template
     * │   └── loader
     * └── lib
     *     └── Evergreen.jar
     */
    @SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE", justification = "Spotbugs false positive")
    private Path locateCurrentKernelUnpackDir() throws IOException, URISyntaxException {
        Path parentDir = new File(KernelAlternatives.class.getProtectionDomain().getCodeSource().getLocation()
                .toURI()).toPath().getParent();
        if (parentDir == null || ! Files.exists(parentDir)
                || parentDir.getFileName() != null && !KERNEL_LIB_DIR.equals(parentDir.getFileName().toString())) {
            throw new IOException("Unable to locate the unpack directory of Kernel Jar file");
        }
        Path unpackDir = parentDir.getParent();
        if (unpackDir == null || ! Files.exists(unpackDir) || !Files.isDirectory(unpackDir.resolve(KERNEL_BIN_DIR))) {
            throw new IOException("Unable to locate the unpack directory of Kernel artifacts");
        }
        return unpackDir;
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
                    return KERNEL_ACTIVATION;
                }
                bootstrapManager.loadBootstrapTaskList(persistedBootstrapTasks);
                if (bootstrapManager.hasNext()) {
                    return BOOTSTRAP;
                }
            } catch (IOException e) {
                logger.atError().setCause(e).log("Bootstrap task list not found or unable to read the file");
            }
            return KERNEL_ACTIVATION;
        } else if (brokenDir.toFile().exists()) {
            return KERNEL_ROLLBACK;
        }
        return DEFAULT;
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
        setupLinkToDirectory(brokenDir, Files.readSymbolicLink(currentDir).toAbsolutePath());
        setupLinkToDirectory(currentDir, Files.readSymbolicLink(oldDir).toAbsolutePath());
        Files.delete(oldDir);
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

        setupLinkToDirectory(oldDir, existingLaunchDir);
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
