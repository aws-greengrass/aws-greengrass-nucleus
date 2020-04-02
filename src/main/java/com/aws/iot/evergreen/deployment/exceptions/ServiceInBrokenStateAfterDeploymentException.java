package com.aws.iot.evergreen.deployment.exceptions;

import com.aws.iot.evergreen.kernel.EvergreenService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Getter;

@SuppressFBWarnings(justification = "This does not need to be serializable", value = "SE_BAD_FIELD")
public class ServiceInBrokenStateAfterDeploymentException extends Exception {
    static final long serialVersionUID = -3387516993124229948L;
    @Getter
    private EvergreenService brokenService;

    public ServiceInBrokenStateAfterDeploymentException(EvergreenService brokenService) {
        super();
        this.brokenService = brokenService;
    }
}
