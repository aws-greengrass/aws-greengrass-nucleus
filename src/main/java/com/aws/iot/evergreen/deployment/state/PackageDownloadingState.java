/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.deployment.state;

import com.aws.iot.evergreen.deployment.exceptions.DeploymentFailureException;
import com.aws.iot.evergreen.deployment.model.DeploymentPacket;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.PackageManager;
import com.aws.iot.evergreen.packagemanager.models.Package;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

import java.util.Set;
import java.util.concurrent.ExecutionException;
import javax.inject.Inject;

/**
 * Class representing the package downloading state.
 * Dependency resolution and download of package recipes, artifacts will happen.
 */
public class PackageDownloadingState extends BaseState {

    private static Logger logger = LogManager.getLogger(PackageDownloadingState.class);

    private PackageManager packageManager;

    /**
     * Constructor for PackageDownloadingState.
     * @param deploymentPacket Deployment packet containing deployment configuration
     * @param objectMapper Object mapper
     * @param packageManager Package manager {@link PackageManager}
     */
    public PackageDownloadingState(DeploymentPacket deploymentPacket,
                                   ObjectMapper objectMapper, PackageManager packageManager) {
        this.deploymentPacket = deploymentPacket;
        this.objectMapper = objectMapper;
        this.packageManager = packageManager;
    }


    @Override
    public boolean canProceed() {
        //TODO: Evaluate download conditions
        return true;
    }

    @Override
    public void proceed() throws DeploymentFailureException {
        logger.info("Downloading the packages");
        logger.atInfo().log("PackageMetadata received: {}",
                deploymentPacket.getProposedPackagesFromDeployment());
        //call package manager withe proposed packages
        try {
            Set<Package> packages =
                    packageManager.resolvePackages(deploymentPacket.getProposedPackagesFromDeployment()).get();
            deploymentPacket.setPackagesToDeploy(packages);
            //TODO: Clean up the proposed packages from deployment packet, if not needed after this point
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Caught exception while downloading packages", e);
            logger.error(e.getMessage());
            throw new DeploymentFailureException(e);
        }

    }

    @Override
    public void cancel() {

    }
}
