package com.aws.iot.evergreen.deployment.exceptions;

public class DeploymentTaskFailureException extends Exception {
    public DeploymentTaskFailureException(Throwable e) {
        super(e);
    }

    public DeploymentTaskFailureException(String msg) {
        super(msg);
    }

}
