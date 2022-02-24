/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.platforms.Platform;
import lombok.NonNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static com.aws.greengrass.android.component.utils.Constants.ACTION_START_COMPONENT;
import static com.aws.greengrass.android.component.utils.Constants.EXIT_CODE_FAILED;
import static com.aws.greengrass.android.component.utils.Constants.EXIT_CODE_KILLED;
import static com.aws.greengrass.android.component.utils.Constants.EXIT_CODE_SUCCESS;
import static com.aws.greengrass.android.component.utils.Constants.EXIT_CODE_TERMINATED;
import static com.aws.greengrass.android.component.utils.Constants.EXTRA_COMPONENT_ENVIRONMENT;
import static com.aws.greengrass.android.component.utils.Constants.LIFECYCLE_EXTRA_OBSERVER_AUTH_TOKEN;
import static com.aws.greengrass.android.component.utils.Constants.LIFECYCLE_EXTRA_STDERR_LINE;
import static com.aws.greengrass.android.component.utils.Constants.LIFECYCLE_EXTRA_STDOUT_LINE;
import static com.aws.greengrass.android.component.utils.Constants.LIFECYCLE_MSG_EXIT_CODE;
import static com.aws.greengrass.android.component.utils.Constants.LIFECYCLE_MSG_OBSERVER_AUTH_FAILED;
import static com.aws.greengrass.android.component.utils.Constants.LIFECYCLE_MSG_REGISTER_OBSERVER;
import static com.aws.greengrass.android.component.utils.Constants.LIFECYCLE_MSG_REQUEST_EXIT;
import static com.aws.greengrass.android.component.utils.Constants.LIFECYCLE_MSG_SERVICE_IS_NOT_RUNNING;
import static com.aws.greengrass.android.component.utils.Constants.LIFECYCLE_MSG_SERVICE_STARTED;
import static com.aws.greengrass.android.component.utils.Constants.LIFECYCLE_MSG_STDERR_LINES;
import static com.aws.greengrass.android.component.utils.Constants.LIFECYCLE_MSG_STDOUT_LINES;
import static com.aws.greengrass.android.component.utils.Constants.LIFECYCLE_MSG_UNREGISTER_OBSERVER;

public class AndroidComponentExec extends AndroidGenericExec {

    private static final Logger staticLogger = LogManager.getLogger(AndroidComponentExec.class);

    public static final String CMD_STARTUP_SERVICE = "#startup_service";
    public static final String CMD_SHUTDOWN_SERVICE = "#shutdown_service";
    public static final String CMD_RUN_SERVICE = "#run_service";

    private int pid = -1;
    private AndroidProcess androidProcess;

    @Nullable
    @Override
    public Path which(String fn) {
        return null;
    }

    @Override
    public String[] getCommand() {
        // Put all arguments into one dimensional list
        ArrayList<String> cmdArgs = new ArrayList<>();
        for (String cmd : cmds) {
            String[] args = cmd.split(" ");
            cmdArgs.addAll(Arrays.asList(args));
        }
        return cmdArgs.toArray(new String[cmdArgs.size()]);
    }

    @Override
    protected Process createProcess() throws IOException {
        throw new UnsupportedOperationException("Cannot create plain process on Android to run components");
    }

    protected AndroidProcess createAndroidProcess() throws IOException {
        try {
            String[] cmdArgs = getCommand();

            // Determine execution type
            String action = "";
            String baseCmd = cmdArgs[0];

            // Get package name
            String packageName = cmdArgs[1];

            // Get full class name
            String className = cmdArgs[2];
            if (className.startsWith(".")) {
                className = packageName + className;
            }

            // Now start the service
            return new AndroidProcess(packageName, className, baseCmd);
        } catch (IndexOutOfBoundsException e) {
            staticLogger.atError().setCause(e).log("Failed to parse command line arguments");
            throw new IOException("Failed to parse command line arguments", e);
        }
    }

    @Override
    public int getPid() {
        return pid;
    }

    @Override
    public synchronized void close() throws IOException {
        if (isClosed.get()) {
            return;
        }
        AndroidProcess p = androidProcess;
        if (p == null || !p.isAlive()) {
            return;
        }

        try {
            p.close();
            if (!p.waitFor(5, TimeUnit.SECONDS) && !isClosed.get()) {
                throw new IOException("Could not stop " + this);
            }
        } catch (InterruptedException e) {
            //FIXME: If we're interrupted make sure to kill the process before returning
        }
    }

    @Override
    public String cmd(String... command) throws InterruptedException, IOException {
        throw new UnsupportedOperationException("cmd method is not supported for AndroidComponentExec");
    }

    @Override
    public String sh(String command) throws InterruptedException, IOException {
        throw new UnsupportedOperationException("sh method is not supported for AndroidComponentExec");
    }

    @Override
    public String sh(File dir, String command) throws InterruptedException, IOException {
        throw new UnsupportedOperationException("sh method is not supported for AndroidComponentExec");
    }

    @Override
    public String sh(Path dir, String command) throws InterruptedException, IOException {
        throw new UnsupportedOperationException("sh method is not supported for AndroidComponentExec");
    }

    @Override
    public boolean successful(boolean ignoreStderr, String command)
            throws InterruptedException, IOException {
        throw new UnsupportedOperationException("successful is not supported for AndroidComponentExec");
    }

    @Override
    public boolean successful(boolean ignoreStderr) throws InterruptedException, IOException {
        exec();
        return (ignoreStderr || androidProcess.getStderrNLines() == 0)
                && androidProcess.exitValue() == EXIT_CODE_SUCCESS;
    }

    @Override
    public Exec cd(File f) {
        staticLogger.atDebug().log("Setting of working directory is not possible on Android. Skipped");
        return this;
    }

    @Override
    public File cwd() {
        staticLogger.atDebug().log("Attempt to determine component's working directory - not relevant for Android");
        return null;
    }

    @Override
    public Exec withShell() {
        throw new UnsupportedOperationException("withShell is not supported for AndroidComponentExec");
    }

    @Override
    public Exec usingShell(String shell) {
        staticLogger.atDebug().log("Shell execution is not supported by AndroidComponentExec. Skipped");
        return this;
    }

    @Override
    public Optional<Integer> exec() throws InterruptedException, IOException {
        // Don't run anything if the current thread is currently interrupted
        if (Thread.currentThread().isInterrupted()) {
            logger.atWarn().kv("command", this).log("Refusing to execute because the active thread is interrupted");
            throw new InterruptedException();
        }

        androidProcess = createAndroidProcess();
        logger.debug("Created thread with tid {}", androidProcess.getId());

        if (whenDone == null) {
            try {
                if (timeout < 0) {
                    androidProcess.waitFor();
                } else {
                    if (!androidProcess.waitFor(timeout, timeunit)) {
                        (stderr == null ? stdout : stderr).accept("\n[TIMEOUT]\n");
                        androidProcess.destroy();
                    }
                }
            } catch (InterruptedException ie) {
                // We just got interrupted by something like the cancel(true) in setBackingTask
                // Give the process a touch more time to exit cleanly
                if (!androidProcess.waitFor(5, TimeUnit.SECONDS)) {
                    (stderr == null ? stdout : stderr).accept("\n[TIMEOUT after InterruptedException]\n");
                    androidProcess.destroyForcibly();
                }
                throw ie;
            }
            if (androidProcess.waitFor(5, TimeUnit.SECONDS)) {
                return Optional.of(androidProcess.exitValue());
            }
        }
        return Optional.empty();
    }

    void setClosed() {
        if (!isClosed.get()) {
            final IntConsumer wd = whenDone;
            final int exit = androidProcess == null ? -1 : androidProcess.exitValue();
            isClosed.set(true);
            if (wd != null) {
                wd.accept(exit);
            }
        }
    }

    @Override
    public boolean isRunning() {
        return androidProcess == null ? !isClosed.get() : androidProcess.isAlive();
    }

    private class AndroidProcess extends Thread {

        private String packageName;
        private String className;
        private String baseCmd;
        private String action;
        private Looper msgLooper = null;
        private int exitCode = EXIT_CODE_FAILED;
        private int stderrNLines = 0;

        private final AtomicBoolean startupLock = new AtomicBoolean(false);

        /** Messenger for communicating with component's service. */
        private Messenger messengerService = null;

        /** Flag indicating whether we have called bind on the service. */
        private boolean isBound = false;

        /**
         * Target we publish for component's service to send messages to Nucleus.
         */
        private Messenger messengerMessenger = null;

        public AndroidProcess(String packageName, String className, String baseCommand) throws IOException {
            this.packageName = packageName;
            this.className = className;
            this.baseCmd = baseCommand;

            if (baseCmd.equals(CMD_STARTUP_SERVICE)) {
                this.action = ACTION_START_COMPONENT;
            } else if (baseCmd.equals(CMD_SHUTDOWN_SERVICE)) {
                this.action = ""; // Action is not used for this command
            } else if (baseCmd.equals(CMD_RUN_SERVICE)) {
                this.action = ACTION_START_COMPONENT;
            } else {
                // Unknown execution type, abort
                staticLogger.atError().kv("command", baseCmd).log("Unknown command");
                throw new IOException("Unknown execution command - " + baseCmd);
            }

            this.start();

            // Wait until we obtain the pid or a startup error happens
            try {
                synchronized (startupLock) {
                    startupLock.wait(5000);
                }
            } catch (InterruptedException ignore) {
            }

            staticLogger.atDebug().log("Android component process created");
        }

        public int exitValue() {
            return exitCode;
        }

        public int getStderrNLines() {
            return stderrNLines;
        }

        @Override
        public void run() {
            // Prepare start intent
            Intent intent = new Intent();
            intent.setFlags(FLAG_ACTIVITY_NEW_TASK);
            intent.setAction(action);
            intent.setComponent(new ComponentName(packageName, className));

            // Add environmental variables
            if (environment != null && !environment.isEmpty()) {
                // Omit JAVA_HOME and HOME since they are not relevant for Android
                environment.remove("JAVA_HOME");
                environment.remove("HOME");

                intent.putExtra(EXTRA_COMPONENT_ENVIRONMENT, (HashMap) environment);
            }

            // Check if specified package exists
            Context context = ((AndroidPlatform)Platform.getInstance()).getAndroidContextProvider().getContext();
            List<ResolveInfo> matches = context.getPackageManager().queryIntentServices(intent, 0);
            if (matches.size() == 1) {
                if (baseCmd.equals(CMD_STARTUP_SERVICE) || baseCmd.equals(CMD_RUN_SERVICE)) {
                    // Start as Foreground Service first
                    ContextCompat.startForegroundService(context, intent);
                }

                // Prepare a looper to establish Messenger connection with the component's service
                Thread messengerLooper = new Thread(() -> {
                    Looper.prepare();
                    msgLooper = Looper.myLooper();
                    messengerMessenger = new Messenger(new IncomingHandler(msgLooper));

                    // Bind Messenger to the service
                    context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
                    isBound = true;

                    // Handle messages
                    Looper.loop();

                    // Once we got here it means lifecycle is over, we don't need looper anymore
                    msgLooper = null;
                });

                try {
                    synchronized (startupLock) {
                        // Initiate connection of the lifecycle Messenger
                        startupLock.set(false);
                        messengerLooper.start();
                        startupLock.wait(5000);
                        if (!startupLock.get()) {
                            staticLogger.atDebug().log("Unable to receive response from the component's service");
                            exitCode = EXIT_CODE_FAILED;
                            unbindComponentService();
                        } else {
                            staticLogger.atDebug().log("Received startup response from the component's service");

                            if (baseCmd.equals(CMD_STARTUP_SERVICE)) {
                                // We are done, service has started and now we can detach from it
                                exitCode = EXIT_CODE_SUCCESS;
                                unbindComponentService();
                            } else if (baseCmd.equals(CMD_SHUTDOWN_SERVICE)) {
                                close();
                            }
                        }
                    }

                    // Wait for the message looper to finalize all activities
                    messengerLooper.join();
                } catch (InterruptedException e) {
                    staticLogger.atDebug().setCause(e)
                            .log("Lifecycle observer was requested to exit");
                    exitCode = EXIT_CODE_TERMINATED;
                } finally {
                    setClosed();
                }
            } else {
                handleIntentResolutionError(matches, packageName, className);
            }
        }

        private void handleIntentResolutionError(List<ResolveInfo> matches,
                                                 @NonNull String packageName,
                                                 @NonNull String className) {
            if (matches.size() == 0) {
                staticLogger.atError()
                        .log("Service with package {} and class {} couldn't found",
                                packageName, className);
            } else {
                staticLogger.atError()
                        .log("Ambiguity in service with package {} and class {} found {} matches",
                                packageName, className, matches.size());
            }
        }

        public boolean waitFor() throws InterruptedException {
            if (isAlive()) {
                join();
                return !isAlive();
            } else {
                return true;
            }
        }

        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (isAlive()) {
                unit.timedJoin(this, timeout);
                return !isAlive();
            } else {
                return true;
            }
        }

        public void close() {
            if (isAlive() && messengerService != null) {
                try {
                    Message msg = Message.obtain(null, LIFECYCLE_MSG_REQUEST_EXIT);
                    msg.replyTo = messengerMessenger;
                    messengerService.send(msg);
                } catch (RemoteException e) {
                    staticLogger.atError().setCause(e).log("Unable to terminate the service");
                }
            }
        }

        public void destroyForcibly() {
            interrupt();
        }

        /**
         * Handler of incoming messages from service.
         */
        private class IncomingHandler extends Handler {

            public IncomingHandler(Looper l) {
                super(l);
            }

            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case LIFECYCLE_MSG_OBSERVER_AUTH_FAILED:
                        staticLogger.atDebug().log("Component's service declined access to its lifecycle");
                        exitCode = EXIT_CODE_FAILED; //TODO: maybe there's a better code for this case
                        unbindComponentService();
                        synchronized (startupLock) {
                            startupLock.set(true);
                            startupLock.notifyAll();
                        }
                        break;

                    case LIFECYCLE_MSG_SERVICE_STARTED:
                        staticLogger.atDebug().log("Component service started successfully");
                        pid = msg.arg1;
                        staticLogger.atDebug().log("Component service pid obtained: {}", pid);
                        synchronized (startupLock) {
                            startupLock.set(true);
                            startupLock.notifyAll();
                        }
                        break;

                    case LIFECYCLE_MSG_SERVICE_IS_NOT_RUNNING:
                        staticLogger.atDebug().log("Component service is not running currently");
                        exitCode = EXIT_CODE_FAILED; //TODO: maybe there's a better code for this case
                        unbindComponentService();
                        synchronized (startupLock) {
                            startupLock.set(true);
                            startupLock.notifyAll();
                        }
                        break;

                    case LIFECYCLE_MSG_EXIT_CODE:
                        staticLogger.atDebug().log("Component service has terminated gracefully");
                        exitCode = msg.arg1;
                        unbindComponentService();
                        break;

                    case LIFECYCLE_MSG_STDERR_LINES:
                        if (stderr != null) {
                            Bundle msgData = msg.getData();
                            String line = msgData.getString(LIFECYCLE_EXTRA_STDERR_LINE, "");
                            stderr.accept(line);
                            stderrNLines++;
                        }
                        break;

                    case LIFECYCLE_MSG_STDOUT_LINES:
                        if (stdout != null) {
                            Bundle msgData = msg.getData();
                            String line = msgData.getString(LIFECYCLE_EXTRA_STDOUT_LINE, "");
                            stdout.accept(line);
                        }
                        break;

                    default:
                        super.handleMessage(msg);
                }
            }
        }

        private void unbindComponentService() {
            if (isBound) {
                if (messengerService != null) {
                    try {
                        Message msg = Message.obtain(null, LIFECYCLE_MSG_UNREGISTER_OBSERVER);
                        msg.replyTo = messengerMessenger;
                        messengerService.send(msg);
                    } catch (RemoteException e) {
                        // There is nothing special we need to do if the service
                        // has crashed.
                    }
                }

                // Detach our existing connection.
                Context context = ((AndroidPlatform)Platform.getInstance()).getAndroidContextProvider().getContext();
                context.unbindService(serviceConnection);
                isBound = false;

                // Finally stop Messenger loop
                if (msgLooper != null) {
                    msgLooper.quit();
                }
            }
        }

        /**
         * Class for interacting with the lifecycle interface of the service.
         */
        private ServiceConnection serviceConnection = new ServiceConnection() {

            @Override
            public void onServiceConnected(ComponentName className,
                                           IBinder service) {
                messengerService = new Messenger(service);
                staticLogger.atDebug().log("Lifecycle Messenger attached");
                // Try to authorize and register this AndroidComponentExec instance as lifecycle observer
                try {
                    Message msg = Message.obtain(null, LIFECYCLE_MSG_REGISTER_OBSERVER);
                    msg.replyTo = messengerMessenger;
                    Bundle msgData = new Bundle();
                    msgData.putString(LIFECYCLE_EXTRA_OBSERVER_AUTH_TOKEN,
                            environment.get("SVCUID")); // Use SVCUID as authentication token
                    msg.setData(msgData);
                    messengerService.send(msg);
                } catch (RemoteException e) {
                    staticLogger.atDebug().log("Component's service has crashed before we could do anything to it");
                    exitCode = EXIT_CODE_FAILED;
                    unbindComponentService();
                    synchronized (startupLock) {
                        startupLock.set(false);
                        startupLock.notifyAll();
                    }
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName className) {
                // This is called when the connection with the service has been
                // unexpectedly disconnected -- that is, its process crashed.
                staticLogger.atDebug().log("Lifecycle Messenger disconnected");
                messengerService = null;
                exitCode = EXIT_CODE_KILLED;
                unbindComponentService();
            }
        };
    }
}
