/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.bootstrap;

public final class BootstrapSuccessCode {
    public static final int NO_OP = 0;
    public static final int REQUEST_RESTART = 100;
    public static final int REQUEST_REBOOT = 101;

    private BootstrapSuccessCode() {
    }

    public static boolean isErrorCode(int code) {
        return code != NO_OP && code != REQUEST_REBOOT && code != REQUEST_RESTART;
    }
}
