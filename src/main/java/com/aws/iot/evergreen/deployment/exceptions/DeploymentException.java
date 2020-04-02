package com.aws.iot.evergreen.deployment.exceptions;

public class DeploymentException extends Exception {

    public DeploymentException(String message) {
        super(message);
    }

    public DeploymentException(Throwable e) {
        super(e);
    }
}
