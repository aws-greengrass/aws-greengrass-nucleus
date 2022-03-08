/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.util;

import com.aws.greengrass.android.provision.WorkspaceManager;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import lombok.NonNull;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public class LogHelper {
    private static AtomicBoolean initialized = new AtomicBoolean(false);

    /**
     * Get logger instance.
     *
     * @param filesDir Android's application files directory
     * @param clazz class to log
     * @return Logger instance
     */
    public static @NonNull Logger getLogger(@NonNull File filesDir, @NonNull Class<?> clazz) {
        if (!initialized.get()) {
            initialize(filesDir);
            initialized.set(true);
        }
        return LogManager.getLogger(clazz.getName());
    }

    private static void initialize(@NonNull File filesDir) {
        WorkspaceManager.init(filesDir);

        // set required properties
        System.setProperty("log.store", "FILE");
    }
}
