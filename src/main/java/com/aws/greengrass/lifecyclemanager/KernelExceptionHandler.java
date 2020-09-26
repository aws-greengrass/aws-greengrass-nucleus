/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;

public class KernelExceptionHandler implements Thread.UncaughtExceptionHandler {
    private static final Logger log = LogManager.getLogger(KernelExceptionHandler.class);

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        log.atError("uncaught-exception").setCause(e).kv("thread", t).log();
    }
}
