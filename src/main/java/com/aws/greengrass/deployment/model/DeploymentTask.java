/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.model;

import com.aws.greengrass.deployment.exceptions.NonRetryableDeploymentTaskFailureException;
import com.aws.greengrass.deployment.exceptions.RetryableDeploymentTaskFailureException;

import java.util.concurrent.Callable;

public interface DeploymentTask extends Callable<DeploymentResult> {
    @Override
    DeploymentResult call() throws NonRetryableDeploymentTaskFailureException, RetryableDeploymentTaskFailureException;
}
