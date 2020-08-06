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
    private static final String ROLLBACK_SNAPSHOT_FILE = "rollback_snapshot.tlog";
    private static final String TARGET_CONFIG_FILE = "target_config.tlog";
    private static final String BOOTSTRAP_TASK_FILE = "bootstrap_task.json";
    private static final String DEPLOYMENT_METADATA_FILE = "deployment_metadata.json";

    private static final String PREVIOUS_SUCCESS_LINK = "previous-success";
    private static final String PREVIOUS_FAILURE_LINK = "previous-failure";
    private static final String ONGOING_DEPLOYMENT_LINK = "ongoing";
    private static final Logger logger = LogManager.getLogger(DeploymentDirectoryManager.class);
    private final Kernel kernel;

    private final Path deploymentsDir;
    private final Path previousSuccessDir;
    private final Path previousFailureDir;
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
     * Persist the directory for the last failed deployment and clean up earlier deployments.
     *
     * @param fleetConfigArn the last deployment ID
     */
    public void persistLastFailedDeployment(String fleetConfigArn) {
        persistPointerToLastFinishedDeployment(previousFailureDir, fleetConfigArn);
    }

    /**
     * Persist the directory for the last successful deployment and clean up earlier deployments.
     *
     * @param fleetConfigArn Deployment fleet configuration ARN
     */
    public void persistLastSuccessfulDeployment(String fleetConfigArn) {
        persistPointerToLastFinishedDeployment(previousSuccessDir, fleetConfigArn);
    }

    private void persistPointerToLastFinishedDeployment(Path symlink, String fleetConfigArn) {
        try {
            Path deploymentPath = getDeploymentDirectoryPath(fleetConfigArn);
            if (Files.isSymbolicLink(symlink)) {
                Utils.deleteFileRecursively(Files.readSymbolicLink(symlink).toFile());
            }
            Utils.deleteFileRecursively(symlink.toFile());
            Files.createSymbolicLink(symlink, deploymentPath);
            Files.delete(ongoingDir);
        } catch (IOException e) {
            logger.atWarn().kv("fleetConfigArn", fleetConfigArn).log(
                    "Unable to preserve artifacts from the last deployment");
        }
    }

    /**
     * Write Deployment object to file.
     *
     * @param fleetConfigArn Deployment fleet configuration ARN
     * @param deployment Deployment object
     * @throws IOException on I/O error
     */
    public void writeDeploymentMetadata(String fleetConfigArn, Deployment deployment) throws IOException {
        Path filePath = getDeploymentMetadataFilePath(fleetConfigArn);
        writeDeploymentMetadata(filePath, deployment);
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

        Path filePath = Files.readSymbolicLink(ongoingDir).toAbsolutePath().resolve(DEPLOYMENT_METADATA_FILE);
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

        Path filePath = Files.readSymbolicLink(ongoingDir).toAbsolutePath().resolve(DEPLOYMENT_METADATA_FILE);
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
    public void takeSnapshot(Path filepath) throws IOException {
        kernel.writeEffectiveConfigAsTransactionLog(filepath);
    }

    /**
     * Resolve snapshot file path.
     *
     * @param fleetConfigArn Deployment fleet configuration ARN
     * @return Path to snapshot file
     * @throws IOException on I/O errors
     */
    public Path getSnapshotFilePath(String fleetConfigArn) throws IOException {
        return getDeploymentDirectoryPath(fleetConfigArn).resolve(ROLLBACK_SNAPSHOT_FILE);
    }

    /**
     * Resolve target config file path.
     *
     * @param fleetConfigArn Deployment fleet configuration ARN
     * @return Path to target config file
     * @throws IOException on I/O errors
     */
    public Path getTargetConfigFilePath(String fleetConfigArn) throws IOException {
        return getDeploymentDirectoryPath(fleetConfigArn).resolve(TARGET_CONFIG_FILE);
    }

    /**
     * Resolve file path to persisted bootstrap task list.
     *
     * @param fleetConfigArn Deployment fleet configuration ARN
     * @return Path to file
     * @throws IOException on I/O errors
     */
    public Path getBootstrapTaskFilePath(String fleetConfigArn) throws IOException {
        return getDeploymentDirectoryPath(fleetConfigArn).resolve(BOOTSTRAP_TASK_FILE);
    }

    /**
     * Resolve file path to persisted bootstrap task list of an ongoing deployment.
     *
     * @return Path to file
     * @throws IOException on I/O errors
     */
    public Path getBootstrapTaskFilePath() throws IOException {
        return Files.readSymbolicLink(ongoingDir).resolve(BOOTSTRAP_TASK_FILE);
    }

    /**
     * Resolve file path to persisted deployment metadata.
     *
     * @param fleetConfigArn Deployment fleet configuration ARN
     * @return Path to file
     * @throws IOException on I/O errors
     */
    public Path getDeploymentMetadataFilePath(String fleetConfigArn) throws IOException {
        return getDeploymentDirectoryPath(fleetConfigArn).resolve(DEPLOYMENT_METADATA_FILE);
    }

    private Path getDeploymentDirectoryPath(String fleetConfigArn) throws IOException {
        return createNewDeploymentDirectoryIfNotExists(fleetConfigArn);
    }

    private Path createNewDeploymentDirectoryIfNotExists(String fleetConfigArn) throws IOException {
        Path path = deploymentsDir.resolve(getSafeFileName(fleetConfigArn));
        if (Files.exists(path) && Files.isDirectory(path)) {
            return path;
        }
        if (Files.isRegularFile(path)) {
            Files.delete(path);
        }
        Utils.createPaths(path);
        Utils.deleteFileRecursively(ongoingDir.toFile());
        Files.createSymbolicLink(ongoingDir, path);

        return path;
    }

    public static String getSafeFileName(String fleetConfigArn) {
        return fleetConfigArn.replace(':', '.').replace('/', '+');
    }
}
