/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment;


import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Utils;
import lombok.AccessLevel;
import lombok.Getter;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.inject.Inject;

/**
 * Deployment directory manager preserves deployment artifacts for configuration rollback workflow and troubleshooting.
 */
public class DeploymentDirectoryManager {
    static final String ROLLBACK_SNAPSHOT_FILE = "rollback_snapshot.tlog";
    static final String TARGET_CONFIG_FILE = "target_config.tlog";
    static final String BOOTSTRAP_TASK_FILE = "bootstrap_task.ser";
    static final String DEPLOYMENT_METADATA_FILE = "deployment_metadata.ser";

    private static final String PREVIOUS_SUCCESS_LINK = "previous-success";
    private static final String PREVIOUS_FAILURE_LINK = "previous-failure";
    private static final String ONGOING_DEPLOYMENT_LINK = "ongoing";
    private static final Logger logger = LogManager.getLogger(DeploymentDirectoryManager.class);
    private final Kernel kernel;

    private final Path deploymentsDir;
    @Getter(AccessLevel.MODULE)
    private final Path previousSuccessDir;
    @Getter(AccessLevel.MODULE)
    private final Path previousFailureDir;
    @Getter(AccessLevel.MODULE)
    private final Path ongoingDir;

    /**
     * Constructor of deployment directory manager for kernel.
     *
     * @param kernel a kernel instance
     */
    @Inject
    public DeploymentDirectoryManager(Kernel kernel) {
        this.kernel = kernel;
        this.deploymentsDir = kernel.getDeploymentsPath();
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
        try {
            Path deploymentPath = getDeploymentDirectoryPath();
            cleanupPreviousDeployments(previousSuccessDir);
            cleanupPreviousDeployments(previousFailureDir);

            Files.createSymbolicLink(symlink, deploymentPath);
            Files.delete(ongoingDir);
        } catch (IOException e) {
            logger.atWarn().log("Unable to preserve artifacts from the last deployment");
        }
    }

    private void cleanupPreviousDeployments(Path symlink) {
        if (!Files.exists(symlink)) {
            return;
        }
        try {
            Utils.deleteFileRecursively(Files.readSymbolicLink(symlink).toFile());
            Files.delete(symlink);
        } catch (IOException ioException) {
            logger.atWarn().kv("link", symlink).log("Unable to clean up previous deployments", ioException);
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
            throw new IOException("Deployment details can not be loaded from file " + ongoingDir);
        }

        Path filePath = getDeploymentMetadataFilePath();
        writeDeploymentMetadata(filePath, deployment);
    }

    private void writeDeploymentMetadata(Path filePath, Deployment deployment) throws IOException {
        try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(filePath))) {
            out.writeObject(deployment);
        }
    }

    /**
     * Read Deployment object from file.
     *
     * @return deployment object
     * @throws IOException on I/O error
     * @throws ClassNotFoundException when deserialization fails
     */
    public Deployment readDeploymentMetadata() throws IOException, ClassNotFoundException {
        if (!Files.isSymbolicLink(ongoingDir)) {
            throw new IOException("Deployment details can not be loaded from file " + ongoingDir);
        }

        Path filePath = getDeploymentMetadataFilePath();
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(filePath))) {
            return (Deployment) in.readObject();
        }
    }

    /**
     * Take a snapshot in a transaction log file before rollback if rollback is applicable for deployment.
     *
     * @param filepath File path to the config snapshot
     * @throws IOException if write fails
     */
    public void takeConfigSnapshot(Path filepath) throws IOException {
        kernel.writeEffectiveConfigAsTransactionLog(filepath);
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
     * @param fleetConfigArn Fleet configuration ARN of the deployment
     * @return Path to the deployment directory
     * @throws IOException on I/O errors
     */
    public Path createNewDeploymentDirectoryIfNotExists(String fleetConfigArn) throws IOException {
        Path path = deploymentsDir.resolve(getSafeFileName(fleetConfigArn));
        if (Files.exists(path) && Files.isDirectory(path)) {
            return path;
        }
        if (Files.isRegularFile(path)) {
            Files.delete(path);
        }
        Utils.createPaths(path);
        cleanupPreviousDeployments(ongoingDir);
        Files.createSymbolicLink(ongoingDir, path);

        return path;
    }

    public static String getSafeFileName(String fleetConfigArn) {
        return fleetConfigArn.replace(':', '.').replace('/', '+');
    }
}
