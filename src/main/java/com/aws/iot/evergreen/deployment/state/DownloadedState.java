/* Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment.state;

import com.aws.iot.evergreen.deployment.ConfigResolver;
import com.aws.iot.evergreen.deployment.DeploymentProcess;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import lombok.RequiredArgsConstructor;

import java.util.Map;

/**
 * Deployment state after package dependencies have been resolved and packages have been downloaded.
 * Generates config to be merged when appropriate.
 */
@RequiredArgsConstructor
public class DownloadedState implements State {

    private static final Logger logger = LogManager.getLogger(DownloadedState.class);

    private final DeploymentProcess deploymentProcess;

    private final Kernel kernel;

    @Override
    public boolean canProceed() {
        logger.atInfo().log("<Downloaded>: checking if deployment can proceed");
        // check update kernel conditions
        return true;
    }

    @Override
    public void proceed() {
        logger.atInfo().log("<Downloaded>: proceed");
        // resolve kernel config
        try {
            deploymentProcess.setResolvedKernelConfig(resolveKernelConfig());
        } catch (Exception e) {
            // TODO : Mark the deployment failed
            logger.atError().setEventType("donwloaded-state-error").setCause(e).log("Error in downloaded state");
        }
    }

    @Override
    public void cancel() {
        // TODO : Cleanup, revert package cache updates, etc
    }

    private Map<Object, Object> resolveKernelConfig() {
        // TODO : Make ConfigResolver singlton and iniaitalize using DI when the state machine
        // made singleton
        ConfigResolver configResolver = new ConfigResolver(kernel, deploymentProcess.getPackagesToDeploy(),
                deploymentProcess.getRemovedTopLevelPackageNames());
        return configResolver.resolveConfig();
    }

}
