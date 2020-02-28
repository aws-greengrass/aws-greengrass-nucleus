/* Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment.state;

import com.aws.iot.evergreen.deployment.ConfigResolver;
import com.aws.iot.evergreen.deployment.exceptions.DeploymentFailureException;
import com.aws.iot.evergreen.deployment.model.DeploymentPacket;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.util.Map;

/**
 * Deployment state after package dependencies have been resolved and packages have been downloaded.
 * Generates config to be merged when appropriate.
 */
@RequiredArgsConstructor
public class DownloadedState extends BaseState {

    private static final Logger logger = LogManager.getLogger(DownloadedState.class);

    private Kernel kernel;

    /**
     * Constructor for Downloaded state.
     * @param deploymentPacket Deployment packet containing deployment configuration details
     * @param objectMapper Object mapper
     * @param kernel Evergreen kernel {@link Kernel}
     */
    public DownloadedState(DeploymentPacket deploymentPacket, ObjectMapper objectMapper, Kernel kernel) {
        this.deploymentPacket = deploymentPacket;
        this.objectMapper = objectMapper;
        this.kernel = kernel;
    }

    @Override
    public boolean canProceed() {
        logger.atInfo().log("<Downloaded>: checking if deployment can proceed");
        // check update kernel conditions
        return true;
    }

    @Override
    public void proceed() throws DeploymentFailureException {
        logger.info("Downloaded. Now preparing config for kernel");
        logger.atInfo().log("<Downloaded>: proceed");
        // resolve kernel config
        try {
            logger.atInfo().log("Packages to deploy are {}",
                    deploymentPacket.getResolvedPackagesToDeploy().toString());
            deploymentPacket.setResolvedKernelConfig(resolveKernelConfig());
            //Cleaning up the data which is no longer needed
            deploymentPacket.getResolvedPackagesToDeploy().clear();
        } catch (Exception e) {
            // TODO : Mark the deployment failed
            logger.atError().setEventType("donwloaded-state-error").setCause(e).log("Error in downloaded state");
            throw new DeploymentFailureException(e);
        }
    }

    @Override
    public void cancel() {
        // TODO : Cleanup, revert package cache updates, etc
    }

    private Map<Object, Object> resolveKernelConfig() {
        // TODO : Make ConfigResolver singlton and iniaitalize using DI when the state machine
        // made singleton
        ConfigResolver configResolver = new ConfigResolver(kernel, deploymentPacket.getResolvedPackagesToDeploy(),
                deploymentPacket.getRemovedTopLevelPackageNames());
        return configResolver.resolveConfig();
    }

}
