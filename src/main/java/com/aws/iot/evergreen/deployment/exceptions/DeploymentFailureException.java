/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment.exceptions;

public class DeploymentFailureException extends Exception {

    public DeploymentFailureException(String message, Throwable e) {
        super(message, e);
    }

    public DeploymentFailureException(Throwable e) {
        super(e);
    }

    public DeploymentFailureException(String message) {
        super(message);
    }
}
