/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import androidx.annotation.Nullable;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Exec;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public class AndroidComponentExec extends AndroidShellExec {

    private static final Logger staticLogger = LogManager.getLogger(AndroidComponentExec.class);
    private int pid = -1; //FIXME: maybe it's worth to use some kind of identifier (e.g. Java package name)

    @Nullable
    @Override
    public Path which(String fn) {
        return null;
    }

    @Override
    public String[] getCommand() {
        return new String[0];
    }

    @Override
    protected Process createProcess() throws IOException {
        return null;
    }

    @Override
    public int getPid() {
        return pid;
    }

    @Override
    public void close() throws IOException {

    }

    @Override
    public String cmd(String... command) throws InterruptedException, IOException {
//        return withExec(command).execAndGetStringOutput();
        throw new IOException("cmd method is not supported on Android");
    }

    @Override
    public String sh(String command) throws InterruptedException, IOException {
//        return sh((File) null, command);
        throw new IOException("sh method is not supported on Android");
    }

    @Override
    public String sh(File dir, String command) throws InterruptedException, IOException {
//        return cd(dir).withShell(command).execAndGetStringOutput();
        throw new IOException("sh method is not supported on Android");
    }

    @Override
    public String sh(Path dir, String command) throws InterruptedException, IOException {
//        return sh(dir.toFile(), command);
        throw new IOException("sh method is not supported on Android");
    }

    @Override
    public boolean successful(boolean ignoreStderr, String command) throws InterruptedException, IOException {
//        return withShell(command).successful(ignoreStderr);
        throw new IOException("shell execution is not supported on Android");
    }

    @Override
    public boolean successful(boolean ignoreStderr) throws InterruptedException, IOException {
        //TODO: rework for Android
        exec();
//        return (ignoreStderr || stderrc.getNlines() == 0) && process.exitValue() == 0;
        return true;
    }

    @Override
    public Exec cd(File f) {
        staticLogger.atWarn("Setting of working directory is not possible on Android. Skipped");
        return this;
    }

    @Override
    public File cwd() {
        staticLogger.atWarn("Attempt to determine component's working directory - not relevant for Android");
        return null;
    }

    @Override
    public Exec withShell(String... command) {
        staticLogger.atDebug("Component Exec instance converted to Shell Exec");
        return (new AndroidShellExec(this)).withShell(command);
    }

    @Override
    public Exec withShell() {
        staticLogger.atDebug("Component Exec instance converted to Shell Exec");
        return (new AndroidShellExec(this)).withShell();
    }

    @Override
    public Exec usingShell(String shell) {
        staticLogger.atDebug("Component Exec instance converted to Shell Exec");
        return (new AndroidShellExec(this)).usingShell(shell);
    }

    @Override
    public Exec withUser(String user) {
        staticLogger.atWarn("Execution with different user is not supported on Android");
        return this;
    }

    @Override
    public Exec withGroup(String group) {
        staticLogger.atWarn("Execution with specified group is not supported on Android");
        return this;
    }

    @Override
    public Optional<Integer> exec() throws InterruptedException, IOException {
        // Don't run anything if the current thread is currently interrupted
        if (Thread.currentThread().isInterrupted()) {
            logger.atWarn().kv("command", this).log("Refusing to execute because the active thread is interrupted");
            throw new InterruptedException();
        }
        //FIXME: start component here
//        process = createProcess();
//        logger.debug("Created process with pid {}", getPid());


        //FIXME: implement a mechanism for a component to provide feedback to Nucleus
//        stderrc = new Copier(process.getErrorStream(), stderr);
//        stdoutc = new Copier(process.getInputStream(), stdout);
//        stderrc.start();
//        stdoutc.start();
//        if (whenDone == null) {
//            try {
//                if (timeout < 0) {
//                    process.waitFor();
//                } else {
//                    if (!process.waitFor(timeout, timeunit)) {
//                        (stderr == null ? stdout : stderr).accept("\n[TIMEOUT]\n");
//                        process.destroy();
//                    }
//                }
//            } catch (InterruptedException ie) {
//                // We just got interrupted by something like the cancel(true) in setBackingTask
//                // Give the process a touch more time to exit cleanly
//                if (!process.waitFor(5, TimeUnit.SECONDS)) {
//                    (stderr == null ? stdout : stderr).accept("\n[TIMEOUT after InterruptedException]\n");
//                    process.destroyForcibly();
//                }
//                throw ie;
//            }
//            stderrc.join(5000);
//            stdoutc.join(5000);
//            return Optional.of(process.exitValue());
//        }
        return Optional.empty();
    }
}
