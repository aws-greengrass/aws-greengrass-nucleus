/* Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.deployment.exceptions.DeploymentFailureException;
import com.aws.iot.evergreen.deployment.model.DeploymentPacket;
import com.aws.iot.evergreen.deployment.state.DownloadedState;
import com.aws.iot.evergreen.deployment.state.PackageDownloadingState;
import com.aws.iot.evergreen.deployment.state.ParseAndValidateState;
import com.aws.iot.evergreen.deployment.state.State;
import com.aws.iot.evergreen.deployment.state.UpdatingKernelState;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.PackageManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Deployment as a process that controls state transition and passes context among
 * deployment states.
 */
public class DeploymentProcess implements Callable<Boolean> {

    // TODO : This object should control all states and transitions
    // and not let itself be modified by other states

    private static final long DEPLOYMENT_STATE_CHANGE_WAIT_TIME_SECONDS = 2;

    private static final Logger logger = LogManager.getLogger(DeploymentProcess.class);
    private final ObjectMapper objectMapper;
    private final Kernel kernel;
    private final PackageManager packageManager;

    @Getter
    @Setter
    private volatile State currentState;

    @Getter
    private final DeploymentPacket deploymentPacket;

    @Getter
    @Setter
    private Map<Object, Object> resolvedKernelConfig;

    /**
     * Execute deployment.
     *
     * @return
     */
    public Boolean execute() throws DeploymentFailureException {
        // TODO : Letting this state machine be modified by individual states is not very maintainable
        // When the state machine is redesigned, have this class manage passing context to states and
        // control state transitions
        try {
            while (!currentState.isFinalState()) {
                if (currentState.canProceed()) {
                    currentState.proceed();
                    switch (deploymentPacket.getProcessStatus()) {
                        //TODO: Rename these states
                        case VALIDATE_AND_PARSE: {
                            logger.atInfo().addKeyValue("deploymentPacket", deploymentPacket)
                                    .log("Finished validating and parsing. Going to downloading");
                            deploymentPacket.setProcessStatus(DeploymentPacket.ProcessStatus.PACKAGE_DOWNLOADING);
                            currentState = new PackageDownloadingState(deploymentPacket, objectMapper, packageManager);
                            break;
                        }
                        case PACKAGE_DOWNLOADING: {
                            logger.atInfo().addKeyValue("deploymentPacket", deploymentPacket)
                                    .log("Package downloaded. Next step is to create config for kernel");
                            deploymentPacket.setProcessStatus(DeploymentPacket.ProcessStatus.PACKAGE_DOWNLOADED);
                            currentState = new DownloadedState(deploymentPacket, objectMapper, kernel);
                            break;
                        }
                        case PACKAGE_DOWNLOADED: { //TODO: Consider renaming this to Create config
                            logger.atInfo().addKeyValue("deploymentPacket", deploymentPacket)
                                    .log("Created config for kernel. Next is to update the kernel");
                            deploymentPacket.setProcessStatus(DeploymentPacket.ProcessStatus.UPDATING_KERNEL);
                            currentState = new UpdatingKernelState(deploymentPacket, objectMapper, kernel);
                            break;
                        }
                        case UPDATING_KERNEL: {
                            logger.atInfo().addKeyValue("deploymentPacket", deploymentPacket).log("Updated kernel");
                            break;
                        }
                        default: {
                            logger.atError().addKeyValue("deploymentPacket", deploymentPacket)
                                    .log("Unexpected status for deployment process");
                            return Boolean.FALSE;
                        }
                    }
                } else {
                    try {
                        TimeUnit.SECONDS.sleep(DEPLOYMENT_STATE_CHANGE_WAIT_TIME_SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            logger.atInfo().setEventType("deployment-state-machine-end")
                    .addKeyValue("finalState", currentState.getClass().getSimpleName()).log();
            return Boolean.TRUE;
        } catch (DeploymentFailureException e) {
            //TODO: Update deployment packet with status details
            logger.atError().setCause(e).addKeyValue("deploymentPacket", deploymentPacket).log("Deployment failed");
            return Boolean.FALSE;
        }
    }

    /**
     * Constructor to initialize deployment process.
     * @param deploymentPacket packet containing the deployment context
     * @param objectMapper Object mapper
     * @param kernel Evergreen kernel {@link Kernel}
     * @param packageManager Package manager {@link PackageManager}
     */
    public DeploymentProcess(DeploymentPacket deploymentPacket, ObjectMapper objectMapper, Kernel kernel,
                             PackageManager packageManager) {
        this.objectMapper = objectMapper;
        this.currentState = new ParseAndValidateState(deploymentPacket, objectMapper);
        this.kernel = kernel;
        this.packageManager = packageManager;
        deploymentPacket.setProcessStatus(DeploymentPacket.ProcessStatus.VALIDATE_AND_PARSE);
        this.deploymentPacket = deploymentPacket;
    }

    /**
     * Cancel an ongoing deployment by terminating current state.
     */
    public void cancel() {
        currentState.cancel();
    }

    @Override
    public Boolean call() throws Exception {
        return execute();
    }
}
