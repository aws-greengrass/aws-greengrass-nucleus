/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms;

import com.aws.greengrass.util.Pair;
import com.aws.greengrass.util.Utils;
import lombok.NoArgsConstructor;
import org.zeroturnaround.process.PidProcess;
import org.zeroturnaround.process.Processes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.aws.greengrass.util.Utils.inputStreamToString;

public class UnixPlatform extends Platform {
    protected static final int SIGINT = 2;
    protected static final int SIGKILL = 9;
    public static final Pattern PS_PID_PATTERN = Pattern.compile("(\\d+)\\s+(\\d+)");

    @Override
    public void killProcessAndChildren(Process process, boolean force) throws IOException, InterruptedException {
        PidProcess pp = Processes.newPidProcess(process);

        logger.atDebug().log("Running pkill to kill child processes of pid {}", pp.getPid());
        // Use pkill to kill all subprocesses under the main shell
        String[] cmd = {"pkill", "-" + (force ? SIGKILL : SIGINT), "-P", Integer.toString(pp.getPid())};
        Process proc = Runtime.getRuntime().exec(cmd);
        proc.waitFor();
        if (proc.exitValue() != 0) {
            logger.atWarn()
                    .kv("pid", pp.getPid())
                    .kv("exit-code", proc.exitValue())
                    .kv("stdout", inputStreamToString(proc.getInputStream()))
                    .kv("stderr", inputStreamToString(proc.getErrorStream()))
                    .log("pkill exited non-zero (process not found or other error)");
        }

        // If forcible, then also kill the parent (the shell)
        if (force) {
            process.destroy();
            process.waitFor(2, TimeUnit.SECONDS);
            process.destroyForcibly();
        }
    }

    @Override
    public CommandDecorator getShellDecorator() {
        return new ShellDecorator();
    }

    @Override
    public int exitCodeWhenCommandDoesNotExist() {
        return 127;
    }

    @Override
    public UserDecorator getUserDecorator() {
        return new SudoDecorator();
    }

    /**
     * Decorate a command to run in a shell.
     */
    public static class ShellDecorator implements CommandDecorator {

        private static final String DEFAULT_SHELL = "sh";
        private static final String DEFAULT_ARG = "-c";
        private String shell;
        private String arg;

        /**
         * Create a new instance using the default shell (sh).
         */
        public ShellDecorator() {
            this(DEFAULT_SHELL, DEFAULT_ARG);
        }

        /**
         * Create a new instance for a given shell command and shell argument for taking in string input.
         * @param shell the shell.
         * @param arg optional argument for passing string data into the shell.
         */
        public ShellDecorator(String shell, String arg) {
            this.shell = shell;
            this.arg = arg;
        }

        @Override
        public String[] decorate(String... command) {
            boolean hasArg = !Utils.isEmpty(arg);
            int size = hasArg ? 3 : 2;
            String[] ret = new String[size];
            ret[0] = shell;
            if (hasArg) {
                ret[1] = arg;
            }
            ret[size - 1] = String.join(" ", command);
            return ret;
        }
    }

    /**
     * Decorator for running a command as a different user/group with `sudo`.
     */
    @NoArgsConstructor
    public static class SudoDecorator implements UserDecorator {
        private String user;
        private String group;

        @Override
        public String[] decorate(String... command) {
            // do nothing if no user set
            if (user == null) {
                return command;
            }
            int size = (group == null) ? 5 : 7;
            String[] ret = new String[command.length + size];
            ret[0] = "sudo";
            ret[1] = "-E";  // pass env vars through
            ret[2] = "-u";
            if (user.chars().allMatch(Character::isDigit)) {
                user = "\\#" + user;
            }
            ret[3] = user;
            if (group != null) {
                ret[4] = "-g";
                if (group.chars().allMatch(Character::isDigit)) {
                    group = "\\#" + group;
                }
                ret[5] = group;
            }
            ret[size - 1] = "--";
            System.arraycopy(command, 0, ret, size, command.length);
            return ret;
        }

        @Override
        public UserDecorator withUser(String user) {
            this.user = user;
            return this;
        }

        @Override
        public UserDecorator withGroup(String group) {
            this.group = group;
            return this;
        }
    }

    List<Integer> getChildPids(Process process) throws IOException, InterruptedException {
        PidProcess pp = Processes.newPidProcess(process);

        // Use PS to list process PID and parent PID so that we can identify the process tree
        logger.atDebug().log("Running ps to identify child processes of pid {}", pp.getPid());
        Process proc = Runtime.getRuntime().exec(new String[]{"ps", "-ax", "-o", "pid,ppid"});
        proc.waitFor();
        if (proc.exitValue() != 0) {
            logger.atWarn()
                    .kv("pid", pp.getPid())
                    .kv("exit-code", proc.exitValue())
                    .kv("stdout", inputStreamToString(proc.getInputStream()))
                    .kv("stderr", inputStreamToString(proc.getErrorStream()))
                    .log("ps exited non-zero");
            throw new IOException("ps exited with " + proc.exitValue());
        }

        try (InputStreamReader reader = new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8);
             BufferedReader br = new BufferedReader(reader)) {
            Stream<String> lines = br.lines();
            Map<String, String> pidToParent = lines.map(s -> {
                Matcher matches = PS_PID_PATTERN.matcher(s);
                if (matches.matches()) {
                    return new Pair<>(matches.group(1), matches.group(2));
                }
                return null;
            }).filter(Objects::nonNull).collect(Collectors.toMap(Pair::getLeft, Pair::getRight));

            Map<String, List<String>> parentToChildren = Utils.inverseMap(pidToParent);
            List<String> childProcesses = children(Integer.toString(pp.getPid()), parentToChildren);

            return childProcesses.stream().map(Integer::parseInt).collect(Collectors.toList());
        }
    }

    private List<String> children(String parent, Map<String, List<String>> procMap) {
        ArrayList<String> ret = new ArrayList<>();
        if (procMap.containsKey(parent)) {
            ret.addAll(procMap.get(parent));
            procMap.get(parent).forEach(p -> ret.addAll(children(p, procMap)));
        }
        return ret;
    }
}
