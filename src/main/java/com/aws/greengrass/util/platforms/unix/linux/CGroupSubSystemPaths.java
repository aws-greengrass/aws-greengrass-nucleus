/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix.linux;

import com.aws.greengrass.lifecyclemanager.GreengrassService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
        justification = "CGroupSubSystemPath virtual filesystem path cannot be relative")
public interface CGroupSubSystemPaths {
    Path CGROUP_ROOT = Paths.get("/sys/fs/cgroup");
    String GG_NAMESPACE = "greengrass";
    String CGROUP_MEMORY_LIMITS = "memory.limit_in_bytes";
    String CPU_CFS_PERIOD_US = "cpu.cfs_period_us";
    String CPU_CFS_QUOTA_US = "cpu.cfs_quota_us";
    String CGROUP_PROCS = "cgroup.procs";
    String FREEZER_STATE_FILE = "freezer.state";
    String CPU_MAX = "cpu.max";
    String MEMORY_MAX = "memory.max";
    String CGROUP_SUBTREE_CONTROL = "cgroup.subtree_control";
    String CGROUP_FREEZE = "cgroup.freeze";
    String MOUNT_PATH = "/proc/self/mounts";
    String UNICODE_SPACE = "\\040";

    default Path getRootPath() {
        return CGROUP_ROOT;
    }

    String rootMountCmd();

    Path getSubsystemRootPath();

    default Path getSubsystemGGPath() {
        return getSubsystemRootPath().resolve(GG_NAMESPACE);
    }

    default Path getSubsystemComponentPath(String componentName) {
        return getSubsystemGGPath().resolve(componentName);
    }

    Path getComponentMemoryLimitPath(String componentName);

    default Path getCgroupProcsPath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(CGROUP_PROCS);
    }

    Path getCgroupFreezerStateFilePath(String componentName);

    void initializeCgroup(GreengrassService component, LinuxPlatform platform)
            throws IOException;

    /**
     * Initialize cgroup core method.
     *
     * @param component      component
     * @param platform       platform
     * @param mountSubSystem mount subsystem method
     * @throws IOException IOException
     */
    default void initializeCgroupCore(GreengrassService component, LinuxPlatform platform,
                                      InitializeCgroup mountSubSystem) throws IOException {
        Set<String> mounts = getMountedPaths();

        if (!mounts.contains(getRootPath().toString())) {
            platform.runCmd(rootMountCmd(), o -> {
            }, "Failed to mount cgroup root");
            Files.createDirectory(getSubsystemRootPath());
        }

        if (!mounts.contains(getSubsystemRootPath().toString())) {
            mountSubSystem.add();
        }

        if (!Files.exists(getSubsystemGGPath())) {
            Files.createDirectory(getSubsystemGGPath());
        }
        if (!Files.exists(getSubsystemComponentPath(component.getServiceName()))) {
            Files.createDirectory(getSubsystemComponentPath(component.getServiceName()));
        }
    }

    void handleCpuLimits(GreengrassService component, double cpu) throws IOException;

    void pauseComponentProcessesCore(GreengrassService component) throws IOException;

    void resumeComponentProcesses(GreengrassService component) throws IOException;

    /**
     * Get mounted paths.
     *
     * @return A set of String
     * @throws IOException IOException
     */
    default Set<String> getMountedPaths() throws IOException {
        Set<String> mountedPaths = new HashSet<>();

        Path procMountsPath = Paths.get(MOUNT_PATH);
        List<String> mounts = Files.readAllLines(procMountsPath);
        for (String mount : mounts) {
            String[] split = mount.split(" ");
            // As reported in fstab(5) manpage, struct is:
            // 1st field is volume name
            // 2nd field is path with spaces escaped as \040
            // 3rd field is fs type
            // 4th field is mount options
            // 5th field is used by dump(8) (ignored)
            // 6th field is fsck order (ignored)
            if (split.length < 6) {
                continue;
            }

            // We only need the path of the mounts to verify whether cgroup is mounted
            String path = split[1].replace(UNICODE_SPACE, " ");
            mountedPaths.add(path);
        }
        return mountedPaths;
    }
}
