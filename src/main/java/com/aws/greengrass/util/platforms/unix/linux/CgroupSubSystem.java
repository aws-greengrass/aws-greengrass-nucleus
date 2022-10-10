/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix.linux;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.nio.file.Path;
import java.nio.file.Paths;

@SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
        justification = "CgroupSubSystem virtual filesystem path cannot be relative")
public enum CgroupSubSystem implements CGroupSubSystemPath {
    Memory("memory", ""), CPU("cpu,cpuacct", ""), Freezer("freezer", "freezer");

    private String osString;
    private String mountSrc;

    CgroupSubSystem(String osString, String mountSrc) {
        this.osString = osString;
        this.mountSrc = mountSrc;
    }

    /**
     * Get the osString associated with this CgroupSubController.
     *
     * @return the osString associated with this CgroupSubController.
     */
    public String getOsString() {
        return osString;
    }

    /**
     * Get the mountSrc associated with this CgroupSubController.
     *
     * @return the mountSrc associated with this CgroupSubController.
     */
    public String getMountSrc() {
        return mountSrc;
    }

    @Override
    public String rootMountCmd() {
        return String.format("mount -t tmpfs cgroup %s", CGROUP_ROOT);
    }

    @Override
    public String subsystemMountCmd() {
        return String.format("mount -t cgroup -o %s %s %s", osString, mountSrc, getSubsystemRootPath());
    }

    @Override
    public Path getSubsystemRootPath() {
        return Paths.get(CGROUP_ROOT).resolve(osString);
    }

    @Override
    public Path getSubsystemGGPath() {
        return getSubsystemRootPath().resolve(GG_NAMESPACE);
    }

    @Override
    public Path getSubsystemComponentPath(String componentName) {
        return getSubsystemGGPath().resolve(componentName);
    }

    @Override
    public Path getComponentMemoryLimitPath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(CGROUP_MEMORY_LIMITS);
    }

    @Override
    public Path getComponentCpuPeriodPath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(CPU_CFS_PERIOD_US);
    }

    @Override
    public Path getComponentCpuQuotaPath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(CPU_CFS_QUOTA_US);
    }

    @Override
    public Path getCgroupProcsPath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(CGROUP_PROCS);
    }

    @Override
    public Path getCgroupFreezerStateFilePath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(FREEZER_STATE_FILE);
    }
}
