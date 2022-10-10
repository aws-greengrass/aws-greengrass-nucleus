/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix.linux;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.nio.file.Path;
import java.nio.file.Paths;

@SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
        justification = "CGroupSubSystemPath virtual filesystem path cannot be relative")
public interface CGroupSubSystemPath {
    String CGROUP_ROOT = "/sys/fs/cgroup";
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

    default Path getRootPath() {
        return Paths.get(CGROUP_ROOT);
    }

    String rootMountCmd();

    default String subsystemMountCmd() {
        return null;
    }

    Path getSubsystemRootPath();

    Path getSubsystemGGPath();

    Path getSubsystemComponentPath(String componentName);

    Path getComponentMemoryLimitPath(String componentName);

    default Path getComponentCpuPeriodPath(String componentName) {
        return null;
    }

    default Path getComponentCpuQuotaPath(String componentName) {
        return null;
    }

    Path getCgroupProcsPath(String componentName);

    Path getCgroupFreezerStateFilePath(String componentName);

    default Path getRootSubTreeControlPath() {
        return null;
    }

    default Path getGGSubTreeControlPath() {
        return null;
    }

    default Path getComponentCpuMaxPath(String componentName) {
        return null;
    }

    default Path getCgroupFreezePath(String componentName) {
        return null;
    }
}
