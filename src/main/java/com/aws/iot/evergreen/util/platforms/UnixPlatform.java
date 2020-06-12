/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.util.platforms;

import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Pair;
import com.aws.iot.evergreen.util.Utils;
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

import static com.aws.iot.evergreen.util.Utils.inputStreamToString;

public class UnixPlatform extends Platform {
    protected static final int SIGINT = 2;
    protected static final int SIGKILL = 9;
    public static final Pattern PS_PID_PATTERN = Pattern.compile("(\\d+)\\s+(\\d+)");
    protected static final Logger logger = LogManager.getLogger(UnixPlatform.class);

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
                    .log("pkill exited non-zero");
        }

        // If forcible, then also kill the parent (the shell)
        if (force) {
            process.destroy();
            process.waitFor(2, TimeUnit.SECONDS);
            process.destroyForcibly();
        }
    }

    @Override
    public String[] getShellForCommand(String command) {
        return new String[]{"sh", "-c", command};
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
            procMap.get(parent).forEach(p -> {
                ret.addAll(children(p, procMap));
            });
        }
        return ret;
    }

}
