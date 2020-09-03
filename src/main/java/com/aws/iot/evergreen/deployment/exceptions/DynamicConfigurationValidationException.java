package com.aws.iot.evergreen.deployment.exceptions;

public class DynamicConfigurationValidationException extends DeploymentException {
    static final long serialVersionUID = -3387516993124229948L;

    public DynamicConfigurationValidationException(String message) {
        super(message);
    }

    public DynamicConfigurationValidationException(Throwable e) {
        super(e);
    }
}

