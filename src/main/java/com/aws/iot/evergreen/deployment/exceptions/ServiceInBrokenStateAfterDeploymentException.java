package com.aws.iot.evergreen.deployment.exceptions;

import com.aws.iot.evergreen.kernel.EvergreenService;

public class DeploymentFailedDueToBrokenServiceException extends Exception {
    private EvergreenService brokenService;

    public DeploymentFailedDueToBrokenServiceException(EvergreenService brokenService) {
        this.brokenService = brokenService;
    }
}
