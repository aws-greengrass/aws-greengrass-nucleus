package com.aws.iot.evergreen.deployment.model;

import com.aws.iot.evergreen.deployment.DeploymentTask;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.Synchronized;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

@AllArgsConstructor
public class DeploymentTaskMetadata {
    @NonNull @Getter
    private DeploymentTask deploymentTask;
    @NonNull
    private Future<DeploymentResult> deploymentProcess;
    @NonNull @Getter
    private String deploymentId;
    @NonNull @Getter
    private Deployment.DeploymentType deploymentType;
    @NonNull @Getter
    private AtomicInteger deploymentAttemptCount;

    @Synchronized
    public void setDeploymentProcess(Future<DeploymentResult> deploymentProcess) {
        this.deploymentProcess = deploymentProcess;
    }

    @Synchronized
    public Future<DeploymentResult> getDeploymentProcess() {
        return deploymentProcess;
    }
}
