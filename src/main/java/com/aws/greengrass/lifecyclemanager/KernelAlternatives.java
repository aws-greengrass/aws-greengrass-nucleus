/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.DeploymentDirectoryManager;
import com.aws.greengrass.deployment.bootstrap.BootstrapManager;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.exceptions.ComponentConfigurationValidationException;
import com.aws.greengrass.deployment.exceptions.DeploymentException;
import com.aws.greengrass.deployment.exceptions.ServiceUpdateException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.lifecyclemanager.exceptions.DirectoryValidationException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.CommitableWriter;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.Platform;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.inject.Inject;

import static com.aws.greengrass.deployment.DeploymentDirectoryManager.getSafeFileName;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.BOOTSTRAP;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.DEFAULT;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.KERNEL_ACTIVATION;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.KERNEL_ROLLBACK;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.ROLLBACK_BOOTSTRAP;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC;
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
    public static final String KERNEL_BIN_DIR = "bin";
    private static final String KERNEL_LIB_DIR = "lib";
    private static final String LOADER_PID_FILE = "loader.pid";
    static final String LAUNCH_PARAMS_FILE = "launch.params";
    private static final String BOOTSTRAP_ON_ROLLBACK_CONFIG_KEY = "bootstrapOnRollback";

    private final NucleusPaths nucleusPaths;
    private final ComponentManager componentManager;

    /**
     * Constructor for KernelAlternatives, which manages the alternative launch directory of Kernel.
     *
     * @param nucleusPaths nucleus paths
     * @param componentManager component manager
     */
    @Inject
    public KernelAlternatives(NucleusPaths nucleusPaths, ComponentManager componentManager) {
        this.nucleusPaths = nucleusPaths;
        this.componentManager = componentManager;
        try {
            setupInitLaunchDirIfAbsent();
        } catch (IOException e) {
            logger.atWarn().log(e.getMessage());
        }
    }

    private Path getAltsDir() {
        return nucleusPaths.kernelAltsPath().toAbsolutePath();
    }

    // Symlink to the broken new launch directory during kernel update
    Path getBrokenDir() {
        return getAltsDir().resolve(BROKEN_DIR).toAbsolutePath();
    }

    // Symlink to the old launch directory during kernel update
    Path getOldDir() {
        return getAltsDir().resolve(OLD_DIR).toAbsolutePath();
    }

    // Symlink to the new launch directory during kernel update
    Path getNewDir() {
        return getAltsDir().resolve(NEW_DIR).toAbsolutePath();
    }

    // Symlink to the current launch directory
    Path getCurrentDir() {
        return getAltsDir().resolve(CURRENT_DIR).toAbsolutePath();
    }

    Path getInitDir() {
        return getAltsDir().resolve(INITIAL_SETUP_DIR).toAbsolutePath();
    }

    /**
     * Get pid file for loader.
     *
     * @return path to pid file
     */
    public Path getLoaderPidPath() {
        return getAltsDir().resolve(LOADER_PID_FILE);
    }

    /**
     * Get loader file.
     *
     * @return path to loader file
     */
    public Path getLoaderPath() {
        return getLoaderPathFromLaunchDir(getCurrentDir());
    }

    private Path getLoaderPathFromLaunchDir(Path path) {
        return path.resolve(KERNEL_DISTRIBUTION_DIR).resolve(KERNEL_BIN_DIR)
                .resolve(Platform.getInstance().loaderFilename());
    }

    public Path getBinDir() {
        return getCurrentDir().resolve(KERNEL_DISTRIBUTION_DIR).resolve(KERNEL_BIN_DIR);
    }

    public Path getLaunchParamsPath() {
        return getCurrentDir().resolve(LAUNCH_PARAMS_FILE);
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
        return Files.isSymbolicLink(getCurrentDir()) && validateLaunchDirSetup(getCurrentDir());
    }

    protected boolean canRecoverMissingLaunchDirSetup()
            throws IOException, URISyntaxException, PackageLoadingException {
        /*
        Try and relink launch dir with the following replacement criteria
        1. check if current Nucleus execution package is valid
        2. un-archive current Nucleus version from component store
        3. fail with DirectoryValidationException if above steps do not satisfy
         */
        Path currentNucleusExecutablePath = locateCurrentKernelUnpackDir();
        if (Files.exists(currentNucleusExecutablePath.resolve(KERNEL_BIN_DIR)
                .resolve(Platform.getInstance().loaderFilename()))) {
            logger.atDebug().kv("path", currentNucleusExecutablePath)
                    .log("Current Nucleus executable is valid, setting up launch dir");
            relinkInitLaunchDir(currentNucleusExecutablePath, true);
            return true;
        }

        List<Path> localNucleusExecutablePaths = componentManager.unArchiveCurrentNucleusVersionArtifacts();
        if (!localNucleusExecutablePaths.isEmpty()) {
            Optional<Path> validNucleusExecutablePath = localNucleusExecutablePaths.stream()
                    .filter(path -> Files.exists(path.resolve(KERNEL_BIN_DIR)
                            .resolve(Platform.getInstance().loaderFilename())))
                    .findFirst();
            if (validNucleusExecutablePath.isPresent()) {
                logger.atDebug().kv("path", validNucleusExecutablePath.get())
                        .log("Un-archived current Nucleus artifact");
                relinkInitLaunchDir(validNucleusExecutablePath.get(), true);
                return true;
            }
        }
        throw new PackageLoadingException("Could not find a valid Nucleus package to recover launch dir setup");
    }

    /**
     * Validate that launch directory is set up.
     *
     * @throws DirectoryValidationException when a file is missing
     * @throws DeploymentException when user is not allowed to change file permission
     */
    public void validateLaunchDirSetupVerbose() throws DirectoryValidationException, DeploymentException {
        try {
            if (!Files.isSymbolicLink(getCurrentDir()) || !Files.exists(getLoaderPathFromLaunchDir(getCurrentDir()))) {
                logger.atInfo().log("Current launch dir setup is missing, attempting to recover");
                canRecoverMissingLaunchDirSetup();
            }
        } catch (PackageLoadingException | IOException ex) {
            throw new DirectoryValidationException("Unable to relink init launch directory", ex);
        } catch (URISyntaxException ex) {
            // TODO: Fix usage of root path with spaces on linux
            throw new DeploymentException("Could not parse init launch directory path", ex);
        }

        Path currentDir = getCurrentDir();
        Path loaderPath = getLoaderPathFromLaunchDir(currentDir);
        if (!loaderPath.toFile().canExecute()) {
            // Ensure that the loader is executable so that we can exec it when restarting Nucleus
            try {
                Platform.getInstance().setPermissions(OWNER_RWX_EVERYONE_RX, loaderPath);
            } catch (IOException e) {
                throw new DeploymentException(
                        String.format("Unable to set loader script at %s as executable", loaderPath), e)
                        .withErrorContext(e, DeploymentErrorCode.SET_PERMISSION_ERROR);
            }
        }
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
            if (validateLaunchDirSetup(getInitDir())) {
                setupLinkToDirectory(getCurrentDir(), getInitDir());
                logger.atDebug().kv("directory", getInitDir()).log("Found previous launch directory setup");
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
        Path distroDir = getInitDir().resolve(KERNEL_DISTRIBUTION_DIR);

        Utils.createPaths(getInitDir());
        Files.deleteIfExists(distroDir);
        setupLinkToDirectory(distroDir, pathToNucleusDistro);

        if (linkCurrentToInit) {
            Files.deleteIfExists(getCurrentDir());
            setupLinkToDirectory(getCurrentDir(), getInitDir());
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
    @SuppressWarnings("PMD.PreserveStackTrace")
    @SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE", justification = "Spotbugs false positive")
    public static Path locateCurrentKernelUnpackDir() throws IOException, URISyntaxException {
        Path parentDir;
        try {
            parentDir = new File(KernelAlternatives.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toPath().getParent();
            if (parentDir == null || !Files.exists(parentDir) || parentDir.getFileName() != null && !KERNEL_LIB_DIR
                    .equals(parentDir.getFileName().toString())) {
                throw new IOException("Unable to locate the unpack directory of Nucleus Jar file");
            }
        } catch (IllegalArgumentException e) {
            // Illegal argument happens when the source path isn't a file. This occurs in static compilation.
            // When statically compiled, the location of the binary is in the java.home property.
            parentDir = new File(System.getProperty("java.home")).toPath().getParent();
        }
        Path unpackDir = parentDir.getParent();
        if (unpackDir == null || !Files.exists(unpackDir) || !Files.isDirectory(unpackDir.resolve(KERNEL_BIN_DIR))) {
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
        if (getOldDir().toFile().exists()) {
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
        } else if (getBrokenDir().toFile().exists()) {
            try {
                Path rollbackBootstrapTasks = deploymentDirectoryManager.getRollbackBootstrapTaskFilePath();
                if (rollbackBootstrapTasks.toFile().exists()) {
                    bootstrapManager.loadBootstrapTaskList(rollbackBootstrapTasks);
                    if (bootstrapManager.hasNext()) {
                        return ROLLBACK_BOOTSTRAP;
                    }
                }
            } catch (IOException e) {
                logger.atError().setCause(e).log("Bootstrap-on-rollback task list was not readable");
            }
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
        Path launchDirToCleanUp = Files.readSymbolicLink(getOldDir()).toAbsolutePath();
        Files.delete(getOldDir());
        if (Files.isSameFile(launchDirToCleanUp, getCurrentDir())) {
            logger.atInfo().kv("oldDir", launchDirToCleanUp).log("Skipping launch directory cleanup after kernel "
                    + "update due to matching directory names. Likely the same deployment was executed twice on the "
                    + "device");
            return;
        }
        logger.atDebug().kv("oldDir", launchDirToCleanUp).log("Cleaning up previous kernel launch directory");
        cleanupLaunchDirectorySingleLevel(launchDirToCleanUp.toFile());
    }

    /**
     * Set up files and directories in order to rollback Kernel to the previous configuration.
     *
     * @throws IOException if file or directory changes fail
     */
    public void prepareRollback() throws IOException {
        if (!Files.exists(getOldDir())) {
            logger.atWarn().log("Cannot find the old launch directory to rollback to.");
            return;
        }
        setupLinkToDirectory(getBrokenDir(), Files.readSymbolicLink(getCurrentDir()).toAbsolutePath());
        Files.delete(getCurrentDir());
        setupLinkToDirectory(getCurrentDir(), Files.readSymbolicLink(getOldDir()).toAbsolutePath());
        Files.delete(getOldDir());
    }

    /**
     * Clean up files and directories if Kernel update rollback completes.
     *
     * @throws IOException if file or directory changes fail
     */
    public void rollbackCompletes() throws IOException {
        if (!Files.exists(getBrokenDir())) {
            return;
        }
        cleanupLaunchDirectorySingleLevel(Files.readSymbolicLink(getBrokenDir()).toFile());
        Files.delete(getBrokenDir());
    }

    /**
     * Set up files and directories in order to run bootstrap steps before activating new Kernel configuration.
     *
     * @param deploymentId deployment ID which associates with the bootstrap task list
     * @throws IOException if file or directory changes fail
     */
    public void prepareBootstrap(String deploymentId) throws IOException {
        logger.atInfo().log("Setting up launch directory for new Nucleus");
        Path newLaunchDir = getAltsDir().resolve(getSafeFileName(deploymentId)).toAbsolutePath();
        Path existingLaunchDir = Files.readSymbolicLink(getCurrentDir()).toAbsolutePath();
        copyFolderRecursively(existingLaunchDir, newLaunchDir, REPLACE_EXISTING, NOFOLLOW_LINKS, COPY_ATTRIBUTES);

        cleanupLaunchDirectoryLinks();
        setupLinkToDirectory(getNewDir(), newLaunchDir);
        setupLinkToDirectory(getOldDir(), existingLaunchDir);
        Files.delete(getCurrentDir());

        setupLinkToDirectory(getCurrentDir(), newLaunchDir);
        Files.delete(getNewDir());
        logger.atInfo().log("Finished setup of launch directory for new Nucleus");

        cleanupLoaderLogs();
    }

    /**
     * Cleans up loader logs dumped in loader.log by acquiring a lock on the file first as
     * Windows FS does not allow a brute force truncate.
     */
    @SuppressWarnings("PMD.AvoidFileStream")
    protected void cleanupLoaderLogs() {
        logger.atDebug().kv("logs-path", getLoaderLogsPath().toAbsolutePath()).log("Cleaning up Nucleus logs");
        try (FileOutputStream fos = new FileOutputStream(getLoaderLogsPath().toAbsolutePath().toString());
             FileChannel channel = fos.getChannel()) {
            // Try to acquire a lock
            FileLock lock = channel.tryLock();

            if (lock == null) {
                logger.atWarn().log("Cannot clean Nucleus logs, the log file is locked by another process");
            } else {
                try {
                    // Truncate the file
                    channel.truncate(0);
                } finally {
                    // Release and close the lock
                    lock.close();
                    logger.atDebug().log("Finished cleaning up Nucleus logs");
                }
            }
        } catch (IOException e) {
            logger.atError().setCause(e).log("Error while cleaning the Nucleus logs file");
        }
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
        cleanupLaunchDirectoryLink(getBrokenDir());
        cleanupLaunchDirectoryLink(getOldDir());
        cleanupLaunchDirectoryLink(getNewDir());
    }

    /**
     * Prepare for bootstrapping in the context of a rollback deployment, if such bootstrapping is required.
     *
     * @param context Context instance for the rollback configuration
     * @param deploymentDirectoryManager DeploymentDirectoryManager instance for obtaining path information
     * @param bootstrapManager BootstrapManager instance for managing pending bootstrap tasks
     * @return true if bootstrapping is required during rollback, otherwise false
     */
    public boolean prepareBootstrapOnRollbackIfNeeded(Context context,
                                                      DeploymentDirectoryManager deploymentDirectoryManager,
                                                      BootstrapManager bootstrapManager) {
        Configuration rollbackConfig = new Configuration(context);
        try {
            rollbackConfig.read(deploymentDirectoryManager.getSnapshotFilePath());
        } catch (IOException exc) {
            logger.atError().log("Failed to read rollback snapshot config", exc);
            return false;
        }
        boolean bootstrapOnRollbackRequired;
        try {
            // Check if we need to execute component bootstrap steps during the rollback deployment.
            final Set<String> componentsToExclude =
                    getComponentsToExcludeFromBootstrapOnRollback(bootstrapManager, rollbackConfig);
            bootstrapOnRollbackRequired = bootstrapManager.isBootstrapRequired(rollbackConfig.toPOJO(),
                    componentsToExclude);
        } catch (ServiceUpdateException | ComponentConfigurationValidationException exc) {
            logger.atError().log("Rollback config invalid or could not be parsed", exc);
            return false;
        }
        Path rollbackBootstrapTaskFilePath;
        try {
            rollbackBootstrapTaskFilePath = deploymentDirectoryManager.getRollbackBootstrapTaskFilePath();
        } catch (IOException exc) {
            logger.atError().log("Bootstrap-on-rollback task file path could not be resolved", exc);
            return false;
        }
        if (bootstrapOnRollbackRequired) {
            // Bootstrap-on-rollback is required, so write the task file.
            try {
                bootstrapManager.persistBootstrapTaskList(rollbackBootstrapTaskFilePath);
            } catch (IOException exc) {
                logger.atError().log("Bootstrap-on-rollback task file could not be written", exc);
                return false;
            }
        } else {
            logger.atInfo().log("No component with a pending rollback bootstrap task found: "
                    + "No rollback deployment exists or rollback deployment has no bootstrap tasks");
            // Bootstrap-on-rollback is not required, so ensure that the task file is deleted.
            try {
                bootstrapManager.deleteBootstrapTaskList(rollbackBootstrapTaskFilePath);
            } catch (IOException exc) {
                logger.atError().log("Bootstrap-on-rollback task file could not be cleaned up", exc);
                return false;
            }
        }
        return bootstrapOnRollbackRequired;
    }

    private Set<String> getComponentsToExcludeFromBootstrapOnRollback(BootstrapManager bootstrapManager,
                                                                      Configuration rollbackConfig) {
        // Exclude components with bootstrap steps that did not execute during the target deployment.
        final Set<String> componentsToExclude = bootstrapManager.getUnstartedTasks();
        logger.atDebug().kv("components", componentsToExclude)
                .log("These components did not bootstrap during the target deployment. "
                        + "They will be excluded from bootstrap-on-rollback.");
        // Exclude components that are not explicitly configured to bootstrap-on-rollback
        Set<String> unconfiguredComponents = getComponentsNotConfiguredToBootstrapOnRollback(rollbackConfig);
        logger.atDebug().kv("components", unconfiguredComponents)
                .log("These components are not configured to execute bootstrap steps during rollback. "
                        + "They will be excluded from bootstrap-on-rollback.");
        componentsToExclude.addAll(unconfiguredComponents);
        return componentsToExclude;
    }

    private Set<String> getComponentsNotConfiguredToBootstrapOnRollback(Configuration rollbackConfig) {
        Topics services = rollbackConfig.findTopics(SERVICES_NAMESPACE_TOPIC);
        if (services == null) {
            return Collections.emptySet();
        }
        Set<String> componentsNotConfiguredToBootstrapOnRollback = new HashSet<>();
        services.forEach((service) -> {
            String serviceName = service.getName();
            if (service instanceof Topics) {
                boolean bootstrapOnRollback = Coerce.toBoolean(((Topics) service).findOrDefault(false,
                        SERVICE_LIFECYCLE_NAMESPACE_TOPIC, LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC,
                        BOOTSTRAP_ON_ROLLBACK_CONFIG_KEY));
                if (!bootstrapOnRollback) {
                    componentsNotConfiguredToBootstrapOnRollback.add(serviceName);
                }
            }
        });
        return componentsNotConfiguredToBootstrapOnRollback;
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

    public Path getLoaderLogsPath() {
        return nucleusPaths.loaderLogsPath().toAbsolutePath();
    }
}
