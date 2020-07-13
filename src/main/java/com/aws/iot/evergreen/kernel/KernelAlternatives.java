/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.deployment.model.Deployment;
import lombok.Getter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

public class KernelAlternatives {
    private static final String CURRENT_DIR = "current";
    private static final String NEW_DIR = "new";
    private static final String TMP_DIR = "tmp";
    private static final String OLD_DIR = "old";
    private static final String BROKEN_DIR = "broken";
    private static final String PREVIOUS_SUCCESS_DIR = "previousSuccess";
    private static final String PREVIOUS_FAILURE_DIR = "previousFailure";

    @Getter
    private Path currentDir;
    @Getter
    private Path newDir;
    @Getter
    private Path tmpDir;
    @Getter
    private Path oldDir;
    @Getter
    private Path brokenDir;

    private final Path previousSuccessDir;
    private final Path previousFailureDir;

    /**
     * Constructor for KernelAlternatives, which manages the alternative launch directory of Kernel.
     *
     * @param kernelAltsPath alternative launch directory of Kernel
     */
    public KernelAlternatives(Path kernelAltsPath) {
        this.currentDir = kernelAltsPath.resolve(CURRENT_DIR);
        this.newDir = kernelAltsPath.resolve(NEW_DIR);
        this.tmpDir = kernelAltsPath.resolve(TMP_DIR);
        this.oldDir = kernelAltsPath.resolve(OLD_DIR);
        this.brokenDir = kernelAltsPath.resolve(BROKEN_DIR);
        this.previousSuccessDir = kernelAltsPath.resolve(PREVIOUS_SUCCESS_DIR);
        this.previousFailureDir = kernelAltsPath.resolve(PREVIOUS_FAILURE_DIR);
    }

    /**
     * Determine if Kernel is in update workflow from deployments and return deployment stage.
     *
     * @return DeploymentStage
     */
    public Deployment.DeploymentStage determineDeploymentStage() {
        // TODO: validate if any directory is corrupted
        if (newDir.toFile().exists()) {
            return Deployment.DeploymentStage.BOOTSTRAP;
        } else if (oldDir.toFile().exists()) {
            return Deployment.DeploymentStage.KERNEL_ACTIVATION;
        } else if (brokenDir.toFile().exists()) {
            return Deployment.DeploymentStage.KERNEL_ROLLBACK;
        }
        return Deployment.DeploymentStage.DEFAULT;
    }

    /**
     * Set up files and directories in order to flip Kernel to a new instance with new configuration.
     *
     * @throws IOException if file or directory changes fail
     */
    public void prepareActivation() throws IOException {
        Files.move(currentDir, oldDir, ATOMIC_MOVE);
        Files.move(newDir, currentDir, ATOMIC_MOVE);
    }

    /**
     * Clean up files and directories if Kernel update deployments succeeds.
     *
     * @throws IOException if file or directory changes fail
     */
    public void activationSucceeds() throws IOException {
        cleanupAltDir(previousSuccessDir);
        cleanupAltDir(previousFailureDir);
        Files.move(oldDir, previousSuccessDir, ATOMIC_MOVE);
    }

    /**
     * Set up files and directories in order to rollback Kernel to the previous configuration.
     *
     * @throws IOException if file or directory changes fail
     */
    public void prepareRollback() throws IOException {
        if (newDir.toFile().exists()) {
            Files.move(newDir, brokenDir, ATOMIC_MOVE);
            return;
        }
        Files.move(currentDir, brokenDir, ATOMIC_MOVE);
        Files.move(oldDir, currentDir, ATOMIC_MOVE);
    }

    /**
     * Clean up files and directories if Kernel update rollback completes.
     *
     * @throws IOException if file or directory changes fail
     */
    public void rollbackCompletes() throws IOException {
        cleanupAltDir(previousFailureDir);
        Files.move(brokenDir, previousFailureDir, ATOMIC_MOVE);
    }

    /**
     * Set up files and directories in order to run bootstrap steps before activating new Kernel configuration.
     *
     * @throws IOException if file or directory changes fail
     */
    public void prepareBootstrap() throws IOException {
        Files.copy(currentDir, tmpDir);
        Files.move(tmpDir, newDir, ATOMIC_MOVE);
    }

    /**
     * Clean up files and directories, and remove symlink references.
     *
     * @param path file path to cleanup
     */
    public void cleanupAltDir(Path path) {
        // TODO: delete files and symlinks and then remove dir
    }

    /**
     * Load information of the deployment to resume.
     *
     * @return Deployment
     */
    public Deployment loadPersistedDeployment() {
        // TODO: read deployment directory
        // return new Deployment(DeploymentType deploymentType, String id, DeploymentStage subtype)
        return null;
    }
}
