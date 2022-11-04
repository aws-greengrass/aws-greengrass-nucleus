/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix.linux;

import com.aws.greengrass.lifecyclemanager.GreengrassService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

@SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
        justification = "CGroupV2 virtual filesystem path cannot be relative")
public enum CGroupV2 implements CGroupSubSystemPaths {
    Memory, CPU, Freezer, Unified;
    private static final String CGROUP_SUBTREE_CONTROL_CONTENT = "+cpuset +cpu +io +memory +pids";

    @Override
    public String rootMountCmd() {
        return String.format("mount -t cgroup2 none %s", CGROUP_ROOT);
    }

    @Override
    public Path getSubsystemRootPath() {
        return CGROUP_ROOT;
    }

    @Override
    public Path getComponentMemoryLimitPath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(MEMORY_MAX);
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

    @Override
    public void initializeCgroup(GreengrassService component, LinuxPlatform platform) throws IOException {
        Set<String> mounts = getMountedPaths();

        if (!mounts.contains(getRootPath().toString())) {
            platform.runCmd(rootMountCmd(), o -> {
            }, "Failed to mount cgroup root");
            Files.createDirectory(getSubsystemRootPath());
        }

        if (!Files.exists(getSubsystemGGPath())) {
            Files.createDirectory(getSubsystemGGPath());
        }
        if (!Files.exists(getSubsystemComponentPath(component.getServiceName()))) {
            Files.createDirectory(getSubsystemComponentPath(component.getServiceName()));
        }

        //Enable controllers for root group
        Files.write(getRootSubTreeControlPath(),
                CGROUP_SUBTREE_CONTROL_CONTENT.getBytes(StandardCharsets.UTF_8));
        //Enable controllers for gg group
        Files.write(getGGSubTreeControlPath(),
                CGROUP_SUBTREE_CONTROL_CONTENT.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void handleCpuLimits(GreengrassService component, double cpu) throws IOException {
        byte[] content = Files.readAllBytes(
                getComponentCpuMaxPath(component.getServiceName()));
        String cpuMaxContent = new String(content, StandardCharsets.UTF_8).trim();
        String[] cpuMaxContentArr = cpuMaxContent.split(" ");
        String cpuMaxStr = "max";
        String cpuPeriodStr = "100000";

        if (cpuMaxContentArr.length >= 2) {
            cpuMaxStr = cpuMaxContentArr[0];
            cpuPeriodStr = cpuMaxContentArr[1];

            if (!StringUtils.isEmpty(cpuPeriodStr)) {
                int period = Integer.parseInt(cpuPeriodStr.trim());
                int max = (int) (period * cpu);
                cpuMaxStr = Integer.toString(max);
            }
        }

        String latestCpuMaxContent = String.format("%s %s", cpuMaxStr, cpuPeriodStr);
        Files.write(getComponentCpuMaxPath(component.getServiceName()),
                latestCpuMaxContent.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void pauseComponentProcessesCore(GreengrassService component)
            throws IOException {
        Files.write(getCgroupFreezerStateFilePath(component.getServiceName()),
                String.valueOf(CgroupV2FreezerState.FROZEN.getIndex()).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Override
    public void resumeComponentProcesses(GreengrassService component) throws IOException {
        Files.write(getCgroupFreezerStateFilePath(component.getServiceName()),
                String.valueOf(CgroupV2FreezerState.THAWED.getIndex()).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.TRUNCATE_EXISTING);
    }
}
