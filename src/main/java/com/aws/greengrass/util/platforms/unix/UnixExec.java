/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix;

import com.aws.greengrass.util.platforms.Exec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.annotation.Nullable;

@SuppressWarnings("PMD.AvoidCatchingThrowable")
public class UnixExec extends Exec {
    static {
        try {
            // This bit is gross: under some circumstances (like IDEs launched from the
            // macos Dock) the PATH environment variable doesn't match the path one expects
            // after the .profile script is executed.  Fire up a login shell, then grab it's
            // path variable, but without using Exec shorthands to avoid initialization
            // order paradoxes.
            Process hack = new ProcessBuilder("sh", "-c", "echo 'echo $PATH' | grep -E ':[^ ]'").start();
            StringBuilder path = new StringBuilder();
            Thread bg = new Thread(() -> {
                try (InputStream in = hack.getInputStream()) {
                    for (int c = in.read(); c >= 0; c = in.read()) {
                        path.append((char) c);
                    }
                } catch (Throwable ignore) {
                }
            });
            bg.start();
            bg.join(2000);
            addPathEntries(path.toString().trim());
            // Ensure some level of sanity
            ensurePresent("/bin", "/usr/bin", "/sbin", "/usr/sbin");
        } catch (Throwable ex) {
            staticLogger.atError().log("Error while initializing PATH", ex);
        }
        computePathString();
    }

    private static void ensurePresent(String... fns) {
        for (String fn : fns) {
            Path ulb = Paths.get(fn);
            if (Files.isDirectory(ulb) && !paths.contains(ulb)) {
                paths.add(ulb);
            }
        }
    }

    private static void computePathString() {
        StringBuilder sb = new StringBuilder();
        paths.forEach(p -> {
            if (sb.length() > 5) {
                sb.append(PATH_SEP);
            }
            sb.append(p.toString());
        });
        setDefaultEnv(PATH, sb.toString());
    }

    @Nullable
    @Override
    public Path which(String fn) {
        fn = deTilde(fn);
        if (fn.startsWith("/")) {
            Path f = Paths.get(fn);
            return Files.isExecutable(f) ? f : null;
        }
        for (Path d : paths) {
            Path f = d.resolve(fn);
            if (Files.isExecutable(f)) {
                return f;
            }
        }
        return null;
    }

    @Override
    protected Process createProcess(String[] command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder();
        pb.environment().putAll(environment);
        process = pb.directory(dir).command(command).start();
        return process;
    }
}
