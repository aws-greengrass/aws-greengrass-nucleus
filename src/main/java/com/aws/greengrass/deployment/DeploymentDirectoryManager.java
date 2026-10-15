/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;


import com.aws.greengrass.config.ConfigurationReader;
import com.aws.greengrass.config.ConfigurationWriter;
import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.CommitableFile;
import com.aws.greengrass.util.CommitableReader;
import com.aws.greengrass.util.CommitableWriter;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.SerializerFactory;
import com.aws.greengrass.util.Utils;
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.apache.commons.io.FileUtils;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.inject.Inject;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PREV_VERSION_CONFIG_KEY;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeploymentConfigMerger.DEPLOYMENT_ID_LOG_KEY;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;

/**
 * Deployment directory manager preserves deployment artifacts for configuration rollback workflow and troubleshooting.
 */
// This class owns the deployment directory's whole on-disk protocol — the ongoing and last-finished links, the attempt
// record and the rollback snapshot — whose crash-safety arguments depend on each other. Splitting it is worth doing
// on its own, not as part of a bug fix.
@SuppressWarnings("PMD.ExcessiveClassLength")
public class DeploymentDirectoryManager {
    static final String ROLLBACK_SNAPSHOT_FILE = "rollback_snapshot.tlog";
    static final String TARGET_CONFIG_FILE = "target_config.tlog";
    static final String BOOTSTRAP_TASK_FILE = "bootstrap_task.json";
    static final String ROLLBACK_BOOTSTRAP_TASK_FILE = "rollback_bootstrap_task.json";
    static final String DEPLOYMENT_METADATA_FILE = "deployment_metadata.json";
    static final String CONFIG_SNAPSHOT_ERROR = "config_snapshot_error";

    /**
     * How often the deployment has been applied, under which attempt limit, and what has been decided about
     * it since: one small record, so a crash during an update leaves either the previous version or the next
     * one rather than a count and a limit that disagree. See {@link ProcessingState}.
     */
    static final String PROCESSING_STATE_FILE = "processing_state.json";

    // Re-processing the same deployment keeps its directory, and with it these files; everything else it left
    // behind is cleaned up. The record's backup is kept so a torn update can still fall back to it, and the
    // metadata is kept until it is rewritten so a local deployment stays re-processable meanwhile.
    private static final Set<String> KEPT_WHEN_REPROCESSED = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(ROLLBACK_SNAPSHOT_FILE, PROCESSING_STATE_FILE, backupOf(PROCESSING_STATE_FILE),
                    DEPLOYMENT_METADATA_FILE, backupOf(DEPLOYMENT_METADATA_FILE))));

    // A replacement ongoing link is created under this prefix and then renamed over the old one. A crash
    // between the two leaves the temporary link behind. While the ongoing link exists nothing refers to it, so
    // it is removed as stale; without one it is what a two-step switch was moving in, so it is rolled forward.
    private static final String TEMPORARY_LINK_PREFIX = ".ongoing-switch-";
    // Where a platform that cannot rename a link over a directory link (Windows) parks the old ongoing link while
    // the replacement is moved in. Renaming it rather than deleting it keeps the unfinished deployment referenced
    // at every point of the switch, so an interrupted switch is rolled back to it when nothing can roll forward.
    private static final String DISPLACED_LINK_PREFIX = ".ongoing-displaced-";
    // A rollback snapshot and attempt record found in a directory a new deployment is given would be taken as
    // that deployment's own, so whatever else an outdated directory leaves behind, these must be gone.
    private static final List<String> STATE_FILES = Collections.unmodifiableList(Arrays.asList(
            ROLLBACK_SNAPSHOT_FILE, PROCESSING_STATE_FILE, backupOf(PROCESSING_STATE_FILE)));

    // The record is read when the launch has not loaded any configuration yet, possibly by an older or newer
    // nucleus than the one that wrote it, so it tolerates fields and values it does not know. A value it does not
    // know reads as the UNRECOGNIZED constant of its enum, never as absent: an absent launch decision or terminal
    // outcome means "not decided yet" or "unfinished", which would invite restoring or re-applying a deployment a
    // newer nucleus already dealt with.
    private static final ObjectMapper PROCESSING_STATE_MAPPER = SerializerFactory.getFailSafeJsonObjectMapper()
            .copy().enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE);

    private static final String LINK_LOG_KEY = "link";
    private static final String FILE_LOG_KEY = "file";
    private static final String DIRECTORY_LOG_KEY = "directory";
    private static final String ROLLBACK_CONFIG_FILE_LOG_KEY = "rollbackConfigFile";

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
     *
     * <p>Only the deployment the ongoing link names is moved. A deployment that fails before its own directory
     * replaced an unfinished one's must leave that unfinished deployment where it is: it was never reported, and
     * its rollback snapshot is still the last verified configuration. A failure whose outcome keeps the rollback
     * baseline ({@link TerminalOutcome#FAILED_UNVERIFIED}) also stays on the ongoing link, so the next deployment
     * carries its rollback snapshot forward.
     *
     * <p>Which of the two is decided by the outcome given, as {@link #recordTerminalStatus} decided it, not by
     * reading the record back: writing the record can fail on the same full disk that failed the deployment, and the
     * baseline must not be dropped because of that.
     *
     * @param deploymentId the deployment that failed
     * @param outcome      the terminal outcome {@link #recordTerminalStatus} decided for it
     */
    public void persistLastFailedDeployment(String deploymentId, TerminalOutcome outcome) {
        Path deploymentDir = findOngoingDirectoryOf(deploymentId);
        if (deploymentDir == null) {
            return;
        }
        if (outcome != null && outcome.keepsRollbackBaseline()) {
            logger.atInfo().kv(DIRECTORY_LOG_KEY, deploymentDir).kv(DEPLOYMENT_ID_LOG_KEY, deploymentId)
                    .log("Keeping the failed deployment on the ongoing link. The device may still have changes "
                            + "that were never verified, so its rollback snapshot stays the baseline for the next "
                            + "deployment");
            return;
        }
        persistPointerToLastFinishedDeployment(previousFailureDir);
    }

    /**
     * Persist the last successful deployment and clean up earlier deployments. Only the deployment the ongoing
     * link names is moved, see {@link #persistLastFailedDeployment}.
     *
     * @param deploymentId the deployment that succeeded
     */
    public void persistLastSuccessfulDeployment(String deploymentId) {
        if (findOngoingDirectoryOf(deploymentId) != null) {
            persistPointerToLastFinishedDeployment(previousSuccessDir);
        }
    }

    /**
     * The directory the ongoing link names if it is this deployment's, or null. Every operation that finishes a
     * deployment goes through here: the link moves to a superseding deployment only once that deployment's
     * directory is ready, so until then it still names the unfinished deployment, which must not be finished on
     * behalf of another one.
     */
    private Path findOngoingDirectoryOf(String deploymentId) {
        try {
            Path deploymentDir = resolveOngoingDirectory();
            if (deploymentDir != null && isDirectoryOf(deploymentDir, deploymentId)) {
                return deploymentDir;
            }
            logger.atInfo().kv(DEPLOYMENT_ID_LOG_KEY, deploymentId).kv(LINK_LOG_KEY, ongoingDir)
                    .kv(DIRECTORY_LOG_KEY, deploymentDir)
                    .log("The ongoing link does not name this deployment's directory. Leaving it as it is");
            return null;
        } catch (IOException e) {
            logger.atError().kv(DEPLOYMENT_ID_LOG_KEY, deploymentId).kv(LINK_LOG_KEY, ongoingDir)
                    .log("Unable to read the ongoing link. Leaving it as it is", e);
            return null;
        }
    }

    /**
     * The directory the ongoing link names, which must be this deployment's.
     *
     * @throws IOException if the ongoing link names no directory or another deployment's
     */
    private Path requireOngoingDirectoryOf(String deploymentId) throws IOException {
        Path deploymentDir = resolveOngoingDirectory();
        if (deploymentDir == null || !isDirectoryOf(deploymentDir, deploymentId)) {
            throw new IOException("Deployment " + deploymentId + " is not the ongoing deployment: the ongoing link "
                    + (deploymentDir == null ? "does not exist" : "names " + deploymentDir));
        }
        return deploymentDir;
    }

    /**
     * Whether the ongoing link names this deployment's directory.
     *
     * @param deploymentId Deployment id
     * @return true if this deployment is the one the ongoing link names
     */
    public boolean isOngoingDeployment(String deploymentId) {
        try {
            Path deploymentDir = resolveOngoingDirectory();
            return deploymentDir != null && isDirectoryOf(deploymentDir, deploymentId);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isDirectoryOf(Path directory, String deploymentId) {
        return deploymentId != null && isDirectoryNamed(directory, getSafeFileName(deploymentId));
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
     * Take the rollback snapshot for the ongoing deployment, unless its directory already holds one: carried
     * forward from an unfinished deployment, or taken by an earlier attempt of this one. That snapshot holds the
     * last verified configuration and live config may not, so overwriting it would make unverified state the
     * rollback target.
     *
     * <p>Either way the snapshot is validated before this returns: the caller counts the attempt and merges only
     * once a rollback target is durable, and a dump can seem to succeed without leaving one, because
     * CommitableFile logs rather than throws a failed rename and the transaction log writer logs failed writes.
     *
     * @param deploymentId the deployment being applied, which the ongoing link must name
     * @throws IOException if the snapshot cannot be taken or does not validate, or this deployment is not the
     *                     ongoing one
     */
    public void takeRollbackSnapshot(String deploymentId) throws IOException {
        Path filepath = requireOngoingDirectoryOf(deploymentId).resolve(ROLLBACK_SNAPSHOT_FILE);
        if (Files.exists(filepath)) {
            logger.atInfo().kv(FILE_LOG_KEY, filepath)
                    .log("Keeping the rollback snapshot carried forward from an unfinished deployment or taken by an "
                            + "earlier attempt of this one");
            if (!isValidTransactionLog(filepath)) {
                throw new UnusableRollbackSnapshotException("The rollback snapshot at " + filepath
                        + " is not a valid transaction log", true);
            }
            return;
        }
        takeConfigSnapshot(filepath);
        // A dump an interrupt cut short can end on a whole line and so validate although it is incomplete; the
        // transaction log writer logs rather than throws the failed writes. Only an uninterrupted dump is kept.
        boolean interrupted = Thread.currentThread().isInterrupted();
        if (interrupted || !isValidTransactionLog(filepath)) {
            // Taken from live configuration just now, before anything was applied, so removing it loses nothing
            // and lets the next attempt take it again instead of finding an unusable one.
            try {
                Files.deleteIfExists(filepath);
            } catch (IOException e) {
                logger.atWarn().kv(FILE_LOG_KEY, filepath).log("Unable to remove an invalid rollback snapshot", e);
            }
            checkNotInterrupted("taking the rollback snapshot");
            throw new IOException("The rollback snapshot written to " + filepath + " is missing or not a valid "
                    + "transaction log");
        }
    }

    /**
     * Whether a deployment is on the ongoing link. The ongoing link is created when a deployment starts and
     * removed once its result is reported, except for a deployment whose outcome keeps the rollback baseline
     * ({@link TerminalOutcome#FAILED_UNVERIFIED}, {@link TerminalOutcome#CANCELLED}), which stays until the next
     * deployment carries its rollback snapshot forward.
     *
     * @return true if a deployment's directory is linked as the ongoing one
     */
    public boolean hasUnfinishedDeployment() {
        return Files.isSymbolicLink(ongoingDir);
    }

    /**
     * Whether an unfinished deployment's metadata is on disk and so can be re-processed. A deployment
     * whose directory exists without metadata cannot be recovered, which is an ordinary state rather than
     * a failure worth reporting. A deployment that reached a terminal status is not re-processed, even when
     * it is still on the ongoing link.
     *
     * @return true if metadata for an unfinished deployment can be read
     */
    public boolean hasReadableDeploymentMetadata() {
        try {
            Path deploymentDir = resolveOngoingDirectory();
            return deploymentDir != null && Files.exists(deploymentDir.resolve(DEPLOYMENT_METADATA_FILE))
                    && readProcessingState(deploymentDir).getTerminalOutcome() == null;
        } catch (IOException e) {
            return false;
        }
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
     * capped. The same applies to a deployment that failed while the device may still hold changes that were
     * never verified ({@link TerminalOutcome#FAILED_UNVERIFIED}), and to a cancelled one
     * ({@link TerminalOutcome#CANCELLED}).
     *
     * <p>The snapshot is validated before it is carried, and the unfinished deployment's directory and the
     * ongoing link stay as they are until the new directory holds everything carried, so a failure or a
     * crash at any point leaves the snapshot where the next attempt will find it: a failure to copy it or to
     * switch the link fails this call and leaves the unfinished deployment untouched.
     *
     * <p>A snapshot that is unusable — it does not validate, or an attempt was counted but the snapshot is gone —
     * is a lasting condition rather than a transient one, and there is no verified configuration left to carry.
     * A superseding deployment then fails once, and the unfinished deployment is set aside on purpose
     * ({@link TerminalOutcome#ABANDONED}) so it does not fail every later deployment too. The same deployment
     * re-delivered with an unusable snapshot fails, unless it already used up its attempts: its failure for that
     * reports what the launch did about it instead.
     *
     * @param deploymentId Deployment id
     * @return Path to the deployment directory
     * @throws UnusableRollbackSnapshotException when the unfinished deployment's rollback snapshot is unusable
     * @throws IOException on I/O errors
     */
    // Synchronized with the record writes below: an activation a cancel overtook can still be recording its attempt
    // on another thread while the next deployment reads or rewrites the same records.
    public synchronized Path createNewDeploymentDirectory(String deploymentId) throws IOException {
        recoverInterruptedLinkSwitch();
        String directoryName = getSafeFileName(deploymentId);
        Path path = deploymentsDir.resolve(directoryName);

        // Resolve the ongoing link once, so every decision below is about the same unfinished deployment.
        Path unfinishedDir = findUnfinishedDeploymentDirectory();
        if (unfinishedDir == null) {
            return createDeploymentDirectory(path, deploymentId);
        }

        ProcessingState unfinishedState = readProcessingState(unfinishedDir);
        if (isDirectoryNamed(unfinishedDir, directoryName) && Files.isDirectory(path)
                && Files.isSameFile(unfinishedDir, path)) {
            return reuseUnfinishedDeploymentDirectory(path, deploymentId, unfinishedState);
        }
        try {
            requireUsableRollbackSnapshot(unfinishedDir, unfinishedState, false);
        } catch (UnusableRollbackSnapshotException e) {
            setAsideUnfinishedDeployment(unfinishedDir, unfinishedState, e);
            throw e;
        }
        return supersedeUnfinishedDeployment(unfinishedDir, unfinishedState, path, deploymentId);
    }

    /**
     * The directory of the deployment the ongoing link names if the next deployment must carry its rollback
     * snapshot forward, or null when there is none to carry anything from.
     */
    private Path findUnfinishedDeploymentDirectory() throws IOException {
        Path ongoingDirectory = resolveOngoingDirectory();
        if (ongoingDirectory == null) {
            return null;
        }
        ProcessingState state = readProcessingState(ongoingDirectory);
        if (state.getTerminalOutcome() != null && !state.getTerminalOutcome().keepsRollbackBaseline()) {
            // Its terminal status was recorded, so live config is its settled result and there is nothing to
            // carry. Only moving its directory off the ongoing link was interrupted; finish that now.
            completeFinishedDeployment(state.getTerminalOutcome());
            return null;
        }
        if (!Files.isDirectory(ongoingDirectory)) {
            logger.atWarn().kv(DIRECTORY_LOG_KEY, ongoingDirectory)
                    .log("The unfinished deployment's directory is missing, so it has no rollback snapshot to carry "
                            + "forward");
            return null;
        }
        return ongoingDirectory;
    }

    private Path createDeploymentDirectory(Path path, String deploymentId) throws IOException {
        cleanupPreviousDeployments(ongoingDir);
        removeOutdatedDeploymentDirectory(path);

        logger.atInfo().kv(DIRECTORY_LOG_KEY, path).kv(DEPLOYMENT_ID_LOG_KEY, deploymentId)
                .kv(LINK_LOG_KEY, ongoingDir).log("Create work directory for new deployment");
        Utils.createPaths(path);
        Files.createSymbolicLink(ongoingDir, path);
        return path;
    }

    /**
     * Re-process the same deployment in its own directory. Neither the directory nor the ongoing link moves, so
     * its rollback snapshot and attempt record are never copied or out of place, even if this is interrupted.
     * Everything else the earlier attempt left behind is removed.
     */
    private Path reuseUnfinishedDeploymentDirectory(Path path, String deploymentId, ProcessingState state)
            throws IOException {
        if (!state.hasUsedUpAttempts()) {
            // A deployment that used up its attempts is failed with what the launch decided about it, which
            // accounts for its snapshot already; only one that is to be applied again needs a usable one.
            requireUsableRollbackSnapshot(path, state, true);
        }
        logger.atInfo().kv(DIRECTORY_LOG_KEY, path).kv(DEPLOYMENT_ID_LOG_KEY, deploymentId)
                .log("Re-processing an unfinished deployment in its directory, keeping its rollback snapshot and "
                        + "attempt record");
        if (state.getTerminalOutcome() != null) {
            // It was reported failed, or cancelled, and kept on the ongoing link for its snapshot, and is now
            // processed again.
            state.setTerminalOutcome(null);
            writeProcessingState(path, state);
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
            for (Path entry : entries) {
                if (KEPT_WHEN_REPROCESSED.contains(String.valueOf(entry.getFileName()))) {
                    continue;
                }
                try {
                    deleteEntry(entry);
                } catch (IOException e) {
                    logger.atError().kv(FILE_LOG_KEY, entry)
                            .log("Failed to clean up outdated deployment artifacts. Ignoring", e);
                }
            }
        }
        return path;
    }

    /**
     * A different deployment supersedes the unfinished one. Its rollback snapshot is copied into the new
     * directory, committed and validated there, and only then does the ongoing link move to the new directory
     * and the unfinished one get deleted: until the link moves, it still names the unfinished deployment and its
     * snapshot, so any failure up to then leaves the unfinished deployment exactly as it was.
     */
    private Path supersedeUnfinishedDeployment(Path unfinishedDir, ProcessingState unfinishedState, Path path,
                                               String deploymentId) throws IOException {
        boolean carrySnapshot = Files.exists(unfinishedDir.resolve(ROLLBACK_SNAPSHOT_FILE));
        removeOutdatedDeploymentDirectory(path);

        logger.atInfo().kv(DIRECTORY_LOG_KEY, path).kv(DEPLOYMENT_ID_LOG_KEY, deploymentId)
                .kv(LINK_LOG_KEY, ongoingDir).log("Create work directory for new deployment");
        Utils.createPaths(path);
        try {
            if (carrySnapshot) {
                carryRollbackSnapshot(unfinishedDir.resolve(ROLLBACK_SNAPSHOT_FILE),
                        path.resolve(ROLLBACK_SNAPSHOT_FILE));
                // Recorded so that, until this deployment reaches a verified result, a failure of it keeps the
                // carried snapshot as the baseline instead of letting the next deployment snapshot live config.
                ProcessingState carried = new ProcessingState();
                carried.setRollbackSnapshotCarried(true);
                writeProcessingState(path, carried);
            }
            switchOngoingLink(path);
        } catch (IOException e) {
            // The unfinished deployment's directory is untouched, and the ongoing link still names it, or, if moving
            // the links failed both ways, the displaced link does (switchOngoingLink removes the replacement link
            // then, so a recovery cannot move onto what this delete leaves). Remove the new directory so nothing
            // half-prepared is left behind.
            try {
                deleteRecursively(path);
            } catch (IOException cleanupException) {
                e.addSuppressed(cleanupException);
            }
            throw e;
        }

        if (TerminalOutcome.FAILED_UNVERIFIED.equals(unfinishedState.getTerminalOutcome())) {
            // Its failure was reported; keep its directory for troubleshooting, as any reported failure's is kept.
            keepAsLastFailedDeployment(unfinishedDir);
            return path;
        }
        logger.atInfo().kv(DIRECTORY_LOG_KEY, unfinishedDir).log("Clean up the superseded unfinished deployment");
        try {
            deleteRecursively(unfinishedDir);
        } catch (IOException e) {
            logger.atError().kv(DIRECTORY_LOG_KEY, unfinishedDir).log("Unable to clean up previous deployments", e);
        }
        return path;
    }

    /**
     * Keep the directory of a deployment that is no longer on the ongoing link as the last finished one, cleaning up
     * earlier ones, the way {@link #persistLastFailedDeployment} does for the ongoing deployment.
     */
    private void keepAsLastFailedDeployment(Path deploymentDir) {
        logger.atInfo().kv(LINK_LOG_KEY, previousFailureDir).kv(DIRECTORY_LOG_KEY, deploymentDir)
                .log("Persist link to last deployment");
        try {
            cleanupPreviousDeployments(previousSuccessDir);
            cleanupPreviousDeployments(previousFailureDir);
            Files.createSymbolicLink(previousFailureDir, deploymentDir);
        } catch (IOException e) {
            logger.atError().log("Unable to preserve artifacts from the last deployment", e);
        }
    }

    /**
     * Fail unless the unfinished deployment in this directory has a rollback snapshot that can be carried, or
     * never applied anything that one would have to undo.
     *
     * @param sameDeployment whether the same deployment is being processed again, rather than superseded
     */
    private void requireUsableRollbackSnapshot(Path deploymentDir, ProcessingState state, boolean sameDeployment)
            throws IOException {
        Path snapshot = deploymentDir.resolve(ROLLBACK_SNAPSHOT_FILE);
        if (Files.exists(snapshot)) {
            // A transaction log that does not validate would be applied only in part, so it cannot be a rollback
            // target; failing here keeps the deployment from snapshotting live configuration instead.
            if (!isValidTransactionLog(snapshot)) {
                throw new UnusableRollbackSnapshotException("The rollback snapshot of the unfinished deployment at "
                        + snapshot + " is not a valid transaction log", sameDeployment, state);
            }
            return;
        }
        if (state.attemptsMade() > 0) {
            // An attempt is counted only once its snapshot validated, so a counted attempt without one means the
            // snapshot was lost after that attempt was applied: nothing can roll that attempt back any more.
            throw new UnusableRollbackSnapshotException("The unfinished deployment at " + deploymentDir
                    + " applied " + state.attemptsMade() + " attempt(s) but has no rollback snapshot", sameDeployment,
                    state);
        }
        if (Boolean.TRUE.equals(state.getRollbackSnapshotCarried())) {
            // It carried an unfinished deployment's verified snapshot forward, and live configuration may still hold
            // that deployment's unverified changes: without the snapshot nothing tells the verified configuration
            // apart, so a snapshot of live configuration must not take its place.
            throw new UnusableRollbackSnapshotException("The unfinished deployment at " + deploymentDir
                    + " carried a rollback snapshot forward that is no longer there", sameDeployment, state);
        }
        logger.atInfo().kv(DIRECTORY_LOG_KEY, deploymentDir)
                .log("The unfinished deployment has neither a rollback snapshot nor a counted attempt, so there is no "
                        + "snapshot to carry forward");
    }

    /**
     * An unfinished deployment whose rollback snapshot is unusable can no longer protect anything, and left on the
     * ongoing link it would fail every later deployment the same way, including one that fixes the device. Move it
     * off the link on purpose, keeping its directory as evidence like any finished deployment's, and record it as
     * set aside so that nothing later mistakes it for a deployment whose failure was reported.
     */
    private void setAsideUnfinishedDeployment(Path unfinishedDir, ProcessingState state, IOException reason) {
        logger.atError().kv(DIRECTORY_LOG_KEY, unfinishedDir).setCause(reason)
                .log("The rollback snapshot of the unfinished deployment is unusable. Setting that deployment aside "
                        + "so it does not block later deployments. The device's configuration may include its "
                        + "unverified changes");
        // A failure that was reported but kept on the ongoing link for its snapshot stays recorded as reported, and so
        // does a cancel or an outcome a newer nucleus recorded; only a deployment that never reached a terminal status
        // is recorded as set aside.
        TerminalOutcome outcome = state.getTerminalOutcome();
        if (outcome == null) {
            state.setTerminalOutcome(TerminalOutcome.ABANDONED);
        } else if (TerminalOutcome.FAILED_UNVERIFIED.equals(outcome)) {
            state.setTerminalOutcome(TerminalOutcome.FAILED);
        }
        try {
            writeProcessingState(unfinishedDir, state);
        } catch (IOException e) {
            logger.atWarn().kv(DIRECTORY_LOG_KEY, unfinishedDir)
                    .log("Unable to record that the unfinished deployment was set aside", e);
        }
        persistPointerToLastFinishedDeployment(previousFailureDir);
    }

    private void removeOutdatedDeploymentDirectory(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        logger.atWarn().kv(DIRECTORY_LOG_KEY, path)
                .log("Deployment directory already exists. Clean up outdated artifacts and create new");
        try {
            deleteRecursively(path);
        } catch (IOException e) {
            // Leftovers the new deployment overwrites do no harm, but a rollback snapshot or an attempt record would
            // be taken as the new deployment's own: a stale rollback target and a stale count and outcome. Remove
            // those on their own, and fail only if that is not possible either.
            List<String> staleState = new ArrayList<>();
            List<IOException> stateFailures = new ArrayList<>();
            for (String name : STATE_FILES) {
                try {
                    deleteRecursively(path.resolve(name));
                } catch (IOException stateFailure) {
                    staleState.add(name);
                    stateFailures.add(stateFailure);
                }
            }
            if (!staleState.isEmpty()) {
                IOException stale = new IOException("Unable to remove " + staleState + " left in " + path
                        + " by an earlier deployment with the same ID", e);
                stateFailures.stream().filter(failure -> failure != e).forEach(stale::addSuppressed);
                throw stale;
            }
            logger.atError().log("Failed to clean up outdated deployment artifacts. Ignoring", e);
        }
        cleanupPointersIfExist(path);
    }

    private void carryRollbackSnapshot(Path source, Path destination) throws IOException {
        checkNotInterrupted("carrying the rollback snapshot forward");
        try {
            try (CommitableFile out = CommitableFile.abandonOnClose(destination)) {
                writeCarriedSnapshot(source, out);
                out.commit();
            }
        } catch (IOException e) {
            checkNotInterrupted("carrying the rollback snapshot forward", e);
            // The source validated, so this is about writing the copy, e.g. a full disk: the unfinished deployment
            // and its snapshot are kept for the next attempt rather than treated as unusable.
            throw new IOException("Unable to carry the rollback snapshot at " + source + " forward to "
                    + destination, e);
        }
        // Also catches a commit whose rename failed, which CommitableFile logs rather than throws.
        if (!isValidTransactionLog(destination)) {
            throw new IOException("The rollback snapshot carried forward to " + destination + " could not be "
                    + "validated");
        }
        logger.atInfo().kv("from", source).kv("to", destination)
                .log("Carried the rollback snapshot of an unfinished deployment forward to the next deployment");
    }

    private static boolean isValidTransactionLog(Path transactionLog) {
        return ConfigurationReader.validateTlog(transactionLog);
    }

    /**
     * Writes through a channel fail on an interrupted thread, which on the deployment thread means the nucleus is
     * stopping. Such a failure says nothing about the files, so it is reported as an interruption rather than as
     * corrupt or unusable state.
     */
    private static void checkNotInterrupted(String action) throws InterruptedIOException {
        checkNotInterrupted(action, null);
    }

    private static void checkNotInterrupted(String action, IOException failure) throws InterruptedIOException {
        if (failure instanceof InterruptedIOException) {
            throw (InterruptedIOException) failure;
        }
        if (Thread.currentThread().isInterrupted()) {
            InterruptedIOException interrupted = new InterruptedIOException("Interrupted while " + action);
            if (failure != null) {
                interrupted.initCause(failure);
            }
            throw interrupted;
        }
    }

    // Package-private so a test can make the copy fail.
    void writeCarriedSnapshot(Path source, OutputStream destination) throws IOException {
        Files.copy(source, destination);
    }

    // Package-private so a test can make deleting a directory fail, as an open handle does on Windows.
    void deleteRecursively(Path directory) throws IOException {
        Utils.deleteFileRecursively(directory.toFile());
    }

    /**
     * Rename a file or a link (the link itself, not what it names). An atomic rename replaces an existing target in
     * one step; a plain one fails if the target exists.
     */
    // Package-private so a test can make a rename fail: on Windows, renaming a link over a link to a directory always
    // fails, and renaming a file another process holds open can.
    void rename(Path source, Path target, boolean atomic) throws IOException {
        if (atomic) {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } else {
            Files.move(source, target);
        }
    }

    /**
     * Point the ongoing link at a new directory. The replacement link is created first and renamed over the old
     * one, which replaces it in a single step, so a crash leaves the link naming one deployment or the other.
     *
     * <p>A platform that cannot rename a link over a link to a directory (Windows) gets a two-step switch instead,
     * and every step is a rename, so at every point one of the links still names a deployment directory: the old
     * ongoing link is renamed aside, the replacement renamed in, and only then the old one removed. If the second
     * rename fails the old link is renamed back. {@link #recoverInterruptedLinkSwitch} finishes or undoes a switch
     * the nucleus stopped in the middle of.
     */
    private void switchOngoingLink(Path directory) throws IOException {
        Path temporaryLink = deploymentsDir.resolve(TEMPORARY_LINK_PREFIX + UUID.randomUUID());
        Files.createSymbolicLink(temporaryLink, directory);
        try {
            rename(temporaryLink, ongoingDir, true);
            return;
        } catch (IOException e) {
            // Not every platform can replace a link to a directory in one step. Replace it in two instead.
            logger.atDebug().kv(LINK_LOG_KEY, ongoingDir).setCause(e)
                    .log("Unable to replace the ongoing link in one step; replacing it in two");
        }
        Path displacedLink = deploymentsDir.resolve(DISPLACED_LINK_PREFIX + UUID.randomUUID());
        try {
            rename(ongoingDir, displacedLink, false);
        } catch (IOException e) {
            // Nothing moved: the ongoing link still names the unfinished deployment.
            deleteLinkKeepingFailure(temporaryLink, e);
            throw e;
        }
        try {
            rename(temporaryLink, ongoingDir, false);
        } catch (IOException e) {
            try {
                rename(displacedLink, ongoingDir, false);
            } catch (IOException restoreFailure) {
                // The displaced link is left in place, so the next deployment or launch can undo the switch. The
                // replacement link is removed, so that undoing it is all a recovery can do: the caller deletes the new
                // directory, and a recovery that followed a link to whatever that delete leaves could make a
                // directory without the snapshot the ongoing one.
                e.addSuppressed(restoreFailure);
                deleteLinkKeepingFailure(temporaryLink, e);
                throw e;
            }
            deleteLinkKeepingFailure(temporaryLink, e);
            throw e;
        }
        try {
            Files.deleteIfExists(displacedLink);
        } catch (IOException e) {
            // The switch is complete; the stale link is removed by the next deployment.
            logger.atWarn().kv(LINK_LOG_KEY, displacedLink).log("Unable to remove the replaced ongoing link", e);
        }
    }

    /**
     * Delete a link that is no longer needed after a failure, without letting a failure to delete it hide the
     * failure being reported.
     */
    private static void deleteLinkKeepingFailure(Path link, IOException failure) {
        try {
            Files.deleteIfExists(link);
        } catch (IOException e) {
            failure.addSuppressed(e);
        }
    }

    /**
     * Finish or undo a switch of the ongoing link that the nucleus stopped in the middle of, and remove the links
     * a switch leaves behind. Without an ongoing link, a replacement link that still names a directory is what the
     * switch was moving in, so it is rolled forward; failing that, the displaced old link is rolled back. With an
     * ongoing link, the switch either completed or never replaced it, and the leftover links are stale; they are
     * deleted without following them.
     */
    private void recoverInterruptedLinkSwitch() {
        if (!Files.isDirectory(deploymentsDir)) {
            return;
        }
        List<Path> temporaryLinks = new ArrayList<>();
        List<Path> displacedLinks = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(deploymentsDir, entry -> {
            String name = String.valueOf(entry.getFileName());
            return name.startsWith(TEMPORARY_LINK_PREFIX) || name.startsWith(DISPLACED_LINK_PREFIX);
        })) {
            for (Path entry : entries) {
                if (String.valueOf(entry.getFileName()).startsWith(TEMPORARY_LINK_PREFIX)) {
                    temporaryLinks.add(entry);
                } else {
                    displacedLinks.add(entry);
                }
            }
        } catch (IOException e) {
            logger.atWarn().log("Unable to look for links left behind by switching the ongoing link", e);
            return;
        }
        if (temporaryLinks.isEmpty() && displacedLinks.isEmpty()) {
            return;
        }
        if (!Files.exists(ongoingDir, LinkOption.NOFOLLOW_LINKS)) {
            // A replacement link is created only once its directory holds what the switch carries (a validated copy
            // of the snapshot, whenever there is one to carry), so it is preferred; the displaced link names the
            // deployment it was replacing, which still holds its own. A switch parks the old link aside before
            // moving the new one in, and removes the old one only once the new one is in place, so a replacement
            // link without a displaced link is not from a switch in progress. Even with one, the directory a
            // replacement link names may have lost what it held since, e.g. to a delete cut short: it stands in for
            // the displaced deployment only if it still holds that deployment's snapshot whenever the displaced
            // deployment has one. Otherwise the switch is undone.
            Path displaced = onlyLinkToDirectory(displacedLinks);
            Path recovered = displaced == null ? null : onlyLinkToDirectory(temporaryLinks.stream()
                    .filter(link -> holdsWhatTheSwitchCarries(link, displaced)).collect(Collectors.toList()));
            if (recovered == null) {
                recovered = displaced;
            }
            if (recovered == null) {
                logger.atError().kv("switchLinks", temporaryLinks).kv("displacedLinks", displacedLinks)
                        .log("The ongoing link is missing and the links an interrupted switch left behind do not "
                                + "name exactly one deployment directory. Leaving them in place");
                return;
            }
            try {
                rename(recovered, ongoingDir, false);
                logger.atWarn().kv(LINK_LOG_KEY, recovered)
                        .log(temporaryLinks.contains(recovered)
                                ? "Completed a switch of the ongoing link that was interrupted"
                                : "Undid a switch of the ongoing link that was interrupted");
            } catch (IOException e) {
                logger.atError().kv(LINK_LOG_KEY, recovered)
                        .log("Unable to restore the ongoing link from a link an interrupted switch left behind", e);
                return;
            }
        }
        for (Path entry : temporaryLinks) {
            removeStaleEntry(entry);
        }
        for (Path entry : displacedLinks) {
            removeStaleEntry(entry);
        }
    }

    /**
     * The one link among these that names an existing directory, or null when none or several do.
     */
    private static Path onlyLinkToDirectory(List<Path> links) {
        List<Path> toDirectories = links.stream().filter(link -> Files.isSymbolicLink(link) && Files.isDirectory(link))
                .collect(Collectors.toList());
        return toDirectories.size() == 1 ? toDirectories.get(0) : null;
    }

    /**
     * Whether the directory a replacement link names holds what a switch from the displaced deployment carries: a
     * rollback snapshot that validates, or none when the displaced deployment had none to carry.
     */
    private static boolean holdsWhatTheSwitchCarries(Path replacementLink, Path displacedLink) {
        Path snapshot = replacementLink.resolve(ROLLBACK_SNAPSHOT_FILE);
        if (Files.exists(snapshot)) {
            return isValidTransactionLog(snapshot);
        }
        return !Files.exists(displacedLink.resolve(ROLLBACK_SNAPSHOT_FILE));
    }

    private static void removeStaleEntry(Path entry) {
        if (!Files.exists(entry, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        logger.atInfo().kv(FILE_LOG_KEY, entry).log("Removing a temporary file left behind by an earlier deployment");
        try {
            deleteEntry(entry);
        } catch (IOException e) {
            logger.atWarn().kv(FILE_LOG_KEY, entry)
                    .log("Unable to remove temporary files left behind by earlier deployments", e);
        }
    }

    /**
     * Delete a file, a link (and not what it points to), or a directory and its contents.
     */
    private static void deleteEntry(Path entry) throws IOException {
        if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
            Utils.deleteFileRecursively(entry.toFile());
        } else {
            Files.deleteIfExists(entry);
        }
    }

    /**
     * The directory of the deployment the ongoing link names, or null when there is no ongoing link.
     */
    private Path resolveOngoingDirectory() throws IOException {
        if (!Files.isSymbolicLink(ongoingDir)) {
            return null;
        }
        return Files.readSymbolicLink(ongoingDir).toAbsolutePath();
    }

    private void completeFinishedDeployment(TerminalOutcome outcome) {
        logger.atInfo().kv("outcome", outcome)
                .log("Found a deployment whose terminal status was recorded but whose directory was not yet moved "
                        + "off the ongoing link. Finishing that now");
        persistPointerToLastFinishedDeployment(
                TerminalOutcome.SUCCEEDED.equals(outcome) ? previousSuccessDir : previousFailureDir);
    }

    /**
     * Record that the ongoing deployment has been applied, and return how many times that has now
     * happened. The count follows the deployment across re-processing (see
     * {@link #createNewDeploymentDirectory}), so it counts attempts that never reached a terminal state —
     * for example because the nucleus was interrupted mid-activation.
     *
     * <p>The record is written atomically and read back, and a failure throws rather than being ignored: an
     * attempt that is not durably counted cannot be capped, so the caller must not apply the deployment. The
     * ongoing link must name this deployment: an activation that a superseding deployment overtook must not
     * count itself in that deployment's record.
     *
     * @param deploymentId      the deployment being applied
     * @param attemptLimit      how many attempts this deployment is allowed, or below 1 for no limit. Only the
     *                          limit given with the first attempt is kept, see {@link ProcessingState}
     * @param rollbackOnFailure whether a failure of this deployment rolls it back, which is what decides whether
     *                          a launch restores the device once the attempts are used up
     * @return the attempt number, starting at 1
     * @throws IOException on I/O errors, or if this deployment is not the ongoing one
     */
    public synchronized int recordProcessingAttempt(String deploymentId, int attemptLimit, boolean rollbackOnFailure)
            throws IOException {
        Path deploymentDir = requireOngoingDirectoryOf(deploymentId);
        ProcessingState state = readProcessingState(deploymentDir);
        state.setProcessingAttempts(state.attemptsMade() + 1);
        if (state.getProcessingAttemptLimit() == null) {
            state.setProcessingAttemptLimit(attemptLimit);
        }
        state.setRollbackOnFailure(rollbackOnFailure);
        writeProcessingState(deploymentDir, state);
        return state.attemptsMade();
    }

    /**
     * The processing state of this deployment if the ongoing link names it, or null.
     *
     * @param deploymentId Deployment id
     * @return the processing state recorded for this deployment, or null when it is not the ongoing deployment
     */
    ProcessingState findProcessingState(String deploymentId) {
        Path deploymentDir = findOngoingDirectoryOf(deploymentId);
        return deploymentDir == null ? null : readProcessingState(deploymentDir);
    }

    /**
     * This deployment's processing state if it is the ongoing deployment and has used up its attempts, or null if
     * it has not or has no limit. Once it has, applying it again would only repeat an attempt that never
     * completes, so the caller should report it failed instead.
     *
     * @param deploymentId Deployment id
     * @return the processing state of this deployment if it has used up its attempts, or null
     */
    ProcessingState exhaustedProcessingState(String deploymentId) {
        ProcessingState state = findProcessingState(deploymentId);
        return state != null && state.hasUsedUpAttempts() ? state : null;
    }

    /**
     * The processing state of this deployment if it is the one most recently failed for using up its processing
     * attempts, or null. Processing a re-delivery that was already in flight when that failure was reported would
     * hand the same deployment a fresh allowance and re-apply the configuration the device was just restored
     * from. Any other deployment reaching a terminal state clears the link and record this reads, so a new
     * revision is unaffected.
     *
     * <p>A failed deployment is usually on the last-failed link, but one recorded as
     * {@link TerminalOutcome#FAILED_UNVERIFIED} stays on the ongoing link. A deployment set aside
     * ({@link TerminalOutcome#ABANDONED}) was never reported, and a cancelled one ({@link TerminalOutcome#CANCELLED})
     * was not failed, so neither is refused.
     *
     * @param deploymentId Deployment id
     * @return the processing state if this deployment already used up its processing attempts, or null
     */
    ProcessingState findExhaustedFailure(String deploymentId) {
        try {
            if (Files.isSymbolicLink(ongoingDir)) {
                Path ongoing = Files.readSymbolicLink(ongoingDir).toAbsolutePath();
                if (isDirectoryOf(ongoing, deploymentId)) {
                    ProcessingState state = readProcessingState(ongoing);
                    boolean reported = TerminalOutcome.FAILED.equals(state.getTerminalOutcome())
                            || TerminalOutcome.FAILED_UNVERIFIED.equals(state.getTerminalOutcome());
                    return reported && state.hasUsedUpAttempts() ? state : null;
                }
            }
            if (!Files.isSymbolicLink(previousFailureDir)) {
                return null;
            }
            Path failedDir = Files.readSymbolicLink(previousFailureDir).toAbsolutePath();
            if (!isDirectoryOf(failedDir, deploymentId)) {
                return null;
            }
            ProcessingState state = readProcessingState(failedDir);
            return state.hasUsedUpAttempts() && !TerminalOutcome.ABANDONED.equals(state.getTerminalOutcome())
                    && !TerminalOutcome.CANCELLED.equals(state.getTerminalOutcome()) ? state : null;
        } catch (IOException e) {
            logger.atError().kv(DEPLOYMENT_ID_LOG_KEY, deploymentId)
                    .log("Unable to tell whether this deployment already used up its attempts", e);
            return null;
        }
    }

    /**
     * Record that this deployment reached a terminal status. Call it before the status is published:
     * the directory moves off the ongoing link only afterwards, and a deployment found on that link without this
     * record is treated as unfinished, so the next deployment would carry its stale rollback snapshot forward and
     * a launch could re-process or restore it. Nothing is recorded unless the ongoing link names this deployment.
     *
     * <p>{@link TerminalOutcome#FAILED_UNVERIFIED} is recorded only if the deployment's rollback snapshot still
     * validates, since carrying it forward is the point of it; otherwise the failure is recorded as
     * {@link TerminalOutcome#FAILED}.
     *
     * <p>The outcome decided is returned even if writing the record fails, and is what
     * {@link #persistLastFailedDeployment} acts on: a failure to record it is logged rather than thrown, because the
     * status must still be published, and the directory must still be kept or moved as the outcome says.
     *
     * @param deploymentId the deployment that reached a terminal status
     * @param outcome      the terminal status it reached
     * @return the outcome decided for it: the one given, or {@link TerminalOutcome#FAILED} in place of a
     *     {@link TerminalOutcome#FAILED_UNVERIFIED} whose snapshot no longer validates
     */
    public synchronized TerminalOutcome recordTerminalStatus(String deploymentId, TerminalOutcome outcome) {
        TerminalOutcome recorded = outcome;
        try {
            Path deploymentDir = findOngoingDirectoryOf(deploymentId);
            if (deploymentDir == null || !Files.isDirectory(deploymentDir)) {
                return recorded;
            }
            if (TerminalOutcome.FAILED_UNVERIFIED.equals(outcome)
                    && !isValidTransactionLog(deploymentDir.resolve(ROLLBACK_SNAPSHOT_FILE))) {
                logger.atWarn().kv(DIRECTORY_LOG_KEY, deploymentDir)
                        .log("The failed deployment's rollback snapshot is missing or invalid, so it cannot remain "
                                + "the baseline for the next deployment");
                recorded = TerminalOutcome.FAILED;
            }
            ProcessingState state = readProcessingState(deploymentDir);
            state.setTerminalOutcome(recorded);
            writeProcessingState(deploymentDir, state);
        } catch (InterruptedIOException e) {
            logger.atWarn().kv("reason", e.getMessage()).log("Interrupted before recording that the deployment "
                    + "reached a terminal status. If the nucleus stops before the deployment directory is moved, it "
                    + "will be treated as unfinished");
        } catch (IOException e) {
            logger.atError().log("Unable to record that the deployment reached a terminal status. If the nucleus "
                    + "stops before the deployment directory is moved, it will be treated as unfinished", e);
        }
        return recorded;
    }

    /**
     * Inspect, before the launch loads any configuration, the deployment the ongoing link names. A deployment
     * whose terminal status was recorded is finished, and moving its directory off the link is completed here.
     * An unfinished deployment that has used up its attempts is restored from its rollback snapshot only when
     * its failure handling rolls back, no explicit configuration was given, and the snapshot validates; the
     * decision is made once per deployment, and {@link #recordLaunchRecovery} records it once applied.
     *
     * @param explicitConfigurationProvided whether Greengrass was started with -i/--config or --init-config,
     *                                      which takes precedence over restoring the snapshot
     * @return what the launch found and how it should deal with it
     * @throws IOException if the ongoing link cannot be read
     */
    public LaunchAssessment assessUnfinishedDeploymentAtLaunch(boolean explicitConfigurationProvided)
            throws IOException {
        recoverInterruptedLinkSwitch();
        Path unfinishedDir = resolveOngoingDirectory();
        if (unfinishedDir == null) {
            return LaunchAssessment.NO_UNFINISHED_DEPLOYMENT;
        }
        ProcessingState state = readProcessingState(unfinishedDir);
        if (state.getTerminalOutcome() != null && state.getTerminalOutcome().keepsRollbackBaseline()) {
            // Reported failed or cancelled (or settled by a newer nucleus); it stays on the ongoing link only so the
            // next deployment carries its snapshot, and is neither re-processed nor restored.
            return LaunchAssessment.NO_UNFINISHED_DEPLOYMENT;
        }
        if (state.getTerminalOutcome() != null) {
            completeFinishedDeployment(state.getTerminalOutcome());
            return LaunchAssessment.NO_UNFINISHED_DEPLOYMENT;
        }
        if (!state.hasUsedUpAttempts() || state.getLaunchRecovery() != null) {
            // Attempts remain, or an earlier launch already dealt with this deployment: restoring again would
            // erase whatever changed since.
            return new LaunchAssessment(true, null, null);
        }
        if (!Boolean.TRUE.equals(state.getRollbackOnFailure())) {
            logger.atWarn().kv(DIRECTORY_LOG_KEY, unfinishedDir).kv("attempts", state.attemptsMade())
                    .log("Deployment was interrupted on every allowed attempt. Its failure handling does not roll "
                            + "back, so the device keeps the configuration the deployment applied");
            return new LaunchAssessment(true, LaunchRecovery.ROLLBACK_NOT_REQUESTED, null);
        }
        Path snapshot = unfinishedDir.resolve(ROLLBACK_SNAPSHOT_FILE);
        if (explicitConfigurationProvided) {
            logger.atWarn().kv(DIRECTORY_LOG_KEY, unfinishedDir).kv("attempts", state.attemptsMade())
                    .kv(ROLLBACK_CONFIG_FILE_LOG_KEY, snapshot)
                    .log("Deployment was interrupted on every allowed attempt, but Greengrass was started with an "
                            + "explicit configuration, which takes precedence over restoring the configuration from "
                            + "before the deployment");
            return new LaunchAssessment(true, LaunchRecovery.EXPLICIT_CONFIGURATION, null);
        }
        if (!ConfigurationReader.validateTlog(snapshot)) {
            logger.atError().kv(ROLLBACK_CONFIG_FILE_LOG_KEY, snapshot)
                    .log("Deployment was interrupted on every allowed attempt, but its rollback configuration is "
                            + "missing or invalid, so it is not applied. Proceed as default");
            return new LaunchAssessment(true, LaunchRecovery.ROLLBACK_SNAPSHOT_UNUSABLE, null);
        }
        logger.atWarn().kv(ROLLBACK_CONFIG_FILE_LOG_KEY, snapshot).kv("attempts", state.attemptsMade())
                .log("Deployment was interrupted on every allowed attempt. Restoring the configuration from before "
                        + "it was applied");
        return new LaunchAssessment(true, LaunchRecovery.RESTORED, snapshot);
    }

    /**
     * Record how the launch dealt with an unfinished deployment that used up its attempts, once that has taken
     * effect, so no later launch decides again. In particular a restore happens once: repeating it would erase
     * everything written since.
     *
     * @param recovery how the launch dealt with the deployment
     * @throws IOException on I/O errors
     */
    public synchronized void recordLaunchRecovery(LaunchRecovery recovery) throws IOException {
        Path deploymentDir = resolveOngoingDirectory();
        if (deploymentDir == null) {
            return;
        }
        ProcessingState state = readProcessingState(deploymentDir);
        state.setLaunchRecovery(recovery);
        writeProcessingState(deploymentDir, state);
    }

    /**
     * Replace the transaction log the launch is about to load with a rollback snapshot, all or nothing: the
     * snapshot is copied next to the log and verified there, and only then renamed into place, keeping the
     * replaced log as its backup the way {@link CommitableFile} does. Any failure leaves the configuration the
     * launch loads as it was, so the launch boots it instead, and the rollback snapshot itself is never touched. The
     * one change that can precede a failure is settling an interrupted truncation's leftover the way the launch
     * itself would; if that cannot be done, nothing is replaced, since the launch would undo the restore with it.
     *
     * <p>True is returned only when nothing is left that would make the launch load anything but the restored log.
     *
     * <p>Unlike loading the snapshot as a configuration override, which makes it durable only by rewriting the log
     * once it is in memory, this cannot fail half way: a disk that is full or read-only fails the copy before
     * anything is loaded, rather than failing the launch every time it tries.
     *
     * @param rollbackSnapshot the snapshot to restore, already validated
     * @param transactionLog   the transaction log the launch loads
     * @return true if the transaction log now holds exactly the snapshot
     */
    public boolean restoreRollbackSnapshot(Path rollbackSnapshot, Path transactionLog) {
        Path newVersion = CommitableFile.getNewFile(transactionLog);
        try {
            try (OutputStream out = Files.newOutputStream(newVersion, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC)) {
                writeCarriedSnapshot(rollbackSnapshot, out);
            }
            if (!FileUtils.contentEquals(rollbackSnapshot.toFile(), newVersion.toFile())) {
                throw new IOException("The copy of " + rollbackSnapshot + " at " + newVersion + " differs from it");
            }
        } catch (IOException e) {
            logger.atError().kv(FILE_LOG_KEY, transactionLog).kv(ROLLBACK_CONFIG_FILE_LOG_KEY, rollbackSnapshot)
                    .log("Unable to copy the rollback snapshot next to the transaction log", e);
            deleteQuietly(newVersion);
            return false;
        }
        // A log truncation interrupted by the last shutdown leaves the log it was truncating as ".old", which the
        // launch takes for the log to load: it moves ".old" back over the log before reading it, and would so undo
        // a restore made over it. Settle it first, the way the launch would, so that nothing is left to undo the
        // restore and the log replaced below, and kept as its backup, is the one the launch would have loaded. If it
        // cannot be moved, nothing is replaced: reporting a restore that the launch then undoes would claim the
        // device was restored while it boots the configuration being backed out.
        Path truncationLeftover = ConfigurationWriter.getOldTlogPath(transactionLog);
        if (Files.exists(truncationLeftover, LinkOption.NOFOLLOW_LINKS)) {
            try {
                rename(truncationLeftover, transactionLog, true);
            } catch (IOException e) {
                logger.atError().kv(FILE_LOG_KEY, truncationLeftover)
                        .log("Unable to settle an interrupted truncation of the transaction log, which the launch "
                                + "would move back over a restored one. Not restoring the rollback snapshot", e);
                deleteQuietly(newVersion);
                return false;
            }
        }
        Path backup = CommitableFile.getBackupFile(transactionLog);
        // A log missing here is what a crash between the two renames below leaves: its backup is already in place.
        boolean logReplaced = Files.exists(transactionLog);
        try {
            if (logReplaced) {
                rename(transactionLog, backup, true);
            }
        } catch (IOException e) {
            logger.atError().kv(FILE_LOG_KEY, transactionLog)
                    .log("Unable to set the transaction log aside to restore the rollback snapshot", e);
            deleteQuietly(newVersion);
            return false;
        }
        try {
            rename(newVersion, transactionLog, true);
        } catch (IOException e) {
            logger.atError().kv(FILE_LOG_KEY, transactionLog)
                    .log("Unable to move the rollback snapshot into place. Putting the transaction log back", e);
            if (logReplaced) {
                try {
                    rename(backup, transactionLog, true);
                } catch (IOException restoreFailure) {
                    // The launch falls back to the backup by itself when the log is missing.
                    logger.atError().kv(FILE_LOG_KEY, transactionLog)
                            .log("Unable to put the transaction log back. The launch loads its backup", restoreFailure);
                }
            }
            deleteQuietly(newVersion);
            return false;
        }
        logger.atWarn().kv(FILE_LOG_KEY, transactionLog).kv(ROLLBACK_CONFIG_FILE_LOG_KEY, rollbackSnapshot)
                .kv("backup", backup).log("Restored the transaction log from the rollback snapshot");
        return true;
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            logger.atWarn().kv(FILE_LOG_KEY, file).log("Unable to remove file", e);
        }
    }

    /**
     * Read the record the way CommitableReader reads a committed file, falling back to the previous version when
     * the current one cannot be read and reverting to it. Jackson reads a file through a FileInputStream, which an
     * interrupt cannot fail, unlike CommitableReader's channel: the deployment thread is interrupted when the
     * nucleus stops, and a read failing for that reason would say nothing about the record.
     */
    private ProcessingState readProcessingState(Path deploymentDir) {
        Path file = deploymentDir.resolve(PROCESSING_STATE_FILE);
        Path backup = CommitableFile.getBackupFile(file);
        if (!Files.exists(file) && !Files.exists(backup)) {
            // Never applied by a nucleus that keeps this record, so no attempts have been made.
            return new ProcessingState();
        }
        IOException failure;
        try {
            return readProcessingStateFrom(file);
        } catch (IOException e) {
            failure = e;
        }
        if (Files.exists(backup)) {
            logger.atWarn().kv(FILE_LOG_KEY, file).kv("backup", backup)
                    .log("Failed to read the processing state. Try with backup next", failure);
            ProcessingState state = null;
            try {
                state = readProcessingStateFrom(backup);
            } catch (IOException e) {
                failure.addSuppressed(e);
            }
            if (state != null) {
                revertToBackup(backup, file);
                return state;
            }
        }
        // Neither the record nor its backup can be read. Start the count again under the default limit:
        // the next attempt rewrites the record, so the cap applies again rather than switching off.
        logger.atWarn().kv(FILE_LOG_KEY, file).kv("attemptLimit", DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS)
                .log("Unreadable processing state. Counting attempts from zero under the default attempt limit",
                        failure);
        return new ProcessingState();
    }

    private static void revertToBackup(Path backup, Path file) {
        try {
            Files.move(backup, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.atWarn().kv(FILE_LOG_KEY, file).kv("backup", backup)
                    .log("Unable to revert the processing state to its backup", e);
        }
    }

    private static ProcessingState readProcessingStateFrom(Path file) throws IOException {
        ProcessingState state = PROCESSING_STATE_MAPPER.readValue(file.toFile(), ProcessingState.class);
        return state == null ? new ProcessingState() : state;
    }

    private void writeProcessingState(Path deploymentDir, ProcessingState state) throws IOException {
        Path file = deploymentDir.resolve(PROCESSING_STATE_FILE);
        // Serialize before touching the file, then replace it atomically: a crash leaves either the previous
        // record or this one.
        byte[] content = PROCESSING_STATE_MAPPER.writeValueAsBytes(state);
        checkNotInterrupted("writing " + file);
        try {
            commitProcessingState(file, content);
            // CommitableFile logs rather than throws when a rename fails, so read the record back.
            ProcessingState persisted = PROCESSING_STATE_MAPPER.readValue(file.toFile(), ProcessingState.class);
            if (!state.equals(persisted)) {
                throw new IOException("Processing state was not persisted to " + file);
            }
        } catch (IOException e) {
            checkNotInterrupted("writing " + file, e);
            throw e;
        }
    }

    // Package-private so a test can make the commit leave the previous record in place, as CommitableFile does when a
    // rename fails: it logs rather than throws.
    void commitProcessingState(Path file, byte[] content) throws IOException {
        try (CommitableFile out = CommitableFile.abandonOnClose(file)) {
            out.write(content);
            out.commit();
        }
    }

    /**
     * The component versions the rollback snapshot on the ongoing link names, by component: each component's version
     * and previous version. That snapshot is what the device may still be returned to, by a rollback of the ongoing
     * deployment, by a launch that restores it, or as the baseline the next deployment carries forward, so a cleanup
     * of stale versions must not delete them even when live configuration no longer names them. Whichever deployment
     * the ongoing link names, its snapshot is such a target.
     *
     * @return the versions the snapshot names; empty when there is no ongoing deployment or its snapshot is absent or
     *     does not validate, since nothing can return the device to such a snapshot
     * @throws IOException if the ongoing link or the snapshot cannot be read
     */
    public Map<String, Set<String>> findRollbackBaselineVersions() throws IOException {
        Path ongoingDirectory = resolveOngoingDirectory();
        if (ongoingDirectory == null) {
            return Collections.emptyMap();
        }
        Path snapshot = ongoingDirectory.resolve(ROLLBACK_SNAPSHOT_FILE);
        if (!Files.exists(snapshot) || !isValidTransactionLog(snapshot)) {
            return Collections.emptyMap();
        }
        Map<String, Set<String>> versions = new HashMap<>();
        try (Context readContext = new Context()) {
            Topics services = ConfigurationReader.createFromTLog(readContext, snapshot)
                    .findTopics(SERVICES_NAMESPACE_TOPIC);
            if (services == null) {
                return versions;
            }
            for (Node service : services) {
                if (!(service instanceof Topics)) {
                    continue;
                }
                Set<String> named = new HashSet<>();
                for (String key : Arrays.asList(VERSION_CONFIG_KEY, PREV_VERSION_CONFIG_KEY)) {
                    String version = Coerce.toString(((Topics) service).find(key));
                    if (version != null) {
                        named.add(version);
                    }
                }
                if (!named.isEmpty()) {
                    versions.put(service.getName(), named);
                }
            }
        }
        return versions;
    }

    private static String backupOf(String fileName) {
        return String.valueOf(CommitableFile.getBackupFile(Paths.get(fileName)));
    }

    /**
     * Whether a directory has the given name. A path has no file name when it is a root, so the name has
     * to be null-checked before it is compared.
     */
    private static boolean isDirectoryNamed(Path directory, String name) {
        Path fileName = directory.getFileName();
        return fileName != null && name.equals(fileName.toString());
    }

    /**
     * How a launch dealt with an unfinished deployment that had used up its processing attempts.
     */
    public enum LaunchRecovery {
        /**
         * The launch booted from the deployment's rollback snapshot.
         */
        RESTORED,
        /**
         * The deployment's failure handling does not roll back, so the device keeps what it applied.
         */
        ROLLBACK_NOT_REQUESTED,
        /**
         * Greengrass was started with an explicit configuration, which took precedence over the snapshot.
         */
        EXPLICIT_CONFIGURATION,
        /**
         * The rollback snapshot was missing or invalid, so it was not applied.
         */
        ROLLBACK_SNAPSHOT_UNUSABLE,
        /**
         * The rollback snapshot was valid, but the configuration could not be replaced with it, so the launch
         * booted the configuration on disk. The snapshot is kept.
         */
        RESTORE_FAILED,
        /**
         * Recorded by a nucleus that knows a value this one does not, and read in its place. A launch has decided
         * already, so none decides again; not knowing how that left the device, this one reports that the device may
         * still hold the deployment's unverified changes.
         */
        @JsonEnumDefaultValue
        UNRECOGNIZED
    }

    /**
     * What became of a deployment, recorded before its terminal status is published so that a crash before its
     * directory moves off the ongoing link cannot make it look unfinished.
     */
    public enum TerminalOutcome {
        SUCCEEDED,
        FAILED,
        /**
         * Failed and reported, while the device may still hold changes that were never verified: an earlier attempt
         * applied them, or the deployment carried an unfinished one's snapshot, and nothing rolled back or restored
         * them. Its rollback snapshot is still the last verified configuration, so the deployment stays on the
         * ongoing link and the next deployment carries the snapshot forward.
         */
        FAILED_UNVERIFIED,
        /**
         * Never reported: set aside because its rollback snapshot is unusable, so that it does not block every
         * later deployment. Its directory is kept as the last failed deployment's.
         */
        ABANDONED,
        /**
         * Cancelled before it reached a result: by CancelLocalDeployment, a cancelled job or a newer shadow
         * deployment. Recorded before CANCELED is published, so no launch re-processes or restores it. The cancel may
         * have landed after its changes were merged, so, as for {@link #FAILED_UNVERIFIED}, it stays on the ongoing
         * link and the next deployment carries its rollback snapshot forward.
         */
        CANCELLED,
        /**
         * Recorded by a nucleus that knows a value this one does not, and read in its place. Some terminal status was
         * reached, so the deployment is neither re-processed nor restored; not knowing what it left, this one keeps
         * its rollback snapshot as the baseline, as for {@link #FAILED_UNVERIFIED}.
         */
        @JsonEnumDefaultValue
        UNRECOGNIZED;

        /**
         * Whether a deployment that reached this outcome stays on the ongoing link for the next deployment to carry
         * its rollback snapshot forward: settled, so never re-processed or restored by a launch, while the device may
         * still hold changes of it that were never verified.
         *
         * @return true if the deployment's rollback snapshot stays the baseline for the next deployment
         */
        public boolean keepsRollbackBaseline() {
            return this == FAILED_UNVERIFIED || this == CANCELLED || this == UNRECOGNIZED;
        }
    }

    /**
     * What a launch found on the ongoing link before loading any configuration.
     */
    @Getter
    public static final class LaunchAssessment {
        static final LaunchAssessment NO_UNFINISHED_DEPLOYMENT = new LaunchAssessment(false, null, null);

        /**
         * Whether an unfinished deployment is on the ongoing link.
         */
        private final boolean unfinishedDeployment;
        /**
         * How to deal with an unfinished deployment that used up its attempts, or null when there is nothing to
         * decide: none is unfinished, its attempts remain, or an earlier launch already decided.
         */
        private final LaunchRecovery recovery;
        /**
         * The rollback snapshot to boot from when the recovery is {@link LaunchRecovery#RESTORED}.
         */
        private final Path rollbackSnapshot;

        LaunchAssessment(boolean unfinishedDeployment, LaunchRecovery recovery, Path rollbackSnapshot) {
            this.unfinishedDeployment = unfinishedDeployment;
            this.recovery = recovery;
            this.rollbackSnapshot = rollbackSnapshot;
        }

        /**
         * This assessment once restoring its rollback snapshot has failed: the launch boots the configuration on
         * disk instead, and that is what is recorded.
         *
         * @return the assessment the launch acts on
         */
        public LaunchAssessment restoreFailed() {
            return new LaunchAssessment(unfinishedDeployment, LaunchRecovery.RESTORE_FAILED, null);
        }
    }

    /**
     * What this nucleus has recorded about applying one deployment. Absent fields read as their defaults, so a
     * record written by another version of the nucleus stays readable, and none of them ever reads as "no limit".
     */
    @Getter
    @Setter
    @EqualsAndHashCode
    @ToString
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static final class ProcessingState {
        /**
         * How many times the deployment has been applied; absent means never.
         */
        private Integer processingAttempts;
        /**
         * The attempt limit is recorded next to the count rather than read when it is needed, because the
         * launch consults the count before it has loaded any configuration. A deployment is therefore judged
         * by the limit in force when it was first applied, which is also the limit its operator chose: a later
         * attempt reads live configuration that already holds the deployment's own unverified changes, which must
         * not change its cap. Absent means the default limit; below 1 means no limit, which only an explicit value
         * can say.
         */
        private Integer processingAttemptLimit;
        /**
         * Whether a failure of the deployment rolls it back. Only a deployment that would be rolled back on
         * failure is restored once its attempts are used up.
         */
        private Boolean rollbackOnFailure;
        /**
         * How a launch dealt with the deployment once it had used up its attempts; absent until one has.
         */
        private LaunchRecovery launchRecovery;
        /**
         * The terminal status the deployment reached, recorded before it is published; absent while unfinished.
         */
        private TerminalOutcome terminalOutcome;
        /**
         * Whether the rollback snapshot in the deployment's directory was carried forward from an unfinished
         * deployment rather than taken from live configuration, which may then hold changes never verified.
         */
        private Boolean rollbackSnapshotCarried;

        int attemptsMade() {
            return processingAttempts == null ? 0 : processingAttempts;
        }

        int attemptLimit() {
            return processingAttemptLimit == null ? DEFAULT_MAX_INTERRUPTED_DEPLOYMENT_ATTEMPTS
                    : processingAttemptLimit;
        }

        boolean hasUsedUpAttempts() {
            int limit = attemptLimit();
            return limit >= 1 && attemptsMade() >= limit;
        }

        /**
         * Whether live configuration may hold changes that were never verified when this deployment arrived: an
         * earlier attempt of it applied them, or it carried an unfinished deployment's snapshot.
         */
        boolean startedFromUnverifiedConfiguration() {
            return attemptsMade() > 0 || Boolean.TRUE.equals(rollbackSnapshotCarried);
        }
    }

    /**
     * The rollback snapshot of an unfinished deployment is unusable: it does not validate, or an attempt was
     * counted but the snapshot is gone. Unlike a failure to copy a valid snapshot, which is kept for the next
     * attempt, this lasts.
     */
    static final class UnusableRollbackSnapshotException extends IOException {
        private static final long serialVersionUID = 6512786340261459732L;

        /**
         * Whether the snapshot is the same deployment's, re-delivered, rather than a superseded one's.
         */
        @Getter
        private final boolean sameDeployment;
        /**
         * The processing state of the deployment whose snapshot is unusable, as read for this check, when known. For
         * the same deployment re-delivered its failure status depends on it, so it is carried here rather than read
         * again after the failure.
         */
        @Getter
        private final transient ProcessingState processingState;

        UnusableRollbackSnapshotException(String message, boolean sameDeployment) {
            this(message, sameDeployment, null);
        }

        UnusableRollbackSnapshotException(String message, boolean sameDeployment, ProcessingState state) {
            super(message);
            this.sameDeployment = sameDeployment;
            this.processingState = state;
        }
    }

    public static String getSafeFileName(String fleetConfigArn) {
        return fleetConfigArn.replace(':', '.').replace('/', '+');
    }
}
