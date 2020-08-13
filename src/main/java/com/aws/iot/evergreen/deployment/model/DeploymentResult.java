package com.aws.iot.evergreen.deployment.model;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@EqualsAndHashCode
public class DeploymentResult {

    DeploymentStatus deploymentStatus;
    Throwable failureCause;

    public enum DeploymentStatus {
        SUCCESSFUL,
        FAILED_NO_STATE_CHANGE,
        FAILED_ROLLBACK_NOT_REQUESTED,
        FAILED_ROLLBACK_COMPLETE,
        FAILED_UNABLE_TO_ROLLBACK
    }
}
