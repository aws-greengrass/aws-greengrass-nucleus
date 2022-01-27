/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.aws.greengrass.android;

import android.app.Application;

public class ContextHolder {

    private static ContextHolder INSTANCE;

    private ContextHolder() {
    }

    public static ContextHolder getInstance() {
        if (INSTANCE == null)
            synchronized (ContextHolder.class) {
                if (INSTANCE == null)
                    INSTANCE = new ContextHolder();
            }
        return INSTANCE;
    }

    public Application context;
}
