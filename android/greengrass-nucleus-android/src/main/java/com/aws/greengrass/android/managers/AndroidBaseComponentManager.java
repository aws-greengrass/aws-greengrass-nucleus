/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.managers;

import com.aws.greengrass.android.AndroidComponentControl;
import com.aws.greengrass.android.AndroidContextProvider;
import com.aws.greengrass.android.util.LogHelper;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.android.AndroidComponentManager;
import com.aws.greengrass.util.platforms.android.AndroidVirtualCmdExecution;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.Value;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.annotation.Nullable;

import static com.aws.greengrass.android.component.utils.Constants.ACTION_START_COMPONENT;
import static com.aws.greengrass.android.component.utils.Constants.EXIT_CODE_FAILED;
import static com.aws.greengrass.android.component.utils.Constants.EXIT_CODE_SUCCESS;

/**
 * Basic implementation of Manager of Android components.
 */
public class AndroidBaseComponentManager implements AndroidComponentManager {
    public static final String RUN_SERVICE_CMD = "#run_service";
    private static final String RUN_SERVICE_CMD_EXAMPLE = "#run_service [[[Package].ClassName] [StartIntent]] [-- Arg1 Arg2 ...]";

    public static final String STARTUP_SERVICE_CMD = "#startup_service";
    private static final String STARTUP_SERVICE_CMD_EXAMPLE = "#startup_service [[[Package].ClassName] [StartIntent]]] [-- Arg1 Arg2 ...]";

    public static final String SHUTDOWN_SERVICE_CMD = "#shutdown_service";
    private static final String SHUTDOWN_SERVICE_CMD_EXAMPLE = "#shutdown_service [[Package].ClassName]";
    private static final String ARGUMENT_SEPARATOR = "--";

    private static final String DEFAULT_CLASS_NAME = ".DefaultGreengrassComponentService";

    /** Time out to start component */
    private final long COMPONENT_STARTUP_MS = 5000;

    /** that Logger is backed to greengrass.log. */
    private final Logger classLogger;

    /** Android Content provider */
    private final AndroidContextProvider contextProvider;

    private final ConcurrentHashMap<String, AndroidComponentControl> startedComponents = new ConcurrentHashMap<>();

    @Value
    @AllArgsConstructor
    private class ComponentRunInfo {
        private final String className;
        private final String action;
        private final String[] arguments;
    }

    private abstract class BaseComponentExecution extends AndroidVirtualCmdExecution {
        // set by constructor
        protected final String packageName;
        protected final String className;
        protected final Logger logger;

        // set by methods
        protected Map<String, String> environment;
        protected Consumer<CharSequence> stdout;
        protected Consumer<CharSequence> stderr;

        BaseComponentExecution(@NonNull String packageName, @NonNull String className, @Nullable Logger logger) {
            this.packageName = packageName;
            this.className = className;
            this.logger = logger;
        }

        @Override
        public AndroidVirtualCmdExecution withOut(@NonNull final Consumer<CharSequence> o) {
            this.stdout = o;
            return this;
        }

        @Override
        public AndroidVirtualCmdExecution withErr(@NonNull final Consumer<CharSequence> o) {
            this.stderr = o;
            return this;
        }

        @Override
        public AndroidVirtualCmdExecution withEnv(@NonNull final Map<String, String> environment) {
            this.environment = environment;
            return this;
        }
    }

    private class RunExecution extends BaseComponentExecution {
        protected final String action;
        protected final String[] arguments;
        protected final String componentId;

        RunExecution(@NonNull String packageName, @NonNull String className, @NonNull String action,
                     @Nullable String[] arguments, @Nullable Logger logger) {
            super(packageName, className, logger);
            this.action = action;
            this.arguments = arguments;
            this.componentId = getComponentId(packageName, className);
        }

        @Override
        public void startup() throws IOException, InterruptedException {
            startService(packageName, className, action, arguments, environment, logger, stdout, stderr);
        }

        @Override
        public int run() throws IOException, InterruptedException {
            AndroidComponentControl control = startedComponents.get(componentId);
            if (control != null) {
                return control.waitCompletion();
            } else {
                logger.atError().kv("package", packageName).kv("class", className)
                        .log("Component is not started");
                return EXIT_CODE_FAILED;
            }
        }

        public void shutdown() throws IOException, InterruptedException {
            stopService(packageName, className, logger);
        }
    }

    private class StartExecution extends RunExecution {

        StartExecution(@NonNull String packageName, @NonNull String className,
                       @NonNull String action, @Nullable String[] arguments,
                       @Nullable Logger logger) {
            super(packageName, className, action, arguments, logger);
        }

        @Override
        public void startup() throws IOException, InterruptedException {
            startService(packageName, className, action, arguments, environment, logger, stdout, stderr);
        }
    }

    private class StopExecution extends BaseComponentExecution {

        StopExecution(@NonNull String packageName, @NonNull String className,
                      @Nullable Logger logger) {
            super(packageName, className, logger);
        }

        @Override
        public void startup() throws IOException, InterruptedException {
            stopService(packageName, className, logger);
        }
    }

    /**
     * Creates instance of AndroidBaseComponentManager.
     *
     * @param contextProvider context getter
     */
    public AndroidBaseComponentManager(AndroidContextProvider contextProvider) {
        classLogger = LogHelper.getLogger(contextProvider.getContext().getFilesDir(), getClass());
        this.contextProvider = contextProvider;
    }

    /**
     * Start Android component as Foreground Service.
     *  Handles thread.isInterrupted() and InterruptedException and stop Android component.
     *
     * @param packageName Android Package to start.
     * @param className   Class name of the ForegroundService.
     * @param action      Action of Intent to send
     * @param arguments Command line arguments
     * @param environment Component environment
     * @param logger component's logger
     * @param stdout consumer of stdout
     * @param stderr consumer of stderr
     * @throws InterruptedException when thread was interrupted
     */
    @Override
    public void startService(@NonNull String packageName, @NonNull String className,
                             @NonNull String action, @Nullable String[] arguments,
                             Map<String, String> environment, @Nullable Logger logger,
                             @Nullable Consumer<CharSequence> stdout,
                             @Nullable Consumer<CharSequence> stderr)
            throws InterruptedException {
        // setup logger if missing
        if (logger == null) {
            logger = classLogger;
        }

        // shutdownService(packageName, className, logger);

        final String componentId = getComponentId(packageName, className);
        AndroidComponentControl control
                = new AndroidBaseComponentControl(contextProvider, packageName, className, action,
                arguments, environment, logger, stdout, stderr);
        control.startup(COMPONENT_STARTUP_MS);
        startedComponents.put(componentId, control);
    }

    /**
     * Stop component.
     * Handles thread.isInterrupted() and InterruptedException and stop Android component.
     *
     * @param packageName Android Package to start.
     * @param className   Class name of the ForegroundService.
     * @param logger component's logger
     * @throws InterruptedException when thread was interrupted
     */
    @Override
    public void stopService(@NonNull String packageName, @NonNull String className,
                            @Nullable Logger logger)
            throws InterruptedException {

        // setup logger if missing
        if (logger == null) {
            logger = classLogger;
        }

        final String componentId = getComponentId(packageName, className);
        AndroidComponentControl control = startedComponents.remove(componentId);
        if (control != null) {
            control.shutdown(COMPONENT_STARTUP_MS);
        } else {
            logger.atDebug().kv("package", packageName).kv("class", className)
                    .log("Component is not started");
        }
    }


    /**
     * Get execution for runService method.
     *
     * @param cmdLine #run_service command line
     * @param packageName Component's name
     * @param logger component's logger
     * @return Callable Object to call runService()
     */
    @Override
    public AndroidVirtualCmdExecution getComponentRunner(@NonNull String cmdLine,
                                                         @NonNull String packageName,
                                                         @Nullable Logger logger) {
        ComponentRunInfo runInfo = parseRunCmdLine(cmdLine, packageName, RUN_SERVICE_CMD, RUN_SERVICE_CMD_EXAMPLE);
        return new RunExecution(packageName, runInfo.className, runInfo.action, runInfo.arguments, logger);
    }

    /**
     * Get execution for startService method.
     *
     * @param cmdLine #run_service command line
     * @param packageName Component's name
     * @param logger component's logger
     * @return Callable Object to call startService()
     */
    @Override
    public AndroidVirtualCmdExecution getComponentStarter(
            @NonNull String cmdLine,
            @NonNull String packageName,
            @Nullable Logger logger) {
        ComponentRunInfo runInfo = parseRunCmdLine(cmdLine, packageName, STARTUP_SERVICE_CMD,
                STARTUP_SERVICE_CMD_EXAMPLE);

        AndroidVirtualCmdExecution starter = new StartExecution(packageName,
                runInfo.className, runInfo.action, runInfo.arguments, logger);

        return starter;
    }

    /**
     * Get execution for stopService method.
     *
     * @param cmdLine #run_service command line
     * @param packageName Component's name
     * @param logger component's logger
     * @return Callable Object to call shutdownService()
     */
    @Override
    public AndroidVirtualCmdExecution getComponentStopper(@NonNull String cmdLine,
                                               @NonNull String packageName,
                                               @Nullable Logger logger) {
        String className = parseShutdownCmdLine(cmdLine, packageName, SHUTDOWN_SERVICE_CMD,
                SHUTDOWN_SERVICE_CMD_EXAMPLE);
        return new StopExecution(packageName, className, logger);
    }

    /**
     * Parse Run/Startup command line.
     *
     * @param cmdLine command line to parse
     * @param packageName name of package
     * @param expected expected command
     * @param example example of command
     * @return component run info
     */
    private ComponentRunInfo parseRunCmdLine(@NonNull String cmdLine,
                                                 @NonNull String packageName,
                                                 @NonNull String expected,
                                                 @NonNull String example) {
        // check raw command line
        if (Utils.isEmpty(cmdLine)) {
            throw new IllegalArgumentException("Expected " + expected +" command but got empty line");
        }

        // split to lexemes
        String[] cmdParts = CmdParser.parse(cmdLine);
        if (cmdParts.length < 1) {
            throw new IllegalArgumentException("Invalid " + expected + " command line, expected "
                    + example);
        }

        // check command lexeme
        String cmd = cmdParts[0];
        if (!expected.equals(cmd)) {
            throw new IllegalArgumentException("Unexpected command, expected " + example);
        }

        // first get component's arguments
        String[] arguments = null;
        for(int i = 1; i < cmdParts.length; i++) {
            if (ARGUMENT_SEPARATOR.equals(cmdParts[i])) {
                arguments = Arrays.copyOfRange(cmdParts, i + 1, cmdParts.length);
                cmdParts = Arrays.copyOfRange(cmdParts, 0, i);
                break;
            }
        }

        if (cmdParts.length > 3) {
            throw new IllegalArgumentException("Too many parameters of " + expected +
                    " command line, expected " + example);
        }

        // parse class name if any
        String className;
        if (cmdParts.length >= 2) {
            className = cmdParts[1];
        } else {
            className = DEFAULT_CLASS_NAME;
        }

        if (className.startsWith(".")) {
            className = packageName + className;
        }

        // parse action if any
        String action;
        if (cmdParts.length >= 3) {
            action = cmdParts[2];
        } else {
            action = ACTION_START_COMPONENT;
        }

        return new ComponentRunInfo(className, action, arguments);
    }

    /**
     * Parse Run/Startup command line.
     *
     * @param cmdLine command line to parse
     * @param packageName name of package
     * @param expected expected command
     * @param example example of command
     * @return FQ class name
     */
    private String parseShutdownCmdLine(@NonNull String cmdLine,
                                                 @NonNull String packageName,
                                                 @NonNull String expected,
                                                 @NonNull String example) {

        if (Utils.isEmpty(cmdLine)) {
            throw new IllegalArgumentException("Expected " + expected +" command but got empty line");
        }

        String[] cmdParts = CmdParser.parse(cmdLine);
        if (cmdParts.length < 1 || cmdParts.length > 2) {
            throw new IllegalArgumentException("Invalid " + expected + " command line, expected "
                    + example);
        }

        String cmd = cmdParts[0];
        if (!expected.equals(cmd)) {
            throw new IllegalArgumentException("Unexpected command, expected " + example);
        }

        String className;
        if (cmdParts.length == 2) {
            className = cmdParts[1];
        } else {
            className = DEFAULT_CLASS_NAME;
        }

        if (className.startsWith(".")) {
            className = packageName + className;
        }

        return className;
    }

    /**
     * Gets Id of component for start/stop match.
     *
     * @param packageName  name of package
     * @param className FQ of Foreground Service class
     * @return id of component.
     */
    private String getComponentId(@NonNull String packageName, @NonNull String className) {
        return packageName + ";" + className;
    }
}
