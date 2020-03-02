/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.deployment.exceptions;

import java.util.Map;

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
