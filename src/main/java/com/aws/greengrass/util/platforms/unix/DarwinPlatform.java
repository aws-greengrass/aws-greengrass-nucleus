/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;

public class DarwinPlatform extends UnixPlatform {
    protected static final String SETSID_PATH = "/usr/local/opt/util-linux/bin/setsid";
    private static String PRIVILEGED_GROUP = "wheel";

    private static String CREATE_USER_CMD_PREFIX = "sudo dscl . -create /Users/";
    private static String AVAILABLE_UNIQUE_ID_CMD =
            "dscl . -list /Users UniqueID | awk '{print $2}' | sort -ug | tail -1";
    private static String CREATE_GROUP_CMD_PREFIX = "sudo dscl . -create /Groups/";
    private static String AVAILABLE_GID_CMD = "dscl . -list /Groups gid | awk '{print $2}' | sort -ug | tail -1";
    private static String ADD_USER_TO_GROUP_CMD_PREFIX = "sudo dscl . -append /Groups/";

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
    public String[] finalDecorateCommand(String[] command) {
        if (canUseSetsid()) {
            String[] arr = new String[command.length + 1];
            arr[0] = SETSID_PATH;
            System.arraycopy(command, 0, arr, 1, command.length);
            return arr;
        }
        return super.finalDecorateCommand(command);
    }

    @SuppressFBWarnings("DMI_HARDCODED_ABSOLUTE_FILENAME")
    @Override
    protected boolean canUseSetsid() {
        // Mac doesn't ship with setsid but it can be installed using `brew install util-linux`
        return Files.exists(Paths.get(SETSID_PATH));
    }
}
