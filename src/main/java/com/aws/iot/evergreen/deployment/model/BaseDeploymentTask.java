/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment.model;

import com.aws.iot.evergreen.deployment.exceptions.NonRetryableDeploymentTaskFailureException;
import com.aws.iot.evergreen.deployment.exceptions.RetryableDeploymentTaskFailureException;

import java.util.concurrent.Callable;

public interface BaseDeploymentTask extends Callable<DeploymentResult> {
    @Override
    DeploymentResult call() throws NonRetryableDeploymentTaskFailureException, RetryableDeploymentTaskFailureException;
}
