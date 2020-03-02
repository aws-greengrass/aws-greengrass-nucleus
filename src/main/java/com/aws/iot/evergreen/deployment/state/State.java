/* Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment.state;

import com.aws.iot.evergreen.deployment.exceptions.DeploymentFailureException;

public interface State {

    /**
     * Checks configured conditions if any to decide if the task can be done.
     *
     * @return flag indicating if the task can proceed.
     */
    boolean canProceed();

    /**
     * Perform the tasks for the state.
     *
     * @throws DeploymentFailureException if the deployment fails at this step
     */
    void proceed() throws DeploymentFailureException;

    /**
     * Cancel the ongoing tasks for the state.
     */
    void cancel();

    /**
     * Denotes if the state is the last state, i.e. end of all tasks.
     *
     * @return flag indicating if it is the end of deployment tasks
     */
    default boolean isFinalState() {
        return false;
    }

}
