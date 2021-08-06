/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.deployment.DeploymentDirectoryManager;
import com.aws.greengrass.deployment.bootstrap.BootstrapManager;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.CommitableWriter;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.Platform;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.AccessLevel;
import lombok.Getter;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.inject.Inject;

import static com.aws.greengrass.deployment.DeploymentDirectoryManager.getSafeFileName;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.BOOTSTRAP;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.DEFAULT;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.KERNEL_ACTIVATION;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.KERNEL_ROLLBACK;
import static com.aws.greengrass.util.Permissions.OWNER_RWX_EVERYONE_RX;
import static com.aws.greengrass.util.Utils.copyFolderRecursively;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class KernelAlternatives {
    private static final Logger logger = LogManager.getLogger(KernelAlternatives.class);

    private static final String CURRENT_DIR = "current";
    private static final String OLD_DIR = "old";
    private static final String NEW_DIR = "new";
    private static final String BROKEN_DIR = "broken";

    private static final String INITIAL_SETUP_DIR = "init";
    static final String KERNEL_DISTRIBUTION_DIR = "distro";
    private static final String SYSTEMD_SERVICE_FILE = "greengrass.service";
    private static final String SYSTEMD_SERVICE_TEMPLATE = "greengrass.service.template";
    public static final String KERNEL_BIN_DIR = "bin";
    private static final String KERNEL_LIB_DIR = "lib";
    private static final String LOADER_PID_FILE = "loader.pid";
    public static final String LOADER_FILE = "loader";
    static final String LAUNCH_PARAMS_FILE = "launch.params";

    private final Path altsDir;
    @Getter(AccessLevel.PACKAGE)
    private final Path initDir;
    // Symlink to the current launch directory
    @Getter(AccessLevel.PACKAGE)
    private Path currentDir;
    // Symlink to the new launch directory during kernel update
    @Getter(AccessLevel.PACKAGE)
    private Path newDir;
    // Symlink to the old launch directory during kernel update
    @Getter(AccessLevel.PACKAGE)
    private Path oldDir;
    // Symlink to the broken new launch directory during kernel update
    @Getter(AccessLevel.PACKAGE)
    private Path brokenDir;

    /**
     * Constructor for KernelAlternatives, which manages the alternative launch directory of Kernel.
     *
     * @param nucleusPaths nucleus paths
     */
    @Inject
    public KernelAlternatives(NucleusPaths nucleusPaths) {
        this.altsDir = nucleusPaths.kernelAltsPath().toAbsolutePath();
        this.currentDir = altsDir.resolve(CURRENT_DIR).toAbsolutePath();
        this.oldDir = altsDir.resolve(OLD_DIR).toAbsolutePath();
        this.newDir = altsDir.resolve(NEW_DIR).toAbsolutePath();
        this.brokenDir = altsDir.resolve(BROKEN_DIR).toAbsolutePath();
        this.initDir = altsDir.resolve(INITIAL_SETUP_DIR).toAbsolutePath();

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
        return getLoaderPathFromLaunchDir(currentDir);
    }

    private Path getLoaderPathFromLaunchDir(Path path) {
        return path.resolve(KERNEL_DISTRIBUTION_DIR).resolve(KERNEL_BIN_DIR).resolve(LOADER_FILE);
    }

    public Path getServiceTemplatePath() {
        return currentDir.resolve(KERNEL_DISTRIBUTION_DIR).resolve(KERNEL_BIN_DIR).resolve(SYSTEMD_SERVICE_TEMPLATE);
    }

    public Path getServiceConfigPath() {
        return currentDir.resolve(KERNEL_DISTRIBUTION_DIR).resolve(KERNEL_BIN_DIR).resolve(SYSTEMD_SERVICE_FILE);
    }

    public Path getLaunchParamsPath() {
        return currentDir.resolve(LAUNCH_PARAMS_FILE);
    }

    /**
     * Write the given string to launch parameters file.
     *
     * @param content file content string
     * @throws IOException on I/O error
     */
    public void writeLaunchParamsToFile(String content) throws IOException {
        try (CommitableWriter out = CommitableWriter.abandonOnClose(getLaunchParamsPath())) {
            out.write(content);
            out.commit();
        }
    }

    public boolean isLaunchDirSetup() {
        return Files.isSymbolicLink(currentDir) && validateLaunchDirSetup(currentDir);
    }

    @SuppressWarnings("PMD.ConfusingTernary")
    private boolean validateLaunchDirSetup(Path path) {
        Path loaderPath = getLoaderPathFromLaunchDir(path);
        if (!Files.exists(loaderPath)) {
            return false;
        } else if (!loaderPath.toFile().canExecute()) {
            // Ensure that the loader is executable so that we can exec it when restarting Nucleus
            try {
                Platform.getInstance().setPermissions(OWNER_RWX_EVERYONE_RX, loaderPath);
            } catch (IOException e) {
                logger.error("Unable to set loader script at {} as executable", loaderPath, e);
                return false;
            }
        }
        return true;
    }

    /**
     * Create launch directory in the initial setup.
     *
     * @throws IOException on I/O error
     * @throws URISyntaxException if unable to determine source path of the Jar file
     */
    public void setupInitLaunchDirIfAbsent() throws IOException {
        if (isLaunchDirSetup()) {
            logger.atDebug().log("Launch directory has been set up");
            return;
        }
        try {
            Path unpackDir = locateCurrentKernelUnpackDir();
            relinkInitLaunchDir(unpackDir, true);
        } catch (IOException | URISyntaxException e) {
            logger.atWarn().log(e.getMessage());
            if (validateLaunchDirSetup(initDir)) {
                setupLinkToDirectory(currentDir, initDir);
                logger.atDebug().kv("directory", initDir).log("Found previous launch directory setup");
            }
        }
    }

    /**
     * Unconditionally relink alts/init to the provided path and alts/current to alts/init.
     *
     * @param pathToNucleusDistro path to the unzipped Nucleus distribution
     * @param linkCurrentToInit relink the current path to the init path, false if current should be left alone and
     *                          only init should be relinked.
     * @throws IOException on I/O error
     */
    public void relinkInitLaunchDir(Path pathToNucleusDistro, boolean linkCurrentToInit) throws IOException {
        Path distroDir = initDir.resolve(KERNEL_DISTRIBUTION_DIR);

        Utils.createPaths(initDir);
        Files.deleteIfExists(distroDir);
        setupLinkToDirectory(distroDir, pathToNucleusDistro);

        if (linkCurrentToInit) {
            Files.deleteIfExists(currentDir);
            setupLinkToDirectory(currentDir, initDir);
        }

        if (!isLaunchDirSetup()) {
            throw new IOException("Failed to setup initial launch directory. Expecting loader script at: "
                    + getLoaderPath());
        }
    }

    /**
     * Locate launch directory of Kernel, assuming unpack directory tree as below.
     * ├── bin
     * │   ├── greengrass.service.template
     * │   └── loader
     * └── lib
     *     └── Greengrass.jar
     *
     * @return Path of the unpack directory
     * @throws IOException if directory structure does not match the expectation
     * @throws URISyntaxException if the source code location isn't a proper URI
     */
    @SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE", justification = "Spotbugs false positive")
    public static Path locateCurrentKernelUnpackDir() throws IOException, URISyntaxException {
        Path parentDir = new File(KernelAlternatives.class.getProtectionDomain().getCodeSource().getLocation()
                .toURI()).toPath().getParent();
        if (parentDir == null || ! Files.exists(parentDir)
                || parentDir.getFileName() != null && !KERNEL_LIB_DIR.equals(parentDir.getFileName().toString())) {
            throw new IOException("Unable to locate the unpack directory of Nucleus Jar file");
        }
        Path unpackDir = parentDir.getParent();
        if (unpackDir == null || ! Files.exists(unpackDir) || !Files.isDirectory(unpackDir.resolve(KERNEL_BIN_DIR))) {
            throw new IOException("Unable to locate the unpack directory of Nucleus artifacts");
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
        Path launchDirToCleanUp = Files.readSymbolicLink(oldDir);
        Files.delete(oldDir);
        cleanupLaunchDirectorySingleLevel(launchDirToCleanUp.toFile());
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
        Files.delete(currentDir);
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
        cleanupLaunchDirectorySingleLevel(Files.readSymbolicLink(brokenDir).toFile());
        Files.delete(brokenDir);
    }

    /**
     * Set up files and directories in order to run bootstrap steps before activating new Kernel configuration.
     *
     * @param deploymentId deployment ID which associates with the bootstrap task list
     * @throws IOException if file or directory changes fail
     */
    public void prepareBootstrap(String deploymentId) throws IOException {
        logger.atInfo().log("Setting up launch directory for new Nucleus");
        Path newLaunchDir = altsDir.resolve(getSafeFileName(deploymentId)).toAbsolutePath();
        Path existingLaunchDir = Files.readSymbolicLink(currentDir).toAbsolutePath();
        copyFolderRecursively(existingLaunchDir, newLaunchDir, REPLACE_EXISTING, NOFOLLOW_LINKS, COPY_ATTRIBUTES);

        cleanupLaunchDirectoryLinks();
        setupLinkToDirectory(newDir, newLaunchDir);
        setupLinkToDirectory(oldDir, existingLaunchDir);
        Files.delete(currentDir);

        setupLinkToDirectory(currentDir, newLaunchDir);
        Files.delete(newDir);
        logger.atInfo().log("Finished setup of launch directory for new Nucleus");
    }

    /**
     * Set up a link to the directory.
     *
     * @param link link to create
     * @param directory path to link to
     * @throws IOException on I/O error
     */
    public void setupLinkToDirectory(Path link, Path directory) throws IOException {
        logger.atDebug().kv("link", link).kv("directory", directory).log("Set up link to directory");
        Files.createSymbolicLink(link, directory);
    }

    /**
     * Clean up launch directory symlinks left from previous deployments, if any.
     */
    public void cleanupLaunchDirectoryLinks() {
        cleanupLaunchDirectoryLink(brokenDir);
        cleanupLaunchDirectoryLink(oldDir);
        cleanupLaunchDirectoryLink(newDir);
    }

    private void cleanupLaunchDirectoryLink(Path link) {
        try {
            Files.deleteIfExists(link);
        } catch (IOException e) {
            logger.atWarn().kv("link", link).log(
                    "Failed to clean up launch directory link from previous deployments", e);
        }
    }

    private void cleanupLaunchDirectorySingleLevel(File filePath) throws IOException {
        File[] files = filePath.listFiles();
        if (files != null) {
            for (File file : files) {
                Files.deleteIfExists(file.toPath());
            }
        }
        Files.deleteIfExists(filePath.toPath());
    }
}
