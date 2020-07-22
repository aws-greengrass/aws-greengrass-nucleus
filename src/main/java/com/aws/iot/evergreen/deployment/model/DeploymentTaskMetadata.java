package com.aws.iot.evergreen.deployment.model;

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
    private Future<DeploymentResult> deploymentResultFuture;
    @NonNull @Getter
    private String deploymentId;
    @NonNull @Getter
    private Deployment.DeploymentType deploymentType;
    @NonNull @Getter
    private AtomicInteger deploymentAttemptCount;
    @NonNull @Getter
    private DeploymentDocument deploymentDocument;
    @NonNull @Getter
    private boolean cancellable;

    @Synchronized
    public void setDeploymentResultFuture(Future<DeploymentResult> deploymentResultFuture) {
        this.deploymentResultFuture = deploymentResultFuture;
    }

    @Synchronized
    public Future<DeploymentResult> getDeploymentResultFuture() {
        return deploymentResultFuture;
    }

}
