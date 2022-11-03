/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix.linux;

import com.aws.greengrass.lifecyclemanager.GreengrassService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Represents Linux cgroup subsystems.
 */
public class Cgroup {
    private final CGroupSubSystemPath subSystem;

    public Cgroup(CGroupSubSystemPath subSystem) {
        this.subSystem = subSystem;
    }

    public Path getRootPath() {
        return subSystem.getRootPath();
    }

    /**
     * root mount cmd.
     *
     * @return mount command string
     */
    public String rootMountCmd() {
        return subSystem.rootMountCmd();
    }

    public String subsystemMountCmd() {
        return subSystem.subsystemMountCmd();
    }

    public Path getSubsystemRootPath() {
        return subSystem.getSubsystemRootPath();
    }

    public Path getSubsystemGGPath() {
        return subSystem.getSubsystemGGPath();
    }

    public Path getSubsystemComponentPath(String componentName) {
        return subSystem.getSubsystemComponentPath(componentName);
    }

    /**
     * get component memory limit path.
     *
     * @param componentName componentName
     * @return memory limit Path
     */
    public Path getComponentMemoryLimitPath(String componentName) {
        return subSystem.getComponentMemoryLimitPath(componentName);
    }

    public Path getComponentCpuPeriodPath(String componentName) {
        return subSystem.getComponentCpuPeriodPath(componentName);
    }

    public Path getComponentCpuQuotaPath(String componentName) {
        return subSystem.getComponentCpuQuotaPath(componentName);
    }

    public Path getCgroupProcsPath(String componentName) {
        return subSystem.getCgroupProcsPath(componentName);
    }

    /**
     * get cgroup freezer path.
     *
     * @param componentName componentName
     * @return cgroup freezer path
     */
    public Path getCgroupFreezerStateFilePath(String componentName) {
        return subSystem.getCgroupFreezerStateFilePath(componentName);
    }

    public Path getRootSubTreeControlPath() {
        return subSystem.getRootSubTreeControlPath();
    }

    public Path getGGSubTreeControlPath() {
        return subSystem.getGGSubTreeControlPath();
    }

    public Path getComponentCpuMaxPath(String componentName) {
        return subSystem.getComponentCpuMaxPath(componentName);
    }

    public Path getCgroupFreezePath(String componentName) {
        return subSystem.getCgroupFreezePath(componentName);
    }

    public void initializeCgroup(GreengrassService component, LinuxPlatform platform) throws IOException {
        subSystem.initializeCgroup(component, platform);
    }

    public void handleCpuLimits(GreengrassService component, double cpu) throws IOException {
        subSystem.handleCpuLimits(component, cpu);
    }

    public void pauseComponentProcessesCore(GreengrassService component, List<Process> processes)
            throws IOException {
        subSystem.pauseComponentProcessesCore(component, processes);
    }

    public void resumeComponentProcesses(GreengrassService component) throws IOException {
        subSystem.resumeComponentProcesses(component);
    }

    protected Path freezerCgroupStateFile(String component) {
        return getCgroupFreezerStateFilePath(component);
    }
}
