package com.aws.iot.evergreen.deployment.exceptions;

public class NonRetryableDeploymentTaskFailureException extends Exception {
    public NonRetryableDeploymentTaskFailureException(Throwable e) {
        super(e);
    }

}
