/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.deployment.state;

import com.aws.iot.evergreen.deployment.exceptions.DeploymentFailureException;
import com.aws.iot.evergreen.deployment.model.DeploymentPacket;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;

public abstract class BaseState implements State {

    @Getter
    @Setter
    protected DeploymentPacket deploymentPacket;

    @Getter
    @Setter
    protected ObjectMapper objectMapper;

    @Override
    public boolean canProceed() {
        return false;
    }

    @Override
    public void proceed() throws DeploymentFailureException {

    }

    @Override
    public void cancel() {

    }
}
