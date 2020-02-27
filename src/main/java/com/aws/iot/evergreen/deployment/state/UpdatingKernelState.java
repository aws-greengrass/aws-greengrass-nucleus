/* Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment.state;

import com.aws.iot.evergreen.deployment.DeploymentProcess;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.util.Map;

/**
 * Deployment state for updating kernel config.
 * Checks for update conditions, performs updates and handles result.
 */
@RequiredArgsConstructor
public class UpdatingKernelState implements State {

    private static final Logger logger = LogManager.getLogger(UpdatingKernelState.class);

    private static final String ROLLBACK_SNAPSHOT_PATH_FORMAT = "rollback_snapshot_%s.tlog";

    private final DeploymentProcess deploymentProcess;

    private final Kernel kernel;

    private boolean updateFinished = false;

    @Override
    public boolean canProceed() {
        logger.atInfo().log("<Updating>: checking if kernel can be updated");
        return true;
    }

    @Override
    public void proceed() {
        logger.atInfo().log("<Updating>: updating kernel");

        // TODO : After taking this snapshot, deployment can wait for some time before performing a safe update
        // so consider moving this to Kernel
        String rollbackSnapshotPath =
                String.format(ROLLBACK_SNAPSHOT_PATH_FORMAT, deploymentProcess.getDeploymentPacket().getDeploymentId());
        // record kernel snapshot
        try {
            kernel.writeEffectiveConfigAsTransactionLog(kernel.configPath.resolve(rollbackSnapshotPath));
        } catch (IOException e) {
            logger.atError().setEventType("config-update-error").setCause(e).log("Error taking kernel snapshot");
        }

        // merge config
        Map<Object, Object> resolvedConfig = deploymentProcess.getResolvedKernelConfig();
        logger.atInfo().addKeyValue("resolved_config", resolvedConfig).log("Resolved config :" + resolvedConfig);
        try {
            kernel.mergeInNewConfig(deploymentProcess.getDeploymentPacket().getDeploymentId(),
                    deploymentProcess.getDeploymentPacket().getDeploymentCreationTimestamp(), resolvedConfig).get();
        } catch (Exception e) {
            logger.atError().setEventType("config-update-error").setCause(e).log("Deployment failed, rolling back");
            // TODO : Rollback handling should be more sophisticated,
            // should it be its own state? Should it have retries?
            // Should revert changes to the local fleets-packages map/package registry as needed
            // All rolled back services should be restarted

            // rollback to snapshot without waiting for a safe time
            try {
                kernel.read(kernel.configPath.resolve(rollbackSnapshotPath));
                // TODO : Set deployment status to RolledBack?
                logger.atInfo().log("Deployment rolled back");
            } catch (IOException re) {
                // TODO : Set deployment status to Failed_RolledBack?
                logger.atError().setEventType("config-update-error").setCause(re).log("Failed to rollback deployment");
            }
        } finally {
            updateFinished = true;
            // TODO : Clean up snapshot file, etc
        }
    }

    @Override
    public void cancel() {
        // unsupported, ignore
    }

    // TODO : Revisit this, there should probably be more states after this like Rollback etc
    @Override
    public boolean isFinalState() {
        return updateFinished;
    }

}
