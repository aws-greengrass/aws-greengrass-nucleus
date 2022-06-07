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
     *     Executed in thread of exec, finishing of that method will be waited by parent thread.
     *
     * @throws InterruptedException when operation was interrupted
     * @throws IOException on errors
     */
    public abstract void startup() throws IOException, InterruptedException;

    /**
     * Run execution after startup.
     *    Executed in thread of exec.
     *
     * @return exit code of run operation
     * @throws InterruptedException when operation was interrupted
     * @throws IOException on errors
     */
    public abstract int run() throws IOException, InterruptedException;

    /**
     * Shutdown execution.
     *    Executed in thread of exec when run is done.
     *
     * @throws InterruptedException when operation was interrupted
     * @throws IOException on errors
     */
    public abstract void shutdown() throws IOException, InterruptedException;

    /**
     * Set stdout consumer of component.
     *
     * @param o consumer of stdout to set
     * @return this
     */
    public AndroidVirtualCmdExecution withOut(@NonNull final Consumer<CharSequence> o) {
        return this;
    }

    /**
     * Set stderr consumer of component.
     *
     * @param o consumer of stderr to set
     * @return this
     */
    public AndroidVirtualCmdExecution withErr(@NonNull final Consumer<CharSequence> o) {
        return this;
    }

    /**
     * Set component environment.
     *
     * @param environment environment to set
     * @return this
     */
    public AndroidVirtualCmdExecution withEnv(@NonNull final Map<String, String> environment) {
        return this;
    }
}
