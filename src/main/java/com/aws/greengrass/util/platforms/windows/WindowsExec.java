/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.windows;

import com.aws.greengrass.jna.Kernel32Ex;
import com.aws.greengrass.lifecyclemanager.KernelAlternatives;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.UserPlatform;
import com.sun.jna.platform.win32.Kernel32;
import org.zeroturnaround.process.Processes;
import vendored.com.microsoft.alm.storage.windows.internal.WindowsCredUtils;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.inject.Inject;

import static com.sun.jna.platform.win32.WinError.ERROR_ACCESS_DENIED;

@SuppressWarnings("PMD.AvoidCatchingThrowable")
public class WindowsExec extends Exec {
    private final List<String> pathext;  // ordered file extensions to try, when no extension is provided
    private final String runasExePath;

    @Inject
    WindowsExec(KernelAlternatives kernelAlts) {
        super();
        String pathExt = System.getenv("PATHEXT");
        pathext = Arrays.asList(pathExt.split(File.pathSeparator));
        runasExePath = kernelAlts.getBinDir().resolve("runas.exe").toAbsolutePath().toString();
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
        if (needToSwitchUser()) {
            return createRunasProcess(commands);
        } else {
            ProcessBuilder pb = new ProcessBuilder();
            pb.environment().putAll(environment);
            return pb.directory(dir).command(commands).start();
        }
    }

    private Process createRunasProcess(String... commands) throws IOException {
        String username = userDecorator.getUser();

        byte[] credBlob = WindowsCredUtils.read(username);
        ByteBuffer bb = ByteBuffer.wrap(credBlob);
        CharBuffer cb = StandardCharsets.UTF_8.decode(bb);

        // Prepend runas arguments to use it for running commands as different user
        List<String> args = new ArrayList<>();
        args.add(runasExePath);
        args.add("-u:" + username);  // runas username
        args.add("-p:" + cb);  // plain text password. TODO revisit this because it exposes plaintext password
        args.add("-l:off");  // disable logging
        args.add("-b:0"); // set exit code base number to 0
        args.add("-w:" + dir); // set workdir explicitly
        // runas is un-escaping customer-provided quotes and escape characters, so we need to escape them
        // first so they unwrap correctly.
        List<String> cmd = Arrays.stream(commands).map((s) ->
                s.replace("\\\"", "\\\\\"").replace("\"", "\\\""))
                .collect(Collectors.toList());
        args.addAll(cmd);

        Arrays.fill(cb.array(), (char) 0);  // zero-out temporary buffers
        Arrays.fill(bb.array(), (byte) 0);

        ProcessBuilder pb = new ProcessBuilder();
        pb.environment().putAll(environment);
        Process p = pb.directory(dir).command(args).start();
        args.clear();  // best effort to clear password presence
        return p;
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

    private void stopGracefully() {
        int pid = Processes.newPidProcess(process).getPid();
        // Global lock since we're messing with a shared resource (the console)
        boolean sentConsoleCtrlEvent = false;
        synchronized (Kernel32Ex.INSTANCE) {
            Kernel32 k32 = Kernel32.INSTANCE;
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
                if (k32.GenerateConsoleCtrlEvent(0, 0)) {
                    sentConsoleCtrlEvent = true;
                } else {
                    logger.error("GenerateConsoleCtrlEvent error {}", k32.GetLastError());
                }
            } finally {
                if (!k32.FreeConsole()) {
                    logger.error("FreeConsole error {}", k32.GetLastError());
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
        } catch (InterruptedException ignore) { }

    }

    private void stopForcefully() throws IOException {
        // Invoke taskkill to terminate the entire process tree forcefully
        String[] taskkillCmds =
                {"taskkill", "/f", "/t", "/pid", Integer.toString(Processes.newPidProcess(process).getPid())};
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
        return !(currUser.getPrincipalName().equals(userDecorator.getUser()) || currUser.getPrincipalIdentifier()
                .equals(userDecorator.getUser()));
    }

    private static boolean isAbsolutePath(String p) {
        return new File(p).isAbsolute();
    }
}
