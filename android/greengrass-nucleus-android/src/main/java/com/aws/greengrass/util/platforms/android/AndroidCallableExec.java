/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import com.aws.greengrass.util.Exec;

import java.io.IOException;
import java.lang.Process;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import javax.annotation.Nullable;

public class AndroidCallableExec extends AndroidGenericExec {

    private AndroidCallable callable = null;
    private final AtomicReference<AndroidCallable> onClose = new AtomicReference<>();
    private final AtomicLong stderrNumLines = new AtomicLong(0);
    private long tid = -1;

    /** Proxy of stderr consumer for count error lines. */
    private final Consumer<CharSequence> localStderr = new Consumer<CharSequence>() {
        @Override
        public void accept(CharSequence line) {
            stderrNumLines.incrementAndGet();
            stderr.accept(line);
        }
    };

    /**
     * Set the command to execute.
     *
     * @param command a commands.
     * @return this.
     */
    public Exec withExec(String... command) {
        logger.atDebug().log("withExec doesn't supported for AndroidCallableExec");
        throw new UnsupportedOperationException("withExec doesn't supported for AndroidCallableExec");
    }

    /**
     * Set the shell command to execute.
     *
     * @param command a command.
     * @return this.
     */
    @Override
    public Exec withShell(String... command) {
        logger.atDebug().log("withShell doesn't supported for AndroidCallableExec");
        throw new UnsupportedOperationException("withShell doesn't supported for AndroidCallableExec");
    }

    @Override
    public Exec withShell() {
        logger.atDebug().log("withShell doesn't supported for AndroidCallableExec");
        throw new UnsupportedOperationException("withShell doesn't supported for AndroidCallableExec");
    }

    /**
     * Set callable to execute and source command(s).
     *
     * @param callable callable to run in thread
     * @param command a fake shell command for logging
     * @return this
     */
    public Exec withCallable(AndroidCallable callable, String... command) {
        this.callable = callable;
        cmds = command;
        return this;
    }

    /**
     * Set callable will execute on close().
     *
     * @param onClose callable to run when close() is called
     * @return this
     */
    public Exec withClose(AndroidCallable onClose) {
        this.onClose.set(onClose);
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
        return (ignoreStderr || stderrNumLines.get() == 0) && process.exitValue() == 0;
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
        logger.atError().log("which is not applicable on Android");
        return null;
    }

    /**
     * Create and start the child process in platform-specific ways.
     *
     * @return child process
     * @throws IOException if IO error occurs
     */
    @Override
    protected Process createProcess() throws IOException {
        // finish callable instance configuration
        callable.withOut(stdout);
        callable.withErr(localStderr);
        callable.withEnv(environment);

        // create new running "Process" which actually run a thread
        AndroidCallableThread thread = new AndroidCallableThread(callable, logger, cmds, () -> {
            setClosed();
        });
        tid = thread.getTid();

        return thread;
    }

    /**
     * Execute a command.
     *
     * @return the process exit code if it is not a background process.
     * @throws InterruptedException if the command is interrupted while running.
     * @throws IOException if an error occurs while executing.
     */
    public Optional<Integer> exec() throws InterruptedException, IOException {
        // Don't run anything if the current thread is currently interrupted
        if (Thread.currentThread().isInterrupted()) {
            logger.atWarn().kv("command", cmds).log("Refusing to execute because the active thread is interrupted");
            throw new InterruptedException();
        }
        process = createProcess();
        logger.debug("Created thread with tid {}", tid);

        if (whenDone == null) {
            try {
                if (timeout < 0) {
                    process.waitFor();
                } else {
                    if (!process.waitFor(timeout, timeunit)) {
                        (stderr == null ? stdout : stderr).accept("\n[TIMEOUT]\n");
                        process.destroy();
                    }
                }
            } catch (InterruptedException ie) {
                // We just got interrupted by something like the cancel(true) in setBackingTask
                // Give the process a touch more time to exit cleanly
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    (stderr == null ? stdout : stderr).accept("\n[TIMEOUT after InterruptedException]\n");
                    process.destroyForcibly();
                }
                throw ie;
            }
            return Optional.of(process.exitValue());
        }
        return Optional.empty();
    }

    /**
     * Set closed flag and call whenDone methods.
     *  Called when thread is done all work in callable from that thread.
     */
    private void setClosed() {
        if (!isClosed.getAndSet(true)) {
            final IntConsumer wd = whenDone;
            final int exit = process == null ? -1 : process.exitValue();
            if (wd != null) {
                wd.accept(exit);
            }
        }
    }

    @Override
    public Process getProcess() {
        return process;
    }

    /**
     * Get the process ID of the underlying process.
     *
     * @return the process PID.
     */
    @Override
    public int getPid() {
        AndroidCallableThread thread = (AndroidCallableThread)process;
        logger.atWarn().log("Pid not applicable to threads");
        // FIXME: get pid from external component process or use tid?
        return -1;
    }

    @Override
    public boolean isRunning() {
        boolean isRunning = false;
        if (process == null) {
            isRunning = !isClosed.get();
        } else if (process.isAlive()) {
            isRunning = true;
        } else {
            // even when thread is finished we need to call onClose if any
            if (onClose.get() != null) {
                isRunning = true;
            }
        }
        return isRunning;
    }

    @Override
    public void close() throws IOException {
        AndroidCallable closeCallable = onClose.getAndSet(null);
        if (closeCallable != null) {
            try {
                closeCallable.call();
            } catch (Throwable e) {
                throw new IOException("Exception during close AndroidCallableExec", e);
            }
        }

        if (isClosed.getAndSet(true)) {
            return;
        }

        Process p = process;
        if (p == null || !p.isAlive()) {
            return;
        }
        p.destroy();
    }
}
