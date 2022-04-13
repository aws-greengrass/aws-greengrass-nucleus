/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import com.aws.greengrass.logging.api.Logger;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.aws.greengrass.android.component.utils.Constants.EXIT_CODE_FAILED;
import static com.aws.greengrass.android.component.utils.Constants.EXIT_CODE_SUCCESS;
import static com.aws.greengrass.android.component.utils.Constants.EXIT_CODE_TERMINATED;

/**
 * Run callable in thread. Provide Process interface.
 */
public class AndroidCallableThread extends Process {
    private static String COMMAND = "command";

    private final AtomicInteger exitCode = new AtomicInteger(EXIT_CODE_SUCCESS);
    private final Thread thread;

    /**
     * Creates AndroidCallableThread instance.
     *
     * @param callable callable to run
     * @param logger component's logger
     * @param command command to use for logging
     * @param onExit will run when thread is gone
     */
    AndroidCallableThread(Callable<Integer> callable, Logger logger, String[] command, Runnable onExit) {
        super();
        thread = new Thread(() -> {
            boolean interrupted = false;
            try {
                logger.atDebug().kv(COMMAND, command).log("AndroidCallableThread started");
                int exitValue = callable.call();
                exitCode.set(exitValue);
                logger.atDebug().kv(COMMAND, command).kv("exitValue", exitValue)
                        .log("AndroidCallableThread finished");
            } catch (InterruptedException e) {
                exitCode.set(EXIT_CODE_TERMINATED);
                logger.atDebug().kv(COMMAND, command).setCause(e)
                        .log("AndroidCallableThread interrupted");
                interrupted = true;
            } catch (Throwable e) {
                exitCode.set(EXIT_CODE_FAILED);
                logger.atError().kv(COMMAND, command).setCause(e)
                        .log("AndroidCallableThread failed with exception");
            } finally {
                onExit.run();
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        //thread.setDaemon(true);
        thread.start();
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
        return thread.isAlive();
    }

    public long getTid() {
        return thread.getId();
    }
}
