/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.windows;

import com.aws.greengrass.jna.Kernel32Ex;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.UserPlatform;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Wincon;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.zeroturnaround.process.Processes;
import vendored.com.microsoft.alm.storage.windows.internal.WindowsCredUtils;
import vendored.org.apache.dolphinscheduler.common.utils.process.ProcessBuilderForWin32;
import vendored.org.apache.dolphinscheduler.common.utils.process.ProcessImplForWin32;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

@SuppressWarnings("PMD.AvoidCatchingThrowable")
public class WindowsExec extends Exec {
    private static final char NULL_CHAR = '\0';
    private static final String STOP_GRACEFULLY_EVENT = "stopGracefully";
    private final List<String> pathext;  // ordered file extensions to try, when no extension is provided
    private int pid;

    WindowsExec() {
        super();
        // Windows env var keys are case-insensitive. Use case-insensitive map to avoid collision
        environment = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        environment.putAll(defaultEnvironment);
        String pathExt = System.getenv("PATHEXT");
        pathext = Arrays.asList(pathExt.split(File.pathSeparator));
    }

    @Nullable
    @Override
    public Path which(String fn) {
        String ext = Utils.extension(fn);
        if (isAbsolutePath(fn)) {
            if (!ext.isEmpty()) {
                Path f = Paths.get(fn);
                return Files.isExecutable(f) ? f : null;
            }
            // No extension provided. Try PATHEXT in order
            for (String extCandidate : pathext) {
                Path f = Paths.get(fn + extCandidate);
                if (Files.isExecutable(f)) {
                    return f;
                }
            }
            return null;
        }
        // Search in paths
        for (Path d : paths) {
            Path f = d.resolve(fn);
            if (!ext.isEmpty() && Files.isExecutable(f)) {
                return f;
            }
            // No extension provided. Try PATHEXT in order
            for (String extCandidate : pathext) {
                f = d.resolve(fn + extCandidate);
                if (Files.isExecutable(f)) {
                    return f;
                }
            }
        }
        return null;
    }

    @Override
    public String[] getCommand() {
        String[] decorated = Arrays.copyOf(cmds, cmds.length);
        if (shellDecorator != null) {
            decorated = shellDecorator.decorate(decorated);
        }
        return decorated;
    }

    @Override
    protected Process createProcess() throws IOException {
        String[] commands = getCommand();
        logger.atTrace().kv("decorated command", String.join(" ", commands)).log();
        ProcessBuilderForWin32 winPb = new ProcessBuilderForWin32();
        char[] password = null;
        if (needToSwitchUser()) {
            String username = userDecorator.getUser();
            password = getPassword(username);
            winPb.user(username, password);
            // When environment is constructed it inherits current process env
            // Clear the env in this case because later we'll load the given user's env instead
            winPb.environment().clear();
        }
        winPb.environment().putAll(environment);
        winPb.directory(dir).command(commands);
        synchronized (Kernel32.INSTANCE) {
            process = winPb.start();
        }
        pid = ((ProcessImplForWin32) process).getPid();
        // zero-out password buffer after use
        if (password != null) {
            Arrays.fill(password, (char) 0);
        }
        // calling attachConsole right after a process is launched will fail with invalid handle error
        // waiting a bit ensures that we get the process handle from the pid
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return process;
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
        if (process == null || !process.isAlive()) {
            return;
        }

        try {
            stopGracefully();
        } finally {
            stopForcefully();
        }
    }

    @SuppressFBWarnings("SWL_SLEEP_WITH_LOCK_HELD")
    private void stopGracefully() {
        if (!process.isAlive()) {
            return;
        }
        // First, start a separate process that holds the console alive
        // so that later gg can re-attach to this same console
        Process holderProc;
        try {
            // Waits indefinitely for a keystroke
            holderProc = new ProcessBuilder().command("cmd", "/C", "pause").start();
        } catch (IOException e) {
            logger.atError(STOP_GRACEFULLY_EVENT).cause(e)
                    .log("Failed to start holder process. Cannot stop gracefully");
            return;
        }

        boolean sentConsoleCtrlEvent = false;
        synchronized (Kernel32.INSTANCE) {

            Kernel32 k32 = Kernel32.INSTANCE;
            try {
                // Must detach from current console before attaching to another
                if (!k32.FreeConsole()) {
                    logger.atError(STOP_GRACEFULLY_EVENT).log("FreeConsole error {}", k32.GetLastError());
                }
                // Attach to the console that's running the target process
                if (!k32.AttachConsole(pid)) {
                    logger.atError(STOP_GRACEFULLY_EVENT).log("AttachConsole error {}", k32.GetLastError());
                    return;
                }
                // Make gg ignore Ctrl-C temporarily so that we don't terminate ourselves as well
                if (!Kernel32Ex.INSTANCE.SetConsoleCtrlHandler(null, true)) {
                    logger.atError(STOP_GRACEFULLY_EVENT)
                            .log("SetConsoleCtrlHandler add error {}", k32.GetLastError());
                    return;
                }
                // Send Ctrl-C to all processes in the console
                if (k32.GenerateConsoleCtrlEvent(Wincon.CTRL_C_EVENT, 0)) {
                    sentConsoleCtrlEvent = true;
                } else {
                    logger.atError(STOP_GRACEFULLY_EVENT)
                            .log("GenerateConsoleCtrlEvent error {}", k32.GetLastError());
                }
            } finally {
                // Re-attach gg to original console and re-enable Ctrl-C
                if (!k32.FreeConsole()) {
                    logger.atError(STOP_GRACEFULLY_EVENT).log("FreeConsole error {}", k32.GetLastError());
                }
                // waiting here serves 2 purposes
                // 1. ensure CtrlHandler is not enabled before the calling process receives the ctrl-c signal
                // 2. holderProc just got launched, wait is required before AttachConsole can be called on holderProc
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignore) {
                }
                int holderPid = Processes.newPidProcess(holderProc).getPid();
                if (!k32.AttachConsole(holderPid)) {
                    logger.atError(STOP_GRACEFULLY_EVENT).log("Re-AttachConsole error {}", k32.GetLastError());
                }
                if (!Kernel32Ex.INSTANCE.SetConsoleCtrlHandler(null, false)) {
                    logger.atError(STOP_GRACEFULLY_EVENT)
                            .log("Re-SetConsoleCtrlHandler error {}", k32.GetLastError());
                }
                holderProc.destroyForcibly();
            }
        }

        if (sentConsoleCtrlEvent) {
            try {
                process.waitFor(gracefulShutdownTimeout.getSeconds(), TimeUnit.SECONDS);
                logger.debug("Process stopped gracefully: {}", pid);
            } catch (InterruptedException ignored) { }
        }
    }

    private void stopForcefully() throws IOException {
        // Invoke taskkill to terminate the entire process tree forcefully
        String[] taskkillCmds = {"taskkill", "/f", "/t", "/pid", Integer.toString(pid)};
        logger.atTrace().kv("executing command", String.join(" ", taskkillCmds)).log("Closing Exec");
        Process killerProcess = new ProcessBuilder().command(taskkillCmds).start();

        try {
            killerProcess.waitFor();
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            process.destroyForcibly();
        }
    }

    /**
     * Returns true if we need to create process as another user. Otherwise, just use ProcessBuilder.
     */
    private boolean needToSwitchUser() throws IOException {
        if (userDecorator == null) {
            return false;
        }
        // check if same as current user
        UserPlatform.UserAttributes currUser = Platform.getInstance().lookupCurrentUser();
        boolean isCurrentUser = currUser.getPrincipalName().equals(userDecorator.getUser())
                || currUser.getPrincipalIdentifier().equals(userDecorator.getUser());
        boolean wantsPrivileges = Objects.equals(userDecorator.getGroup(),
                WindowsPlatform.getInstance().getPrivilegedGroup());

        // If the command is not requesting privileges and is not requesting some other user,
        // then we need to switch users
        return !wantsPrivileges && !isCurrentUser;
    }

    private static boolean isAbsolutePath(String p) {
        return new File(p).isAbsolute();
    }

    private static char[] getPassword(String key) throws IOException {
        byte[] credBlob = WindowsCredUtils.read(key);
        ByteBuffer bb = ByteBuffer.wrap(credBlob);
        CharBuffer cb = WindowsCredUtils.getCharsetForSystem().decode(bb);
        char[] password = new char[cb.length() + 1];
        cb.get(password, 0, cb.length());
        // char[] needs to be null terminated for windows
        password[password.length - 1] = NULL_CHAR;
        // zero-out temporary buffers
        Arrays.fill(cb.array(), (char) 0);
        Arrays.fill(bb.array(), (byte) 0);
        return password;
    }
}
