/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment;


import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.aws.iot.evergreen.deployment.DeploymentConfigMerger.MERGE_ERROR_LOG_EVENT_KEY;
import static com.aws.iot.evergreen.deployment.DeploymentConfigMerger.ROLLBACK_SNAPSHOT_PATH_FORMAT;

/**
 * This class will be replaced with deployment directory manager.
 * @Deprecated
 */
final class ConfigSnapshotUtils {
    private ConfigSnapshotUtils() {
    }

    /**
     * Take a snapshot in a transaction log file before rollback if rollback is applicable for deployment.
     *
     * @param kernel Kernel instance
     * @param filepath File path to the config snapshot
     */
    static void takeSnapshot(Kernel kernel, Path filepath) throws IOException {
        kernel.writeEffectiveConfigAsTransactionLog(filepath);
    }

    /**
     * Clean up snapshot file.
     *
     * @param filepath File path to the config snapshot
     * @param logger Logger instance
     */
    static void cleanUpSnapshot(Path filepath, Logger logger) {
        try {
            Files.delete(filepath);
        } catch (IOException e) {
            logger.atError().setEventType(MERGE_ERROR_LOG_EVENT_KEY).setCause(e)
                    .log("Error cleaning up kernel snapshot");
        }
    }

    /**
     * Resolve snapshot file path.
     *
     * @param kernel Kernel instance
     * @param deploymentId Deployment Identifier
     * @return Path to snapshot file
     */
    static Path getSnapshotFilePath(Kernel kernel, String deploymentId) {
        return kernel.getConfigPath().resolve(String
                .format(ROLLBACK_SNAPSHOT_PATH_FORMAT, deploymentId.replace(':', '.').replace('/', '+')));
    }
}
