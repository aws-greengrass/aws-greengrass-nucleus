/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;


import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.CommitableReader;
import com.aws.greengrass.util.CommitableWriter;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.SerializerFactory;
import com.aws.greengrass.util.Utils;
import lombok.AccessLevel;
import lombok.Getter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;

import static com.aws.greengrass.deployment.DeploymentConfigMerger.DEPLOYMENT_ID_LOG_KEY;

/**
 * Deployment directory manager preserves deployment artifacts for configuration rollback workflow and troubleshooting.
 */
public class DeploymentDirectoryManager {
    static final String ROLLBACK_SNAPSHOT_FILE = "rollback_snapshot.tlog";
    static final String TARGET_CONFIG_FILE = "target_config.tlog";
    static final String BOOTSTRAP_TASK_FILE = "bootstrap_task.json";
    static final String ROLLBACK_BOOTSTRAP_TASK_FILE = "rollback_bootstrap_task.json";
    static final String DEPLOYMENT_METADATA_FILE = "deployment_metadata.json";
    static final String CONFIG_SNAPSHOT_ERROR = "config_snapshot_error";
    static final String PROCESSING_ATTEMPTS_FILE = "processing_attempts";

    /**
     * The attempt limit is recorded next to the count rather than read when it is needed, because the
     * launch consults the count before it has loaded any configuration. A deployment is therefore judged
     * by the limit in force when it was applied, which is also the limit its operator chose.
     */
    static final String ATTEMPT_LIMIT_FILE = "processing_attempt_limit";

    // Files preserved from an unfinished deployment are parked here while its directory is cleaned up.
    private static final String PRESERVED_FILE_PREFIX = ".preserved-";

    private static final String LINK_LOG_KEY = "link";
    private static final String FILE_LOG_KEY = "file";

    private static final String PREVIOUS_SUCCESS_LINK = "previous-success";
    private static final String PREVIOUS_FAILURE_LINK = "previous-failure";
    private static final String ONGOING_DEPLOYMENT_LINK = "ongoing";
    private static final Logger logger = LogManager.getLogger(DeploymentDirectoryManager.class);
    private final Kernel kernel;

    private final Path deploymentsDir;
    @Getter(AccessLevel.PACKAGE)
    private final Path previousSuccessDir;
    @Getter(AccessLevel.PACKAGE)
    private final Path previousFailureDir;
    @Getter(AccessLevel.PACKAGE)
    private final Path ongoingDir;

    /**
     * Constructor of deployment directory manager for kernel.
     *
     * @param kernel a kernel instance
     * @param nucleusPaths nucleus paths
     */
    @Inject
    public DeploymentDirectoryManager(Kernel kernel, NucleusPaths nucleusPaths) {
        this.kernel = kernel;
        this.deploymentsDir = nucleusPaths.deploymentPath();
        this.previousFailureDir = deploymentsDir.resolve(PREVIOUS_FAILURE_LINK);
        this.previousSuccessDir = deploymentsDir.resolve(PREVIOUS_SUCCESS_LINK);
        this.ongoingDir = deploymentsDir.resolve(ONGOING_DEPLOYMENT_LINK);
    }

    /**
     * Persist the last failed deployment and clean up earlier deployments.
     */
    public void persistLastFailedDeployment() {
        persistPointerToLastFinishedDeployment(previousFailureDir);
    }

    /**
     * Persist the last successful deployment and clean up earlier deployments.
     */
    public void persistLastSuccessfulDeployment() {
        persistPointerToLastFinishedDeployment(previousSuccessDir);
    }

    private void persistPointerToLastFinishedDeployment(Path symlink) {
        logger.atInfo().kv(LINK_LOG_KEY, symlink).log("Persist link to last deployment");
        try {
            cleanupPreviousDeployments(previousSuccessDir);
            cleanupPreviousDeployments(previousFailureDir);

            Path deploymentPath = getDeploymentDirectoryPath();
            Files.createSymbolicLink(symlink, deploymentPath);
            Files.delete(ongoingDir);
        } catch (IOException e) {
            logger.atError().log("Unable to preserve artifacts from the last deployment", e);
        }
    }

    private void cleanupPointersIfExist(Path target) {
        try {
            if (Files.isSymbolicLink(previousFailureDir) && Files.readSymbolicLink(previousFailureDir).equals(target)) {
                Files.delete(previousFailureDir);
            }
        } catch (IOException ignore) {
        }

        try {
            if (Files.isSymbolicLink(previousSuccessDir) && Files.readSymbolicLink(previousSuccessDir).equals(target)) {
                Files.delete(previousSuccessDir);
            }
        } catch (IOException ignore) {
        }
    }

    private void cleanupPreviousDeployments(Path symlink) {
        if (!Files.isSymbolicLink(symlink)) {
            return;
        }
        logger.atInfo().kv(LINK_LOG_KEY, symlink).log("Clean up link to earlier deployment");
        try {
            Utils.deleteFileRecursively(Files.readSymbolicLink(symlink).toFile());
            Files.delete(symlink);
        } catch (IOException ioException) {
            logger.atError().kv(LINK_LOG_KEY, symlink).log("Unable to clean up previous deployments", ioException);
        }
    }

    /**
     * Write Deployment object to file.
     *
     * @param deployment Deployment object
     * @throws IOException on I/O error
     */
    public void writeDeploymentMetadata(Deployment deployment) throws IOException {
        if (!Files.isSymbolicLink(ongoingDir)) {
            throw new IOException("Deployment details can not be saved to directory " + ongoingDir);
        }
        Path filePath = getDeploymentMetadataFilePath();
        logger.atInfo().kv(FILE_LOG_KEY, filePath).kv(DEPLOYMENT_ID_LOG_KEY,
                deployment.getGreengrassDeploymentId()).log("Saving deployment metadata to file");
        writeDeploymentMetadata(filePath, deployment);
    }

    private void writeDeploymentMetadata(Path filePath, Deployment deployment) throws IOException {
        Files.deleteIfExists(filePath);
        Files.createFile(filePath);
        try (CommitableWriter out = CommitableWriter.commitOnClose(filePath)) {
            SerializerFactory.getFailSafeJsonObjectMapper().writeValue(out, deployment);
        }
    }

    /**
     * Read Deployment object from file.
     *
     * @return deployment object
     * @throws IOException on I/O error
     * @throws ClassNotFoundException when deserialization fails
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    public Deployment readDeploymentMetadata() throws IOException {
        if (!Files.isSymbolicLink(ongoingDir)) {
            throw new IOException("Deployment details can not be loaded from file " + ongoingDir);
        }

        Path filePath = getDeploymentMetadataFilePath();
        logger.atInfo().kv(FILE_LOG_KEY, filePath).log("Load deployment metadata");
        AtomicReference<Deployment> deploymentAtomicReference = new AtomicReference<>();
        CommitableReader.of(filePath).read(in -> {
            Deployment deployment = SerializerFactory.getFailSafeJsonObjectMapper().readValue(in, Deployment.class);
            deploymentAtomicReference.set(deployment);
            return null;
        });

        return deploymentAtomicReference.get();
    }

    /**
     * Take a snapshot in a transaction log file before rollback if rollback is applicable for deployment.
     *
     * @param filepath File path to the config snapshot
     * @throws IOException if write fails
     */
    public void takeConfigSnapshot(Path filepath) throws IOException {
        logger.atInfo().kv(FILE_LOG_KEY, filepath).log("Persist configuration snapshot");
        kernel.writeEffectiveConfigAsTransactionLog(filepath);
    }

    /**
     * Take the rollback snapshot for the ongoing deployment, unless one was carried forward from an
     * unfinished deployment: that snapshot holds the last verified configuration and live config may
     * not, so overwriting it would make unverified state the rollback target.
     *
     * @throws IOException if write fails
     */
    public void takeRollbackSnapshot() throws IOException {
        Path filepath = getSnapshotFilePath();
        if (Files.exists(filepath)) {
            logger.atInfo().kv(FILE_LOG_KEY, filepath)
                    .log("Keeping the rollback snapshot carried forward from an unfinished deployment");
            return;
        }
        takeConfigSnapshot(filepath);
    }

    /**
     * Whether a deployment is recorded as still in progress. The ongoing link is created when a
     * deployment starts and removed once its result is reported.
     *
     * @return true if an unfinished deployment's directory is still linked
     */
    public boolean hasUnfinishedDeployment() {
        return Files.isSymbolicLink(ongoingDir);
    }

    /**
     * Resolve snapshot file path.
     *
     * @return Path to snapshot file
     * @throws IOException on I/O errors
     */
    public Path getSnapshotFilePath() throws IOException {
        return getDeploymentDirectoryPath().resolve(ROLLBACK_SNAPSHOT_FILE);
    }

    /**
     * Resolve target config file path.
     *
     * @return Path to target config file
     * @throws IOException on I/O errors
     */
    public Path getTargetConfigFilePath() throws IOException {
        return getDeploymentDirectoryPath().resolve(TARGET_CONFIG_FILE);
    }

    /**
     * Resolve file path to persisted bootstrap task list of an ongoing deployment.
     *
     * @return Path to file
     * @throws IOException on I/O errors
     */
    public Path getBootstrapTaskFilePath() throws IOException {
        return getDeploymentDirectoryPath().resolve(BOOTSTRAP_TASK_FILE);
    }

    /**
     * Resolve file path to persisted bootstrap task list of a rollback deployment.
     *
     * @return Path to file
     * @throws IOException on I/O errors
     */
    public Path getRollbackBootstrapTaskFilePath() throws IOException {
        return getDeploymentDirectoryPath().resolve(ROLLBACK_BOOTSTRAP_TASK_FILE);
    }

    /**
     * Resolve file path to persisted deployment metadata.
     *
     * @return Path to file
     * @throws IOException on I/O errors
     */
    private Path getDeploymentMetadataFilePath() throws IOException {
        return getDeploymentDirectoryPath().resolve(DEPLOYMENT_METADATA_FILE);
    }

    private Path getDeploymentDirectoryPath() throws IOException {
        return Files.readSymbolicLink(ongoingDir).toAbsolutePath();
    }

    /**
     * Create or return the directory for a given deployment.
     *
     * <p>An ongoing directory left behind by an unfinished deployment means the live configuration may
     * hold that deployment's merged but never verified changes, so its rollback snapshot — not live
     * config — is the last verified configuration. Preserve that snapshot into the new deployment's
     * directory, whether the same deployment is re-processed or a different one supersedes it. Preserve
     * the attempt count too when the same deployment is re-processed, so repeated interruptions can be
     * capped.
     *
     * @param deploymentId Deployment id
     * @return Path to the deployment directory
     * @throws IOException on I/O errors
     */
    public Path createNewDeploymentDirectory(String deploymentId) throws IOException {
        final Path preservedSnapshot = stashFileFromUnfinishedDeployment(ROLLBACK_SNAPSHOT_FILE, null);
        final Path preservedAttempts =
                stashFileFromUnfinishedDeployment(PROCESSING_ATTEMPTS_FILE, getSafeFileName(deploymentId));
        final Path preservedLimit =
                stashFileFromUnfinishedDeployment(ATTEMPT_LIMIT_FILE, getSafeFileName(deploymentId));

        cleanupPreviousDeployments(ongoingDir);
        Path path = deploymentsDir.resolve(getSafeFileName(deploymentId));

        if (Files.exists(path)) {
            logger.atWarn().kv("directory", path)
                    .log("Deployment directory already exists. Clean up outdated artifacts and create new");
            try {
                Utils.deleteFileRecursively(path.toFile());
                cleanupPointersIfExist(path);
            } catch (IOException e) {
                logger.atError().log("Failed to clean up outdated deployment artifacts. Ignoring", e);
            }
        }

        logger.atInfo().kv("directory", path).kv(DEPLOYMENT_ID_LOG_KEY, deploymentId).kv(LINK_LOG_KEY, ongoingDir)
                .log("Create work directory for new deployment");
        Utils.createPaths(path);
        restorePreservedFile(preservedSnapshot, path.resolve(ROLLBACK_SNAPSHOT_FILE));
        restorePreservedFile(preservedAttempts, path.resolve(PROCESSING_ATTEMPTS_FILE));
        restorePreservedFile(preservedLimit, path.resolve(ATTEMPT_LIMIT_FILE));
        Files.createSymbolicLink(ongoingDir, path);

        return path;
    }

    /**
     * Move a file out of the unfinished (ongoing) deployment's directory so it survives directory
     * cleanup.
     *
     * @param fileName        name of the file to preserve
     * @param requiredDirName when non-null, preserve only if the unfinished deployment's directory has
     *                        this name, i.e. the same deployment is being re-processed
     * @return path the file moved to, or null when there is nothing to preserve
     */
    private Path stashFileFromUnfinishedDeployment(String fileName, String requiredDirName) {
        try {
            if (!Files.isSymbolicLink(ongoingDir)) {
                // The previous deployment reached a terminal state; the live configuration is verified.
                return null;
            }
            Path unfinishedDir = Files.readSymbolicLink(ongoingDir).toAbsolutePath();
            if (requiredDirName != null && !requiredDirName.equals(unfinishedDir.getFileName().toString())) {
                return null;
            }
            Path file = unfinishedDir.resolve(fileName);
            if (!Files.exists(file)) {
                return null;
            }
            Path stash = deploymentsDir.resolve(PRESERVED_FILE_PREFIX + fileName);
            Files.move(file, stash, StandardCopyOption.REPLACE_EXISTING);
            logger.atInfo().kv(FILE_LOG_KEY, file)
                    .log("Preserving file from unfinished deployment for the next deployment");
            return stash;
        } catch (IOException e) {
            logger.atError().kv(FILE_LOG_KEY, fileName)
                    .log("Unable to preserve file from unfinished deployment", e);
            return null;
        }
    }

    private void restorePreservedFile(Path stash, Path destination) {
        if (stash == null) {
            return;
        }
        try {
            Files.move(stash, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.atError().kv(FILE_LOG_KEY, destination)
                    .log("Unable to restore preserved file into new deployment directory", e);
        }
    }

    /**
     * Record that the ongoing deployment has been applied, and return how many times that has now
     * happened. The count follows the deployment across re-processing (see
     * {@link #createNewDeploymentDirectory}), so it counts attempts that never reached a terminal state —
     * for example because the nucleus was interrupted mid-activation.
     *
     * @param attemptLimit how many attempts this deployment is allowed, or below 1 for no limit
     * @return the attempt number, starting at 1
     * @throws IOException on I/O errors
     */
    public int recordProcessingAttempt(int attemptLimit) throws IOException {
        Path deploymentDir = getDeploymentDirectoryPath();
        Files.write(deploymentDir.resolve(ATTEMPT_LIMIT_FILE),
                String.valueOf(attemptLimit).getBytes(StandardCharsets.UTF_8));
        Path attemptsFile = deploymentDir.resolve(PROCESSING_ATTEMPTS_FILE);
        int attempts = readNumber(attemptsFile, 0) + 1;
        Files.write(attemptsFile, String.valueOf(attempts).getBytes(StandardCharsets.UTF_8));
        return attempts;
    }

    /**
     * How many times the ongoing deployment has been applied.
     *
     * @return the attempt count, or 0 if none is recorded
     */
    public int getProcessingAttempts() {
        try {
            return readNumber(getDeploymentDirectoryPath().resolve(PROCESSING_ATTEMPTS_FILE), 0);
        } catch (IOException e) {
            logger.atError().log("Unable to read the processing attempt count", e);
            return 0;
        }
    }

    /**
     * Whether the ongoing deployment has used up its attempts. Once it has, applying it again would only
     * repeat an attempt that never completes, so the caller should restore the device to the deployment's
     * rollback snapshot and report it failed instead.
     *
     * @return true if the deployment has reached its attempt limit, false if it has not or has no limit
     */
    public boolean hasExhaustedProcessingAttempts() {
        try {
            return hasExhaustedAttempts(getDeploymentDirectoryPath());
        } catch (IOException e) {
            logger.atError().log("Unable to read the processing attempt count", e);
            return false;
        }
    }

    private boolean hasExhaustedAttempts(Path deploymentDir) throws IOException {
        int limit = readNumber(deploymentDir.resolve(ATTEMPT_LIMIT_FILE), 0);
        return limit >= 1 && readNumber(deploymentDir.resolve(PROCESSING_ATTEMPTS_FILE), 0) >= limit;
    }

    private int readNumber(Path file, int fallback) throws IOException {
        if (!Files.exists(file)) {
            return fallback;
        }
        try {
            return Integer.parseInt(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException e) {
            logger.atWarn().kv(FILE_LOG_KEY, file).log("Unreadable number. Treating as absent", e);
            return fallback;
        }
    }

    /**
     * Whether this deployment is the one most recently failed for using up its processing attempts.
     * Processing a re-delivery that was already in flight when that failure was reported would hand the
     * same deployment a fresh allowance and re-apply the configuration the device was just restored
     * from. Any other deployment reaching a terminal state clears the link and count this reads, so a
     * new revision is unaffected.
     *
     * @param deploymentId Deployment id
     * @return true if this deployment already used up its processing attempts
     */
    public boolean wasRefusedForExhaustedAttempts(String deploymentId) {
        try {
            if (!Files.isSymbolicLink(previousFailureDir)) {
                return false;
            }
            Path failedDir = Files.readSymbolicLink(previousFailureDir).toAbsolutePath();
            return getSafeFileName(deploymentId).equals(failedDir.getFileName().toString())
                    && hasExhaustedAttempts(failedDir);
        } catch (IOException e) {
            logger.atError().kv(DEPLOYMENT_ID_LOG_KEY, deploymentId)
                    .log("Unable to tell whether this deployment already used up its attempts", e);
            return false;
        }
    }

    public static String getSafeFileName(String fleetConfigArn) {
        return fleetConfigArn.replace(':', '.').replace('/', '+');
    }
}
