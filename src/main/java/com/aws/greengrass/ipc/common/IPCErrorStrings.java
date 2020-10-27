/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc.common;

public final class IPCErrorStrings {
    public static final String DEPLOYMENTS_QUEUE_NOT_INITIALIZED = "Greengrass not setup to receive deployments. The "
            + "deployments queue is not initialized";
    public static final String DEPLOYMENTS_QUEUE_FULL = "Deployments queue is full, Please try again later";

    private IPCErrorStrings() {
    }
}
