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

import static com.sun.jna.platform.win32.WinError.ERROR_ACCESS_DENIED;

@SuppressWarnings("PMD.AvoidCatchingThrowable")
public class WindowsExec extends Exec {
    private static final char NULL_CHAR = '\0';
    private final List<String> pathext;  // ordered file extensions to try, when no extension is provided

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
        if (needToSwitchUser()) {
            String username = userDecorator.getUser();
            winPb.user(username, new String(getPassword(username)));
            // When environment is constructed it inherits current process env
            // Clear the env in this case because later we'll load the given user's env instead
            winPb.environment().clear();
        }
        winPb.environment().putAll(environment);
        return winPb.directory(dir).command(commands).start();
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
    @SuppressWarnings("PMD.AvoidDeeplyNestedIfStmts")
    private void stopGracefully() {
        int pid = ((ProcessImplForWin32) process).getPid();
        boolean sentConsoleCtrlEvent = false;
        synchronized (Kernel32.INSTANCE) {
            Kernel32 k32 = Kernel32.INSTANCE;
            // if the console is already attached, a second attach call will fail.
            if (!k32.FreeConsole()) {
                logger.error("FreeConsole error {}", k32.GetLastError());
            }
            if (!k32.AttachConsole(pid)) {
                logger.error("AttachConsole error {}", k32.GetLastError());
                // Console already attached so we cannot signal it
                if (k32.GetLastError() == ERROR_ACCESS_DENIED) {
                    logger.info("AttachConsole failed for PID: {}, calling FreeConsole on it", pid);
                    // Oddly, calling FreeConsole seems to make subsequent calls work?
                    if (!k32.FreeConsole()) {
                        logger.error("FreeConsole error {}", k32.GetLastError());
                    }
                }
                return;
            }
            Kernel32Ex k32Ex = Kernel32Ex.INSTANCE;
            try {
                if (!k32Ex.SetConsoleCtrlHandler(null, true)) {
                    logger.error("SetConsoleCtrlHandler add error {}", k32.GetLastError());
                    return;
                }
                if (k32.GenerateConsoleCtrlEvent(Wincon.CTRL_C_EVENT, 0)) {
                    sentConsoleCtrlEvent = true;
                } else {
                    logger.error("GenerateConsoleCtrlEvent error {}", k32.GetLastError());
                }
            } finally {
                if (!k32.FreeConsole()) {
                    logger.error("FreeConsole error {}", k32.GetLastError());
                }
                // wait to ensure CtrlHandler is not enabled before the calling process gets the signal
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                }
                if (!k32Ex.SetConsoleCtrlHandler(null, false)) {
                    logger.error("SetConsoleCtrlHandler remove error {}", k32.GetLastError());
                }
            }
        }

        try {
            if (sentConsoleCtrlEvent) {
                process.waitFor(gracefulShutdownTimeout, TimeUnit.SECONDS);
                logger.info("Process stopped gracefully: {}", pid);
            }
        } catch (InterruptedException ignore) {
        }
    }

    private void stopForcefully() throws IOException {
        // Invoke taskkill to terminate the entire process tree forcefully
        int pidToKill = process instanceof ProcessImplForWin32
                ? ((ProcessImplForWin32) process).getPid() : Processes.newPidProcess(process).getPid();
        String[] taskkillCmds =
                {"taskkill", "/f", "/t", "/pid", Integer.toString(pidToKill)};
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
