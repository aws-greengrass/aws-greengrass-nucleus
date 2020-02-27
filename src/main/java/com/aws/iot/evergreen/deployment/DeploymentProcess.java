/* Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.deployment.model.DeploymentPacket;
import com.aws.iot.evergreen.deployment.state.DownloadedState;
import com.aws.iot.evergreen.deployment.state.State;
import com.aws.iot.evergreen.deployment.state.UpdatingKernelState;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.PackageManager;
import com.aws.iot.evergreen.packagemanager.models.Package;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Deployment as a process that controls state transition and passes context among
 * deployment states.
 */
public class DeploymentProcess {

    // TODO : This object should control all states and transitions
    // and not let itself be modified by other states
    private static final Logger logger = LogManager.getLogger(DeploymentProcess.class);

    private static final long DEPLOYMENT_STATE_CHANGE_WAIT_TIME_SECONDS = 2;

    @Getter
    private final State downloadedState;

    @Getter
    private final State updatingKernelState;

    @Getter
    @Setter
    private volatile State currentState;

    @Getter
    private final DeploymentPacket deploymentPacket;

    @Getter
    @Setter
    private Set<Package> packagesToDeploy;

    @Getter
    @Setter
    private Set<String> removedTopLevelPackageNames;

    @Getter
    @Setter
    private Map<Object, Object> resolvedKernelConfig;

    /**
     * Constructor to initialize deployment process.
     *
     * @param packet parsed deployment document
     * @param kernel running kernel instance
     */
    public DeploymentProcess(DeploymentPacket packet, Kernel kernel) {
        this.downloadedState = new DownloadedState(this, kernel);
        this.updatingKernelState = new UpdatingKernelState(this, kernel);

        // TODO : Change this to appropriate initial state when the initial state is implemented
        this.currentState = this.downloadedState;
        this.deploymentPacket = packet;
    }

    /**
     * Execute deployment.
     */
    public void execute() {
        // TODO : Letting this state machine be modified by individual states is not very maintainable
        // When the state machine is redesigned, have this class manage passing context to states and
        // control state transitions
        while (!currentState.isFinalState()) {
            if (currentState.canProceed()) {
                currentState.proceed();
            } else {
                try {
                    int duration = 2;
                    TimeUnit.SECONDS.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        logger.atInfo().addKeyValue("final_state", currentState.getClass().getSimpleName()).log("final state is");
    }

    /**
     * Cancel an ongoing deployment by terminating current state.
     */
    public void cancel() {
        currentState.cancel();
    }
}
