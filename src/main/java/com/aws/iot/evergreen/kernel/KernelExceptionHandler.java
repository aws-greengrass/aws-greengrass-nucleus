/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;

public class KernelExceptionHandler implements Thread.UncaughtExceptionHandler {
    private static final Logger log = LogManager.getLogger(KernelExceptionHandler.class);

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        log.atError("uncaught-exception").setCause(e).kv("thread", t).log();
    }
}
