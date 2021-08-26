/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.windows;

import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.lifecyclemanager.KernelAlternatives;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.UserPlatform;
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
import javax.annotation.Nullable;
import javax.inject.Inject;

import static com.aws.greengrass.config.PlatformResolver.ARCHITECTURE_KEY;
import static com.aws.greengrass.config.PlatformResolver.ARCH_X86;

@SuppressWarnings("PMD.AvoidCatchingThrowable")
public class WindowsExec extends Exec {
    private final List<String> pathext;  // ordered file extensions to try, when no extension is provided
    private final String runasExePath;

    @Inject
    WindowsExec(PlatformResolver platformResolver, KernelAlternatives kernelAlts) {
        super();
        String pathExt = System.getenv("PATHEXT");
        pathext = Arrays.asList(pathExt.split(File.pathSeparator));
        Path runasPath = ARCH_X86.equals(platformResolver.getCurrentPlatform().get(ARCHITECTURE_KEY))
                ? kernelAlts.getBinDir().resolve("runas_x86.exe") : kernelAlts.getBinDir().resolve("runas_x64.exe");
        runasExePath = runasPath.toAbsolutePath().toString();
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
        if (needToSwitchUser()) {
            return createRunasProcess();
        } else {
            ProcessBuilder pb = new ProcessBuilder();
            pb.environment().putAll(environment);
            return pb.directory(dir).command(getCommand()).start();
        }
    }

    private Process createRunasProcess() throws IOException {
        String username = userDecorator.getUser();

        byte[] credBlob = WindowsCredUtils.read(username);
        ByteBuffer bb = ByteBuffer.wrap(credBlob);
        CharBuffer cb = StandardCharsets.UTF_8.decode(bb);

        List<String> args = new ArrayList<>();
        args.add(runasExePath);
        args.add("-u:" + username);
        args.add("-p:" + cb);
        args.add("-l:off");  // disable logging
        args.addAll(Arrays.asList(getCommand()));

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
        Process killerProcess = new ProcessBuilder().command("taskkill", "/f", "/t", "/pid",
                Integer.toString(Processes.newPidProcess(process).getPid())).start();
        try {
            int taskkillExitCode = killerProcess.waitFor();
            if (taskkillExitCode != 0) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
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
