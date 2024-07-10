/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.model;

import java.util.concurrent.Callable;

public interface DeploymentTask extends Callable<DeploymentResult> {
    @Override
    DeploymentResult call() throws InterruptedException;

    default void cancel() {
    }
}
