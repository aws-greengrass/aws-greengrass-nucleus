/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import lombok.NonNull;

import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Basic interface to run virtual commands like APK installation or component.
 */
public abstract class AndroidVirtualCmdExecution {

    /**
     * Start (beginning) execution.
     *     Perform initialization of operation.
     *     Expected to be called synchronously.
     *
     * @throws InterruptedException when operation was interrupted
     * @throws IOException on errors
     */
    public abstract void startup() throws IOException, InterruptedException;

    /**
     * Run execution after startup.
     *    Expected to be called in separated thread.
     *
     * @return exit code of run operation
     * @throws InterruptedException when operation was interrupted
     * @throws IOException on errors
     */
    public abstract int run() throws IOException, InterruptedException;

    /**
     * Shutdown execution.
     *    Expected to be called in same thread as run or other thread.
     *
     * @throws InterruptedException when operation was interrupted
     * @throws IOException on errors
     */
    public abstract void shutdown() throws IOException, InterruptedException;

    public AndroidVirtualCmdExecution withOut(@NonNull final Consumer<CharSequence> o) {
        return this;
    }

    public AndroidVirtualCmdExecution withErr(@NonNull final Consumer<CharSequence> o) {
        return this;
    }

    public AndroidVirtualCmdExecution withEnv(@NonNull final Map<String, String> environment) {
        return this;
    }
}
