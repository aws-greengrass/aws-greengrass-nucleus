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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import javax.annotation.Nullable;

public class AndroidVirtualCmdExec extends AndroidGenericExec {

    private AndroidVirtualCmdExecution virtualCmd = null;
    private final AtomicLong stderrNumLines = new AtomicLong(0);
    private long tid = -1;

    /** Proxy of stderr consumer for count error lines. */
    private final Consumer<CharSequence> stderrProxy = new Consumer<CharSequence>() {
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
     * @param virtualCmd virtual command execution
     * @param command a fake shell command for logging
     * @return this
     */
    public Exec withVirtualCmd(AndroidVirtualCmdExecution virtualCmd, String... command) {
        this.virtualCmd = virtualCmd;
        cmds = command;
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
     * @throws InterruptedException when thread has been interrupted
     */
    @Override
    protected Process createProcess() throws IOException, InterruptedException {
        // finish callable instance configuration
        virtualCmd.withOut(stdout);
        virtualCmd.withErr(stderrProxy);
        virtualCmd.withEnv(environment);

        // create new running "Process" which actually run a thread
        AndroidVirtualCmdThread thread = new AndroidVirtualCmdThread(virtualCmd, logger, cmds, () -> {
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
        logger.atWarn().log("Pid not applicable to threads");
        // FIXME: get pid from external component process or use tid?
        // AndroidVirtualCmdThread thread = (AndroidVirtualCmdThread)process;
        return -1;
    }

    @Override
    public void close() throws IOException {
        if (!isClosed.getAndSet(true)) {
            Process p = process;
            if (p != null && p.isAlive()) {
                p.destroy();
            }
        }
    }
}
