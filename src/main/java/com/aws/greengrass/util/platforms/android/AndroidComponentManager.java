/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import java.io.IOException;
import lombok.NonNull;

/**
 * Interface to start/stop Android Foreground service or activity.
 */
public interface AndroidComponentManager {

    /**
     * Start Android component as Activity.
     *
     * @param packageName Android Package to start.
     * @param className Class name of the Activity.
     * @param action Action of Intent to send.
     * @throws IOException on errors
     */
    void startActivity(@NonNull String packageName, @NonNull String className
            , @NonNull String action) throws IOException;

    /**
     * Stop Android component started as Activity.
     *
     * @param packageName Android Package to start.
     * @param className Class name of the Activity.
     * @param action Action of Intent to send.
     * @throws IOException on errors
     */
    void stopActivity(@NonNull String packageName, @NonNull String className
            , @NonNull String action) throws IOException;
    /**
     * Initiate starting Android component as Foreground Service.
     *
     * @param packageName Android Package to start.
     * @param className Class name of the ForegroundService.
     * @param action Action of Intent to send
     * @throws IOException on errors
     */
    void startService(@NonNull String packageName, @NonNull String className
            , @NonNull String action) throws IOException;

    /**
     * Initiate stopping Android component was started as Foreground Service.
     *
     * @param packageName Android Package to start.
     * @param className Class name of the ForegroundService.
     * @param action Action of Intent to send.
     * @throws IOException on errors
     */
    void stopService(@NonNull String packageName, @NonNull String className
            , @NonNull String action) throws IOException;
}
