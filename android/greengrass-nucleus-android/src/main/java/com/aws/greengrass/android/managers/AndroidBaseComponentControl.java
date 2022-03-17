/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.managers;

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
import androidx.core.content.ContextCompat;
import com.aws.greengrass.android.AndroidComponentControl;
import com.aws.greengrass.android.AndroidContextProvider;
import com.aws.greengrass.logging.api.Logger;
import lombok.NonNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.annotation.Nullable;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static com.aws.greengrass.android.component.utils.Constants.EXIT_CODE_FAILED;
import static com.aws.greengrass.android.component.utils.Constants.EXIT_CODE_KILLED;
import static com.aws.greengrass.android.component.utils.Constants.EXTRA_ARGUMENTS;
import static com.aws.greengrass.android.component.utils.Constants.EXTRA_COMPONENT_ENVIRONMENT;
import static com.aws.greengrass.android.component.utils.Constants.EXTRA_MASTER_PACKAGE;
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

public class AndroidBaseComponentControl implements AndroidComponentControl {
    private static final String PACKAGE_NAME = "package";
    private static final String CLASS_NAME = "class";
    private static final String STATUS_NAME = "status";

    // inputs
    private final AndroidContextProvider contextProvider;
    private final String packageName;
    private final String className;
    private final String action;
    private final String[] arguments;
    private final Map<String, String> environment;
    private final Logger logger;
    private final Consumer<CharSequence> stdout;
    private final Consumer<CharSequence> stderr;


    // runtime variables
    private AtomicReference<PrivateLooper> looper = new AtomicReference<>();
    private final AtomicReference<ComponentStatus> status
            = new AtomicReference(ComponentStatus.UNKNOWN);
    private final AtomicInteger exitCode = new AtomicInteger(EXIT_CODE_FAILED);
    private final AtomicInteger pid = new AtomicInteger(-1);
    private final AtomicBoolean isBound = new AtomicBoolean(false);

    //  created and destroyed in ServiceConnection methods
    private AtomicReference<Messenger> messengerService = new AtomicReference<>();

    // both created and dropped in looper thread
    private AtomicReference<Looper> msgLooper = new AtomicReference<>();
    private AtomicReference<Messenger> replyMessenger = new AtomicReference<>();

    private enum ComponentStatus {
        UNKNOWN, STOPPED, STARTED, AUTH_FAILED, EXITED, CRASHED
    }

    AndroidBaseComponentControl(AndroidContextProvider contextProvider,
                                @NonNull String packageName, @NonNull String className,
                                @NonNull String action, @Nullable String[] arguments,
                                @Nullable Map<String, String> environment, @Nullable Logger logger,
                                @Nullable Consumer<CharSequence> stdout,
                                @Nullable Consumer<CharSequence> stderr) {
        this.contextProvider = contextProvider;
        this.packageName = packageName;
        this.className = className;
        this.action = action;
        this.arguments = arguments;
        this.environment = environment;
        this.logger = logger;
        this.stdout = stdout;
        this.stderr = stderr;
    }

    /**
     * Start component, bind service, waiting for first response for time out.
     *  After that wait until component terminates.
     *  Before leave method - send STOP command to component if possible.
     *  Handles thread.isInterrupted() and InterruptedException and stop Android component.
     * @param msTimeout timeout for first response from component
     * @return exitCode of component
     * @throws RuntimeException on errors
     * @throws InterruptedException when current thread has been interrupted
     */
    @Override
    public int run(long msTimeout) throws RuntimeException, InterruptedException {
        PrivateLooper localLooper = null;
        try {
            localLooper = start(msTimeout);
            int exitCode = localLooper.waitCompletion();
            logger.atDebug().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                    .log("Component finished with exitCode {}", exitCode);
            return exitCode;
        } finally {
            if (localLooper != null) {
                localLooper.terminateComponent(msTimeout);
            }
        }
    }

    /**
     * Start component, bind service, waiting for first response for time out.
     *  Save looper for future references in shutdown.
     *  Handles thread.isInterrupted() and InterruptedException and stop Android component.
     * @param msTimeout timeout for first response from component
     * @throws RuntimeException on errors
     * @throws InterruptedException when current thread has been interrupted
     */
    @Override
    public void startup(long msTimeout) throws RuntimeException, InterruptedException {
        shutdown(msTimeout);
        PrivateLooper localLooper = start(msTimeout);
        looper.set(localLooper);
    }

    /**
     * Shutdown component.
     *  Should be called after startup().
     *
     * @param msTimeout timeout for response from component
     * @throws RuntimeException on errors
     * @throws InterruptedException when current thread has been interrupted
     */
    @Override
    public void shutdown(long msTimeout) throws RuntimeException, InterruptedException {
        PrivateLooper localLooper = looper.getAndSet(null);
        if (localLooper != null) {
            logger.atDebug().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                    .log("Stopping component");
            localLooper.terminateComponent(msTimeout);
            logger.atDebug().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                    .log("Component stopped");
        }
    }

    /**
     * Startup component and return looper.
     *
     * @param msTimeout timeout for first response from component
     * @return looper instance of started component
     * @throws RuntimeException on errors
     * @throws InterruptedException when current thread has been interrupted
     */
    private PrivateLooper start(long msTimeout) throws RuntimeException, InterruptedException {
        if (Thread.interrupted()) {
            logger.atDebug().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                    .log("Was interrupted before component start");
            throw new InterruptedException("Was interrupted before component start");
        }

        logger.atDebug().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                .log("Starting component");

        PrivateLooper localLooper = null;
        try {
            Context context = contextProvider.getContext();
            Intent intent = buildIntent(context, packageName, className, action, arguments,
                    environment, logger);
            ContextCompat.startForegroundService(context, intent);
            logger.atDebug().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                    .kv("intent", intent.getAction())
                    .log("intent sent");

            localLooper = new PrivateLooper(intent);
            ComponentStatus status = localLooper.startCommunication(msTimeout);
            if (status == ComponentStatus.STARTED) {
                logger.atDebug().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                        .log("Component started");
                return localLooper;
            } else {
                logger.atError().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                        .log("Couldn't start Android component in {} ms", msTimeout);
                throw new RuntimeException("Couldn't start Android component");
            }
        } catch (Throwable e) {
            if (localLooper != null) {
                localLooper.terminateComponent(msTimeout);
            }
            throw e;
        }
    }

    private static Intent buildIntent(@NonNull Context context, @NonNull String packageName,
                                      @NonNull String className, @NonNull String action,
                                      @Nullable String[] arguments,
                                      @Nullable Map<String, String> environment,
                                      @NonNull Logger logger)
            throws RuntimeException {

        Intent intent = new Intent();
        intent.setFlags(FLAG_ACTIVITY_NEW_TASK);
        intent.setAction(action);
        intent.setComponent(new ComponentName(packageName, className));
        List<ResolveInfo> matches = context.getPackageManager().queryIntentServices(intent, 0);
        // TODO: add service check
        if (matches.size() == 1) {
            // Add environmental variables
            if (environment != null && !environment.isEmpty()) {
                // Omit JAVA_HOME and HOME since they are not relevant for Android
                environment.remove("JAVA_HOME");
                environment.remove("HOME");

                intent.putExtra(EXTRA_COMPONENT_ENVIRONMENT, (HashMap) environment);
                intent.putExtra(EXTRA_MASTER_PACKAGE, context.getPackageName());
                intent.putExtra(EXTRA_ARGUMENTS, arguments);
            }
        } else {
            handleIntentResolutionError(matches, packageName, className, logger);
        }
        return intent;
    }

    private static void handleIntentResolutionError(List<ResolveInfo> matches, @NonNull String packageName,
                                             @NonNull String className, @NonNull Logger logger)
            throws RuntimeException {
        if (matches.size() == 0) {
            logger.atError().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                    .log("Service does not found");
            throw new RuntimeException("Service with package " + packageName + " and class "
                    + className + " couldn't found");
        } else {
            logger.atError().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                    .log("Ambiguity in service");
            throw new RuntimeException("Ambiguity in service with package " + packageName
                    + " and class " + className + " found " + matches.size() + " matches");
        }
    }

    private class IncomingHandler extends Handler {

        IncomingHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            try {
                switch (msg.what) {
                    case LIFECYCLE_MSG_OBSERVER_AUTH_FAILED:
                        logger.atError().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                                .log("Component's service declined access to its lifecycle");
                        exitCode.set(EXIT_CODE_FAILED); // TODO: maybe there's a better code for this case
                        setStatus(ComponentStatus.AUTH_FAILED);
                        break;

                    case LIFECYCLE_MSG_SERVICE_STARTED:
                        pid.set(msg.arg1);
                        logger.atDebug().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                                .kv("pid", pid).log("Component service started successfully");
                        setStatus(ComponentStatus.STARTED);
                        break;

                    case LIFECYCLE_MSG_SERVICE_IS_NOT_RUNNING:
                        logger.atDebug().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                                .log("Component service is not running currently");
                        exitCode.set(EXIT_CODE_FAILED); // TODO: maybe there's a better code for this case
                        setStatus(ComponentStatus.STOPPED);
                        break;

                    case LIFECYCLE_MSG_EXIT_CODE:
                        exitCode.set(msg.arg1);
                        logger.atDebug()
                                .kv(PACKAGE_NAME, packageName)
                                .kv(CLASS_NAME, className)
                                .kv("exitCode", exitCode)
                                .log("Component service has terminated gracefully");
                        setStatus(ComponentStatus.EXITED);
                        break;

                    case LIFECYCLE_MSG_STDERR_LINES:
                        if (stderr != null) {
                            Bundle msgData = msg.getData();
                            String line = msgData.getString(LIFECYCLE_EXTRA_STDERR_LINE, "");
                            stderr.accept(line);
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
            } catch (Throwable e) {
                logger.atError().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                        .setCause(e).log("Exception in handleMessage");
            }
        }
    }

    private class PrivateServiceConnection implements ServiceConnection {
        @Override
        public void onBindingDied(ComponentName name) {
            logger.atDebug().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                    .log("Lifecycle Messenger died");
            exitCode.set(EXIT_CODE_KILLED);
            setStatus(ComponentStatus.CRASHED);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            logger.atDebug().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                    .log("Lifecycle Messenger could not be attached");
            exitCode.set(EXIT_CODE_FAILED);
            setStatus(ComponentStatus.CRASHED);
        }

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            messengerService.set(new Messenger(service));
            // Try to authorize and register this instance as lifecycle observer
            try {
                String token = null;
                if (environment != null) {
                    token = environment.get("SVCUID");
                }
                if (token == null) {
                    token = "";
                }
                logger.atDebug().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                        .kv("token", token.substring(0, 4) + "...")
                        .log("Lifecycle Messenger attached");
                Message msg = Message.obtain(null, LIFECYCLE_MSG_REGISTER_OBSERVER);
                msg.replyTo = replyMessenger.get();
                Bundle msgData = new Bundle();
                // Use SVCUID as authentication token
                msgData.putString(LIFECYCLE_EXTRA_OBSERVER_AUTH_TOKEN, token);
                msg.setData(msgData);
                messengerService.get().send(msg);
            } catch (Throwable e) {
                logger.atError().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                        .log("Component's service has crashed before we could do anything to it");
                exitCode.set(EXIT_CODE_FAILED);
                setStatus(ComponentStatus.CRASHED);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            messengerService.set(null);
            exitCode.set(EXIT_CODE_KILLED);
            setStatus(ComponentStatus.CRASHED);
            logger.atDebug().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                    .log("Lifecycle Messenger detached");
        }
    }

    private class PrivateLooper {
        private Intent intent = null;

        Thread thread = new Thread(() -> {
            ServiceConnection serviceConnection = null;
            logger.atDebug().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                    .log("Looper thread started");
            try {
                Looper.prepare();
                Looper looper = Looper.myLooper();
                msgLooper.set(looper);
                Handler handler = new IncomingHandler(looper);
                replyMessenger.set(new Messenger(handler));

                serviceConnection = new PrivateServiceConnection();
                // Bind Messenger to the service
                boolean bindSuccess = contextProvider.getContext()
                        .bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
                if (bindSuccess) {
                    isBound.set(true);
                    // Handle messages
                    logger.atDebug().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                            .log("bindService successful");
                    Looper.loop();
                } else {
                    logger.atError().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                            .log("Could not bind to service");
                    setStatus(ComponentStatus.UNKNOWN);
                }
            } catch (Throwable e) {
                logger.atError().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className).setCause(e)
                        .log("Exception in looper thread");
                exitCode.set(EXIT_CODE_FAILED);
            } finally {
                if (serviceConnection != null) {
                    contextProvider.getContext().unbindService(serviceConnection);
                }
                isBound.set(false);
                // Once we got here it means lifecycle is over, we don't need looper anymore
                replyMessenger.set(null);
                msgLooper.set(null);
            }
            logger.atDebug().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                    .log("Looper thread finished");
        });

        PrivateLooper(Intent intent) {
            super();
            this.intent = intent;
        }

        /**
         * Start looper, bind to service and initiate communication.
         *
         * @param msTimeout timeout in ms to receive STARTED response from component
         * @return status of component
         * @throws InterruptedException when thread has been interrupted
         */
        private ComponentStatus startCommunication(long msTimeout) throws InterruptedException {
            ComponentStatus localStatus;
            synchronized (status) {
                status.set(ComponentStatus.UNKNOWN);
                thread.start();
                status.wait(msTimeout);
                localStatus = status.get();
                if (localStatus != ComponentStatus.UNKNOWN) {
                    logger.atDebug()
                            .kv(PACKAGE_NAME, packageName)
                            .kv(CLASS_NAME, className)
                            .kv(STATUS_NAME, localStatus)
                            .log("Obtained components status");
                } else {
                    logger.atError()
                            .kv(PACKAGE_NAME, packageName)
                            .kv(CLASS_NAME, className)
                            .log("Failed to get component status in {} ms", msTimeout);

                }
            }
            return localStatus;
        }

        /**
         * Waiting until looper is finish the thread or until InterruptedException.
         *
         * @return exit code of component
         * @throws InterruptedException when current thread was interrupted
         */
        private int waitCompletion() throws InterruptedException {
            thread.join();
            return exitCode.get();
        }

        /**
         * Send STOP command if communication channel is opened, quit looper and join with it thread.
         *
         * @throws InterruptedException when thread has been interrupted
         */
        private void terminate() throws InterruptedException {
            logger.atDebug().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                    .log("Request terminate component");
            requestUnbind();
            //Thread.sleep(300);
            //thread.interrupt();
            thread.join();
        }

        private void terminateComponent(long msTimeout) throws RuntimeException, InterruptedException {
            sendExitRequest(msTimeout);
            terminate();
        }

        /**
         * Send EXIT command and waiting for response for time out.
         *
         */
        private void sendExitRequest(long msTimeout) throws RuntimeException {
            Messenger messenger = messengerService.get();
            Messenger replyTo = replyMessenger.get();
            if (messenger != null && replyTo != null) {
                try {
                    Message msg = Message.obtain(null, LIFECYCLE_MSG_REQUEST_EXIT);
                    msg.replyTo = replyTo;
                    messenger.send(msg);
                } catch (RemoteException e) {
                    logger.atError().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                            .setCause(e).log("Unable to terminate the service");
                    throw new RuntimeException("Unable to terminate the service", e);
                }
            } else {
                //throw new RuntimeException("Couldn't send EXIT command due to communication channel is closed");
            }

        }
    }

    private void setStatus(ComponentStatus status) {
        logger.atDebug().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                .kv(STATUS_NAME, status).log("Set status");
        // request to terminate looper loop
        if (status != ComponentStatus.STARTED) {
            requestUnbind();
        }

        synchronized (this.status) {
            this.status.set(status);
            this.status.notifyAll();
        }
    }

    private void requestUnbind() {
        if (isBound.getAndSet(false)) {
            logger.atDebug().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                    .log("Request unbind");
            Messenger messenger = messengerService.get();
            if (messenger != null) {
                try {
                    Message msg = Message.obtain(null, LIFECYCLE_MSG_UNREGISTER_OBSERVER);
                    msg.replyTo = replyMessenger.get();
                    messenger.send(msg);
                } catch (RemoteException e) {
                    logger.atError().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                            .setCause(e).log("Exception when requesting unbind");
                }
            }

            // Finally break Messenger loop and finish looper thread
            quit();
        }
    }

    /**
     * Quit looper.
     */
    private void quit() {
        logger.atDebug().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                .log("Request looper quit");
        Looper l = msgLooper.get();
        if (l != null) {
            l.quitSafely();
            logger.atDebug().kv(PACKAGE_NAME, packageName).kv(CLASS_NAME, className)
                    .log("Requested looper quit");
        }
    }
}
