/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.windows;

import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.UserPlatform;
import org.zeroturnaround.process.Processes;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

@SuppressWarnings("PMD.AvoidCatchingThrowable")
public class WindowsExec extends Exec {
    private final List<String> pathext;  // ordered file extensions to try, when no extension is provided

    WindowsExec() {
        super();
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
        if (needToSwitchUser()) {
            return createRunasProcess(commands);
        } else {
            ProcessBuilder pb = new ProcessBuilder();
            pb.environment().putAll(environment);
            return pb.directory(dir).command(commands).start();
        }
    }

    private Process createRunasProcess(String... commands) throws IOException {
        // Expect username in format: DOMAIN\UserName
        String[] usernameParts = userDecorator.getUser().split("\\\\");  // split by single backslash
        String domain = null;
        String username;
        if (usernameParts.length == 2) {
            domain = usernameParts[0];
            username = usernameParts[1];
        } else {
            username = usernameParts[0];
        }

        WindowsRunasProcess p = new WindowsRunasProcess(domain, username);
        p.setEnv(environment);
        p.setCurrentDirectory(dir.getAbsolutePath());
        p.start(commands);
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

        // Invoke taskkill to terminate the entire process tree forcefully
        int pidToKill = process instanceof WindowsRunasProcess
                ? ((WindowsRunasProcess) process).getPid() : Processes.newPidProcess(process).getPid();
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
}
