/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.deployment.state;

import com.aws.iot.evergreen.deployment.exceptions.DeploymentFailureException;
import com.aws.iot.evergreen.deployment.model.DeploymentContext;
import com.aws.iot.evergreen.logging.api.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@AllArgsConstructor
public abstract class BaseState implements State {

    @Getter
    @Setter
    protected DeploymentContext deploymentContext;

    @Getter
    @Setter
    protected ObjectMapper objectMapper;

    protected Logger logger;

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
