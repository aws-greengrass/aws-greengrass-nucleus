package com.aws.iot.evergreen.deployment.exceptions;

import com.aws.iot.evergreen.kernel.EvergreenService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Getter;

@SuppressFBWarnings(justification = "This does not need to be serializable", value = "SE_BAD_FIELD")
public class ServiceUpdateException extends DeploymentException {

    @Getter
    private EvergreenService brokenService;

    public ServiceUpdateException(String message, EvergreenService brokenService) {
        super(message);
        this.brokenService = brokenService;
    }
}
