/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.errorcode;

public enum DeploymentErrorType {
    NONE,
    SERVER_ERROR,
    NUCLEUS_ERROR,
    COMPONENT_ERROR,
    AWS_COMPONENT_ERROR,
    USER_COMPONENT_ERROR,
    PERMISSION_ERROR,
    DEVICE_ERROR,
    NETWORK_ERROR,
    DEPENDENCY_ERROR,
    REQUEST_ERROR,
    HTTP_ERROR,
    COMPONENT_RECIPE_ERROR,
    UNKNOWN_ERROR
}
