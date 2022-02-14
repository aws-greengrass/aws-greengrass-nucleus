/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.utils;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;

public class LazyLogger {
    // actual logger instance, use postponed creation until Nucleus did initialized
    private Logger logger = null;


    /**
     * Initialize logger internals.
     * @param cl class reference
     */
    public void initLogger(Class cl) {
        synchronized (this) {
            logger = LogManager.getLogger(cl);
        }
    }

    /**
     * Send message to logs with debug log level.
     * @param s message
     * @param objects related objects
     */
    public void logDebug(String s, Object... objects) {
        Logger localLogger;
        synchronized (this) {
            localLogger = logger;
        }
        if (localLogger != null) {
            localLogger.debug(s, objects);
        }
    }

    /**
     * Send message to logs with info log level.
     * @param s message
     * @param objects related objects
     */
    public void logInfo(String s, Object... objects) {
        Logger localLogger;
        synchronized (this) {
            localLogger = logger;
        }
        if (localLogger != null) {
            localLogger.info(s, objects);
        }
    }

    /**
     * Send message to logs with debug log level.
     * @param s message
     * @param objects related objects
     */
    public void logWarn(String s, Object... objects) {
        Logger localLogger;
        synchronized (this) {
            localLogger = logger;
        }
        if (localLogger != null) {
            localLogger.error(s, objects);
        }
    }
    /**
     * Send message to logs with debug log level.
     * @param s message
     * @param objects related objects
     */
    public void logError(String s, Object... objects) {
        Logger localLogger;
        synchronized (this) {
            localLogger = logger;
        }
        if (localLogger != null) {
            localLogger.error(s, objects);
        }
    }
}