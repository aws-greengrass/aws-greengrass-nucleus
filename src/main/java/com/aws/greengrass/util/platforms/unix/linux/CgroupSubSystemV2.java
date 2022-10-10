/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix.linux;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.nio.file.Path;
import java.nio.file.Paths;

@SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
        justification = "CgroupSubSystemV2 virtual filesystem path cannot be relative")
public enum CgroupSubSystemV2 implements CGroupSubSystemPath {
    Memory, CPU, Freezer, Unified;

    @Override
    public String rootMountCmd() {
        return String.format("mount -t cgroup2 none %s", CGROUP_ROOT);
    }

    @Override
    public Path getSubsystemRootPath() {
        return Paths.get(CGROUP_ROOT);
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
        return getSubsystemComponentPath(componentName).resolve(MEMORY_MAX);
    }

    @Override
    public Path getCgroupProcsPath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(CGROUP_PROCS);
    }

    @Override
    public Path getCgroupFreezerStateFilePath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(CGROUP_FREEZE);
    }

    @Override
    public Path getRootSubTreeControlPath() {
        return getSubsystemRootPath().resolve(CGROUP_SUBTREE_CONTROL);
    }

    @Override
    public Path getGGSubTreeControlPath() {
        return getSubsystemGGPath().resolve(CGROUP_SUBTREE_CONTROL);
    }

    @Override
    public Path getComponentCpuMaxPath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(CPU_MAX);
    }

    @Override
    public Path getCgroupFreezePath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(CGROUP_FREEZE);
    }
}
