/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import com.aws.greengrass.logging.api.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.aws.greengrass.android.component.utils.Constants.EXIT_CODE_FAILED;
import static com.aws.greengrass.android.component.utils.Constants.EXIT_CODE_SUCCESS;
import static com.aws.greengrass.android.component.utils.Constants.EXIT_CODE_TERMINATED;

/**
 * Run Android virtual command in thread. Provide Process interface.
 */
public class AndroidVirtualCmdThread extends Process {
    private static String COMMAND = "command";

    private final AtomicInteger exitCode = new AtomicInteger(EXIT_CODE_SUCCESS);
    private final Thread thread;

    /**
     * Creates AndroidVirtualCmdThread instance.
     *
     * @param processControl object to run in separate thread
     * @param logger component's logger
     * @param command command to use for logging
     * @param onThreadDone will run when thread is gone
     *
     * @throws IOException on errors
     * @throws InterruptedException when thread has been interrupted
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    AndroidVirtualCmdThread(AndroidVirtualCmdExecution processControl, Logger logger, String[] command,
                            Runnable onThreadDone) throws IOException, InterruptedException {
        super();

        // we need to wait until command is started
        CountDownLatch startupLatch = new CountDownLatch(1);

        thread = new Thread(() -> {
            boolean interrupted = false;
            try {
                logger.atDebug().kv(COMMAND, command).log("startup-virtual-command");
                processControl.startup();
                startupLatch.countDown();

                logger.atDebug().kv(COMMAND, command).log("run-virtual-command");
                int exitValue = processControl.run();

                exitCode.set(exitValue);
                logger.atDebug().kv(COMMAND, command).kv("exitValue", exitValue)
                        .log("virtual-command-done");
            } catch (InterruptedException e) {
                exitCode.set(EXIT_CODE_TERMINATED);
                // logger.atDebug().kv(COMMAND, command).setCause(e).log("interrupted-virtual-command");
                interrupted = true;
                if (startupLatch.getCount() > 0) {
                    startupLatch.countDown();
                }
            } catch (Exception e) {
                logger.atError().kv(COMMAND, command).setCause(e)
                        .log("failed-virtual-command");
                exitCode.set(EXIT_CODE_FAILED);
                if (startupLatch.getCount() > 0) {
                    startupLatch.countDown();
                }
            } finally {
                try {
                    logger.atDebug().kv(COMMAND, command).log("shutdown-virtual-command");
                    processControl.shutdown();
                } catch (Exception e) {
                    exitCode.set(EXIT_CODE_FAILED);
                    logger.atError().kv(COMMAND, command).setCause(e)
                            .log("failed-virtual-command-during-shutdown");
                }
                logger.atDebug().kv(COMMAND, command).log("on-exit-virtual-command");
                onThreadDone.run();
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        // thread.setDaemon(true);
        thread.start();
        startupLatch.await();
    }

    @Override
    public OutputStream getOutputStream() {
        return null;
    }

    @Override
    public InputStream getInputStream() {
        return null;
    }

    @Override
    public InputStream getErrorStream() {
        return null;
    }

    @Override
    public int waitFor() throws InterruptedException {
        if (isAlive()) {
            thread.join();
        }
        return exitCode.get();
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        if (isAlive()) {
            unit.timedJoin(thread, timeout);
            return !isAlive();
        } else {
            return true;
        }
    }

    @Override
    public int exitValue() {
        return exitCode.get();
    }

    @Override
    public void destroy() {
        if (isAlive()) {
            thread.interrupt();
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public Process destroyForcibly() {
        destroy();
        return this;
    }

    @Override
    public boolean isAlive() {
        return thread != null && thread.isAlive();
    }

    /**
     * Get thread of internal thread.
     *
     * @return thread id
     */
    public long getTid() {
        long tid = -1;

        if (thread != null) {
            tid = thread.getId();
        }
        return tid;
    }
}
