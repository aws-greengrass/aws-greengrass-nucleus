/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Exec;

import java.io.IOException;
import java.lang.Process;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import javax.annotation.Nullable;

import static com.aws.greengrass.android.component.utils.Constants.EXIT_CODE_FAILED;
import static com.aws.greengrass.android.component.utils.Constants.EXIT_CODE_TERMINATED;

public class AndroidCallableExec extends AndroidGenericExec {
    private static final Logger staticLogger = LogManager.getLogger(AndroidCallableExec.class);

    Thread thread;
    CountDownLatch timeoutLatch = new CountDownLatch(1);
    Callable<Integer> callable;
    Integer exitValue = -1;

    /**
     * Set the command to execute.
     * @param c a command.
     * @return this.
     */
    public Exec withExec(String... c) {
        logger.atError().log("withExec method is not applicable to commands to run in thread");
        return null;
    }

    @Override
    public Exec withShell(String... command) {
        logger.atError().log("whichShell method is not applicable to commands to run in thread");
        return null;
    }

    @Override
    public Exec withShell() {
        logger.atError().log("whichShell method is not applicable to commands to run in thread");
        return null;
    }

    /**
     * Set callable to execute and fakeCommand.
     *
     * @param callable callable to run in thread
     * @param fakeCommand a fakeCommand
     * @return this
     */
    public Exec withCallable(Callable<Integer> callable, String... fakeCommand) {
        cmds = fakeCommand;
        this.callable = callable;
        return this;
    }

    @Override
    public Exec usingShell(String shell) {
        // silently ignored
        return this;
    }

    @Override
    public boolean successful(boolean ignoreStderr) throws InterruptedException, IOException {
        exec();
        // TODO: use ignoreStderr when implement log forwarding
        return exitValue != null && exitValue == 0;
    }

    @Override
    public String[] getCommand() {
        return cmds;
    }

    /**
     * Find the path of a given command.
     *
     * @param fn command to lookup.
     * @return the Path of the command, or null if not found.
     */
    @Nullable
    public Path which(String fn) {
        logger.atError().log("which not applicable to thread");
        return null;
    }

    /**
     * Execute a command.
     *
     * @return the process exit code if it is not a background process.
     * @throws InterruptedException if the command is interrupted while running.
     * @throws IOException if an error occurs while executing.
     */
    @Override
    public Optional<Integer> exec() throws InterruptedException, IOException {
        // Don't run anything if the current thread is currently interrupted
        if (Thread.currentThread().isInterrupted()) {
            logger.atWarn().kv("command", this).log("Refusing to execute because the active thread is interrupted");
            throw new InterruptedException();
        }
        thread = new Thread( () -> {
                try {
                    exitValue = callable.call();
                    logger.atDebug().kv("command", this).kv("exitValue", exitValue)
                            .log("Finished");
                } catch(InterruptedException e) {
                    exitValue = EXIT_CODE_TERMINATED;
                    logger.atError().kv("command", this).setCause(e)
                            .log("Interrupted");
                } catch(Throwable e) {
                    exitValue = EXIT_CODE_FAILED;
                    logger.atError().kv("command", this).setCause(e)
                            .log("Terminated by exception");
                } finally {
                    triggerClosed();
                    timeoutLatch.countDown();
                }
            });
        logger.atDebug().log("Created thread with tid {}", thread.getId());
        thread.setDaemon(true);
        thread.start();

        // stderrc = new Copier(process.getErrorStream(), stderr);
        // stdoutc = new Copier(process.getInputStream(), stdout);
        // stderrc.start();
        // stdoutc.start();
        if (whenDone == null) {
            // waiting for the result
            try {
                if (timeout < 0) {
                    timeoutLatch.await();
                } else {
                    if (!timeoutLatch.await(timeout, timeunit)) {
                        (stderr == null ? stdout : stderr).accept("\n[TIMEOUT]\n");
                        thread.interrupt();
                    }
                }
            } catch (InterruptedException ie) {
                // We just got interrupted by something like the cancel(true) in setBackingTask
                // Give the process a touch more time to exit cleanly
                if (!timeoutLatch.await(5, TimeUnit.SECONDS)) {
                    (stderr == null ? stdout : stderr).accept("\n[TIMEOUT after InterruptedException]\n");
                    thread.interrupt();
                }
                throw ie;
            }
            // stderrc.join(5000);
            // stdoutc.join(5000);
            Integer returnValue = exitValue;
            if (returnValue != null) {
                return Optional.of(returnValue);
            }
        }

        return Optional.empty();
    }

    /**
     * Create and start the child process in platform-specific ways.
     *
     * @return child process
     * @throws IOException if IO error occurs
     */
    @Override
    protected Process createProcess() throws IOException {
        throw new IOException("Process is not applicable to run specific command");
    }

    //@SuppressWarnings("PMD.NullAssignment")
    private void triggerClosed() {
        if (!isClosed.get()) {
            isClosed.set(true);
            final IntConsumer wd = whenDone;
            final int exit = exitValue == null ? -1 : exitValue ;
            if (wd != null) {
                wd.accept(exit);
            }
        }
    }

    @Override
    public boolean isRunning() {
        return thread == null ? !isClosed.get() : thread.isAlive();
    }

    /**
     * Get associated process instance representing underlying OS process.
     *
     * @return process object.
     */
    @Override
    public Process getProcess() {
        logger.atWarn().log("Process is not applicable to run specific command");
        return null;
    }

    /**
     * Get the process ID of the underlying process.
     *
     * @return the process PID.
     */
    @Override
    public int getPid() {
        logger.atWarn().log("Pid not applicable to run thread");
        // TODO: use TID ?
        return -1;
    }

    @Override
    public void close() throws IOException {
        Thread t = thread;
        if (t == null) {
            return;
        }

        t.interrupt();
    }
}
