/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix.linux;

import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.util.Utils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

@SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
        justification = "CGroupV1 virtual filesystem path cannot be relative")
public enum CGroupV1 implements CGroupSubSystemPaths {
    Memory("memory", ""), CPU("cpu,cpuacct", ""), Freezer("freezer", "freezer");

    private String osString;
    private String mountSrc;

    CGroupV1(String osString, String mountSrc) {
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

    public String subsystemMountCmd() {
        return String.format("mount -t cgroup -o %s %s %s", osString, mountSrc, getSubsystemRootPath());
    }

    @Override
    public Path getSubsystemRootPath() {
        return CGROUP_ROOT.resolve(osString);
    }

    @Override
    public Path getComponentMemoryLimitPath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(CGROUP_MEMORY_LIMITS);
    }

    public Path getComponentCpuPeriodPath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(CPU_CFS_PERIOD_US);
    }

    public Path getComponentCpuQuotaPath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(CPU_CFS_QUOTA_US);
    }

    @Override
    public Path getCgroupFreezerStateFilePath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(FREEZER_STATE_FILE);
    }

    @Override
    public void initializeCgroup(GreengrassService component, LinuxPlatform platform)
            throws IOException {
        initializeCgroupCore(component, platform, () -> {
                    platform.runCmd(subsystemMountCmd(), o -> {
                    }, "Failed to mount cgroup subsystem");
                }
        );
    }

    @Override
    public void handleCpuLimits(GreengrassService component, double cpu) throws IOException {
        byte[] content = Files.readAllBytes(
                getComponentCpuPeriodPath(component.getServiceName()));
        int cpuPeriodUs = Integer.parseInt(new String(content, StandardCharsets.UTF_8).trim());

        int cpuQuotaUs = (int) (cpuPeriodUs * cpu);
        String cpuQuotaUsStr = Integer.toString(cpuQuotaUs);

        Files.write(getComponentCpuQuotaPath(component.getServiceName()),
                cpuQuotaUsStr.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void pauseComponentProcessesCore(GreengrassService component)
            throws IOException {
        if (LinuxSystemResourceController.CgroupFreezerState.FROZEN.equals(
                currentFreezerCgroupState(component.getServiceName()))) {
            return;
        }
        Files.write(getCgroupFreezerStateFilePath(component.getServiceName()),
                LinuxSystemResourceController.CgroupFreezerState.FROZEN.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Override
    public void resumeComponentProcesses(GreengrassService component) throws IOException {
        if (LinuxSystemResourceController.CgroupFreezerState.THAWED.equals(
                currentFreezerCgroupState(component.getServiceName()))) {
            return;
        }

        Files.write(getCgroupFreezerStateFilePath(component.getServiceName()),
                LinuxSystemResourceController.CgroupFreezerState.THAWED.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private LinuxSystemResourceController.CgroupFreezerState currentFreezerCgroupState(String component)
            throws IOException {
        List<String> stateFileContent =
                Files.readAllLines(getCgroupFreezerStateFilePath(component));
        if (Utils.isEmpty(stateFileContent) || stateFileContent.size() != 1) {
            throw new IOException("Unexpected error reading freezer cgroup state");
        }
        return LinuxSystemResourceController.CgroupFreezerState.valueOf(stateFileContent.get(0).trim());
    }
}
