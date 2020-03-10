package com.aws.iot.evergreen.deployment.exceptions;

public class RetryableDeploymentTaskFailureException extends Exception {
    public RetryableDeploymentTaskFailureException(Throwable e) {
        super(e);
    }
}
