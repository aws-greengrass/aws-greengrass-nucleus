/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix;

import com.aws.greengrass.util.Exec;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public class DarwinPlatform extends UnixPlatform {
    private static String CREATE_USER_CMD_PREFIX = "sudo dscl . -create /Users/";
    private static String AVAILABLE_UNIQUE_ID_CMD =
            "dscl . -list /Users UniqueID | awk '{print $2}' | sort -ug | tail -1";
    private static String CREATE_GROUP_CMD_PREFIX = "sudo dscl . -create /Groups/";
    private static String AVAILABLE_GID_CMD = "dscl . -list /Groups gid | awk '{print $2}' | sort -ug | tail -1";
    private static String ADD_USER_TO_GROUP_CMD_PREFIX = "sudo dscl . -append /Groups/";

    @Override
    public void createUser(String user) throws IOException, InterruptedException {
        try (Exec exec = new Exec()) {
            AtomicLong uniqueUid = new AtomicLong();
            Optional<Integer> exit = exec.withExec(AVAILABLE_UNIQUE_ID_CMD.split(" ")).withShell().withOut(o -> {
                uniqueUid.set(Long.parseLong(o.toString().replaceAll("\\n", "")) + 1L);
            }).exec();
            if (!exit.isPresent() && exit.get() != 0) {
                throw new IOException("Cannot get a unique id for creating user");
            }
            Exec.cmd((CREATE_USER_CMD_PREFIX + user).split(" "));
            Exec.cmd((CREATE_USER_CMD_PREFIX + user + " UserShell /bin/bash").split(" "));
            Exec.cmd((CREATE_USER_CMD_PREFIX + user + " UniqueID " + uniqueUid.get()).split(" "));
            Exec.cmd((CREATE_USER_CMD_PREFIX + user + " PrimaryGroupID " + uniqueUid.get()).split(" "));
        }
    }

    @Override
    public void createGroup(String group) throws IOException, InterruptedException {
        try (Exec exec = new Exec()) {
            AtomicLong gid = new AtomicLong();
            Optional<Integer> exit = exec.withExec(AVAILABLE_GID_CMD.split(" ")).withShell().withOut(o -> {
                gid.set(Long.parseLong(o.toString().replaceAll("\\n", "")) + 1L);
            }).exec();
            if (!exit.isPresent() && exit.get() != 0) {
                throw new IOException("Cannot get a unique gid for creating group");
            }
            Exec.cmd((CREATE_GROUP_CMD_PREFIX + group).split(" "));
            Exec.cmd((CREATE_GROUP_CMD_PREFIX + group + " PrimaryGroupID " + gid.get()).split(" "));
        }
    }

    @Override
    public void addUserToGroup(String user, String group) throws IOException, InterruptedException {
        Exec.cmd((ADD_USER_TO_GROUP_CMD_PREFIX + group + " GroupMembership " + user).split(" "));
    }
}
