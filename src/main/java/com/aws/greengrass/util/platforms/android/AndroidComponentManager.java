/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.util.Pair;
import lombok.NonNull;

import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * Manager of Nucleus Android components.
 */
public interface AndroidComponentManager {

    /**
     * Get execution to run service method.
     *
     * @param cmdLine #run_service command line
     * @param packageName Component's name
     * @param logger component's logger
     * @return Callable Object to call runService()
     */
    AndroidVirtualCmdExecution getComponentRunner(@NonNull String cmdLine,
                                       @NonNull String packageName,
                                       @Nullable Logger logger);

    /**
     * Start Android component as Foreground Service.
     *  Handles thread.isInterrupted() and InterruptedException and stop Android component.
     * @param packageName Android Package to start.
     * @param className   Class name of the ForegroundService.
     * @param action      Action of Intent to send
     * @param arguments   Command line arguments
     * @param environment Component environment
     * @param logger component's logger
     * @param stdout consumer of stdout
     * @param stderr consumer of stderr
     * @throws InterruptedException when thread was interrupted
     */
    void startService(@NonNull String packageName, @NonNull String className,
                      @NonNull String action, @Nullable String[] arguments,
                      Map<String, String> environment, @Nullable Logger logger,
                      @Nullable Consumer<CharSequence> stdout,
                      @Nullable Consumer<CharSequence> stderr)
            throws InterruptedException;

    /**
     * Get execution for startService methods.
     *
     * @param cmdLine #run_service command line
     * @param packageName Component's name
     * @param logger component's logger
     * @return Callable Object to call startService()
     */
    AndroidVirtualCmdExecution getComponentStarter(@NonNull String cmdLine,
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
     * Get execution for shutdownService method.
     *
     * @param cmdLine #run_service command line
     * @param packageName Component's name
     * @param logger component's logger
     * @return Callable Object to call shutdownService()
     */
    AndroidVirtualCmdExecution getComponentStopper(@NonNull String cmdLine,
                                        @NonNull String packageName,
                                        @Nullable Logger logger);
}
