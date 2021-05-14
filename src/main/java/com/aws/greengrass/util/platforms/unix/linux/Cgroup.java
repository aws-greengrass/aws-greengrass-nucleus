/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix.linux;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Represents Linux cgroup v1 subsystems.
 */
@SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME", justification = "Cgroup virtual filesystem path "
        + "cannot be relative")
public enum Cgroup {
    Memory("memory"), CPU("cpu,cpuacct"), Freezer("freezer", "freezer");

    private static final String CGROUP_ROOT = "/sys/fs/cgroup";
    private static final String GG_NAMESPACE = "greengrass";
    private static final String CGROUP_MEMORY_LIMITS = "memory.limit_in_bytes";
    private static final String CPU_CFS_PERIOD_US = "cpu.cfs_period_us";
    private static final String CPU_CFS_QUOTA_US = "cpu.cfs_quota_us";
    private static final String CGROUP_PROCS = "cgroup.procs";
    private static final String FREEZER_STATE_FILE = "freezer.state";

    private final String osString;
    private final String mountSrc;

    Cgroup(String str) {
        osString = str;
        mountSrc = "cgroup";
    }

    Cgroup(String str, String mountSrc) {
        this.osString = str;
        this.mountSrc = mountSrc;
    }

    public static Path getRootPath() {
        return Paths.get(CGROUP_ROOT);
    }

    public static String rootMountCmd() {
        return String.format("mount -t tmpfs cgroup %s", CGROUP_ROOT);
    }

    public String subsystemMountCmd() {
        return String.format("mount -t cgroup -o %s %s %s", osString, mountSrc, getSubsystemRootPath());
    }

    public Path getSubsystemRootPath() {
        return Paths.get(CGROUP_ROOT).resolve(osString);
    }

    public Path getSubsystemGGPath() {
        return getSubsystemRootPath().resolve(GG_NAMESPACE);
    }

    public Path getSubsystemComponentPath(String componentName) {
        return getSubsystemGGPath().resolve(componentName);
    }

    public Path getComponentMemoryLimitPath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(CGROUP_MEMORY_LIMITS);
    }

    public Path getComponentCpuPeriodPath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(CPU_CFS_PERIOD_US);
    }

    public Path getComponentCpuQuotaPath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(CPU_CFS_QUOTA_US);
    }

    public Path getCgroupProcsPath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(CGROUP_PROCS);
    }

    public Path getCgroupFreezerStateFilePath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(FREEZER_STATE_FILE);
    }
}
