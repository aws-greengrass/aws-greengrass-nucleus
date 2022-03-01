/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import com.aws.greengrass.logging.api.Logger;
import lombok.NonNull;

import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * Manager of Nucleus Android components.
 */
public interface AndroidComponentManager {
    /**
     * Run Android component as Foreground Service.
     *  Handles thread.isInterrupted() and InterruptedException and stop Android component.
     * @param packageName Android Package to start.
     * @param className   Class name of the ForegroundService.
     * @param action      Action of Intent to send
     * @param environment Component environment
     * @param logger component's logger
     * @param stdout consumer of stdout
     * @param stderr consumer of stderr
     * @return exit code of component
     * @throws InterruptedException when thread was interrupted
     */
    int runService(@NonNull String packageName, @NonNull String className, @NonNull String action,
                   Map<String, String> environment, @Nullable Logger logger,
                   @Nullable Consumer<CharSequence> stdout, @Nullable Consumer<CharSequence> stderr)
            throws InterruptedException;

    /**
     * Get callable for runService method.
     *
     * @param cmdLine #run_service command line
     * @param packageName Component's name
     * @param logger component's logger
     * @return Callable Object to call runService()
     */
    AndroidCallable getComponentRunner(@NonNull String cmdLine,
                                       @NonNull String packageName,
                                       @Nullable Logger logger);

    /**
     * Start Android component as Foreground Service.
     *  Handles thread.isInterrupted() and InterruptedException and stop Android component.
     * @param packageName Android Package to start.
     * @param className   Class name of the ForegroundService.
     * @param action      Action of Intent to send
     * @param environment Component environment
     * @param logger component's logger
     * @param stdout consumer of stdout
     * @param stderr consumer of stderr
     * @throws InterruptedException when thread was interrupted
     */
    void startService(@NonNull String packageName, @NonNull String className,
                      @NonNull String action, Map<String, String> environment,
                      @Nullable Logger logger, @Nullable Consumer<CharSequence> stdout,
                      @Nullable Consumer<CharSequence> stderr)
            throws InterruptedException;

    /**
     * Get callable for startService method.
     *
     * @param cmdLine #run_service command line
     * @param packageName Component's name
     * @param logger component's logger
     * @return Callable Object to call startService()
     */
    AndroidCallable getComponentStarter(@NonNull String cmdLine,
                                       @NonNull String packageName,
                                       @Nullable Logger logger);

    /**
     * Shutdown component.
     *  Handles thread.isInterrupted() and InterruptedException and stop Android component.
     * @param packageName Android Package to start.
     * @param className   Class name of the ForegroundService.
     * @param logger component's logger
     * @throws InterruptedException when thread was interrupted
     */
    void stopService(@NonNull String packageName, @NonNull String className,
                     @Nullable Logger logger)
            throws InterruptedException;

    /**
     * Get callable for shutdownService method.
     *
     * @param cmdLine #run_service command line
     * @param packageName Component's name
     * @param logger component's logger
     * @return Callable Object to call shutdownService()
     * @throws RuntimeException on errors
     */
    AndroidCallable getComponentStopper(@NonNull String cmdLine,
                                        @NonNull String packageName,
                                        @Nullable Logger logger);
}
