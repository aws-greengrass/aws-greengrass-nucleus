/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.Synchronized;

import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

@AllArgsConstructor
public class DeploymentTaskMetadata {
    // TODO: [P41179644] clean up duplicate information
    @NonNull @Getter
    private Deployment deployment;
    @NonNull @Getter
    private DeploymentTask deploymentTask;
    @NonNull
    private Future<DeploymentResult> deploymentResultFuture;
    @NonNull @Getter
    private AtomicInteger deploymentAttemptCount;

    @Synchronized
    public void setDeploymentResultFuture(Future<DeploymentResult> deploymentResultFuture) {
        this.deploymentResultFuture = deploymentResultFuture;
    }

    @Synchronized
    public Future<DeploymentResult> getDeploymentResultFuture() {
        return deploymentResultFuture;
    }

    public String getDeploymentId() {
        return this.deployment.getId();
    }

    public String getGreengrassDeploymentId() {
        return this.deployment.getGreengrassDeploymentId();
    }

    public String getConfigurationArn() {
        return this.deployment.getConfigurationArn();
    }

    public Deployment.DeploymentType getDeploymentType() {
        return this.deployment.getDeploymentType();
    }

    public DeploymentDocument getDeploymentDocument() {
        return this.deployment.getDeploymentDocumentObj();
    }

    public List<String> getRootPackages() {
        return this.getDeploymentDocument().getRootPackages();
    }

    public void cancel(boolean mayInterruptIfRunning) {
        deploymentTask.cancel();
        deploymentResultFuture.cancel(mayInterruptIfRunning);
    }
}
