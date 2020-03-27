package com.aws.iot.evergreen.deployment.exceptions;

public class NonRetryableDeploymentTaskFailureException extends Exception {
    static final long serialVersionUID = -3387516993124229948L;

    public NonRetryableDeploymentTaskFailureException(Throwable e) {
        super(e);
    }

}
