/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix;

import com.aws.greengrass.util.Pair;
import com.aws.greengrass.util.Utils;
import org.zeroturnaround.process.PidProcess;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.aws.greengrass.util.Utils.inputStreamToString;

public class DarwinPlatform extends UnixPlatform {
    private static final String PRIVILEGED_GROUP = "wheel";

    private static final String CREATE_USER_CMD_PREFIX = "sudo dscl . -create /Users/";
    private static final String AVAILABLE_UNIQUE_ID_CMD =
            "dscl . -list /Users UniqueID | awk '{print $2}' | sort -ug | tail -1";
    private static final String CREATE_GROUP_CMD_PREFIX = "sudo dscl . -create /Groups/";
    private static final String AVAILABLE_GID_CMD = "dscl . -list /Groups gid | awk '{print $2}' | sort -ug | tail -1";
    private static final String ADD_USER_TO_GROUP_CMD_PREFIX = "sudo dscl . -append /Groups/";

    @Override
    public void createUser(String user) throws IOException {
        AtomicLong uniqueUid = new AtomicLong();
        runCmd(AVAILABLE_UNIQUE_ID_CMD, o -> {
            uniqueUid.set(Long.parseLong(o.toString().replaceAll("\\n", "")) + 1L);
        }, "Cannot get a unique id for creating user");
        runCmd(CREATE_USER_CMD_PREFIX + user, o -> {}, "Failed to create user");
        runCmd(CREATE_USER_CMD_PREFIX + user + " UserShell /bin/bash", o -> {}, "Failed to add shell");
        runCmd(CREATE_USER_CMD_PREFIX + user + " UniqueID " + uniqueUid.get(), o -> {},
                "Failed to add user id");
        runCmd(CREATE_USER_CMD_PREFIX + user + " PrimaryGroupID " + uniqueUid.get(), o -> {},
                "Failed to add group id");
    }

    @Override
    public void createGroup(String group) throws IOException {
        AtomicLong gid = new AtomicLong();
        runCmd(AVAILABLE_GID_CMD, o -> {
            gid.set(Long.parseLong(o.toString().replaceAll("\\n", "")) + 1L);
        }, "Cannot get a unique gid for creating group");
        runCmd(CREATE_GROUP_CMD_PREFIX + group, o -> {}, "Failed to create group");
        runCmd(CREATE_GROUP_CMD_PREFIX + group + " PrimaryGroupID " + gid.get(), o -> {},
                "Failed to add gid");
    }

    @Override
    public void addUserToGroup(String user, String group) throws IOException {
        runCmd(ADD_USER_TO_GROUP_CMD_PREFIX + group + " GroupMembership " + user, o -> {},
                "Failed to add user to group");
    }

    @Override
    public String getPrivilegedGroup() {
        return PRIVILEGED_GROUP;
    }

    @Override
    Set<Integer> getChildPids(PidProcess pp) throws IOException, InterruptedException {
        // Use PS to list process PID and parent PID so that we can identify the process tree
        logger.atDebug().log("Running ps to identify child processes of pid {}", pp.getPid());
        Process proc = Runtime.getRuntime().exec(new String[]{"ps", "-ax", "-o", "pid,ppid"});
        proc.waitFor();
        if (proc.exitValue() != 0) {
            logger.atWarn().kv("pid", pp.getPid()).kv("exit-code", proc.exitValue())
                    .kv(STDOUT, inputStreamToString(proc.getInputStream()))
                    .kv(STDERR, inputStreamToString(proc.getErrorStream())).log("ps exited non-zero");
            throw new IOException("ps exited with " + proc.exitValue());
        }

        try (InputStreamReader reader = new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8);
             BufferedReader br = new BufferedReader(reader)) {
            Stream<String> lines = br.lines();
            Map<String, String> pidToParent = lines.map(s -> {
                Matcher matches = PS_PID_PATTERN.matcher(s.trim());
                if (matches.matches()) {
                    return new Pair<>(matches.group(1), matches.group(2));
                }
                return null;
            }).filter(Objects::nonNull).collect(Collectors.toMap(Pair::getLeft, Pair::getRight));

            Map<String, List<String>> parentToChildren = Utils.inverseMap(pidToParent);
            List<String> childProcesses = children(Integer.toString(pp.getPid()), parentToChildren);

            return childProcesses.stream().map(Integer::parseInt).collect(Collectors.toSet());
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
