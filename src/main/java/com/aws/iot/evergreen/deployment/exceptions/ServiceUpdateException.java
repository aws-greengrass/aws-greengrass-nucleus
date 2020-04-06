package com.aws.iot.evergreen.deployment.exceptions;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@SuppressFBWarnings(justification = "This does not need to be serializable", value = "SE_BAD_FIELD")
public class ServiceUpdateException extends DeploymentException {

    public ServiceUpdateException(String message) {
        super(message);
    }
}
