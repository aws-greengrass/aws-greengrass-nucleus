package com.aws.iot.evergreen.deployment.exceptions;

public class ServiceUpdateException extends DeploymentException {
    static final long serialVersionUID = -3387516993124229948L;

    public ServiceUpdateException(String message) {
        super(message);
    }

    public ServiceUpdateException(Throwable e) {
        super(e);
    }
}
