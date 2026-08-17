/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins.docker.exceptions;

/**
 * A {@code docker pull} failure that could not be classified as either a known network error or a known
 * non-retryable error.
 *
 * <p>Such a failure is treated as possibly transient and is given a small, bounded number of retries. Extends
 * {@link DockerPullException} so that the deployment error code reported to the customer once those retries are
 * exhausted is unchanged.
 */
public class UnknownDockerPullException extends DockerPullException {
    static final long serialVersionUID = -3387516993124229948L;

    public UnknownDockerPullException(String message) {
        super(message);
    }

    public UnknownDockerPullException(String message, Throwable cause) {
        super(message, cause);
    }
}
