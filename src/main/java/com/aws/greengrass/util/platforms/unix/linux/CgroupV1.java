/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix.linux;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Cgroup v1 implementation of CgroupManager.
 * Each enum instance represents a different cgroup v1 subsystem.
 */
@SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
        justification = "CGroupV1 virtual filesystem path cannot be relative")
public enum CgroupV1 implements CgroupManager {
    Memory("memory"), 
    CPU("cpu,cpuacct"), 
    Freezer("freezer", "freezer");
    
    // v1-specific constants
    private static final String MEMORY_LIMIT_FILE = "memory.limit_in_bytes";
    private static final String CPU_PERIOD_FILE = "cpu.cfs_period_us";
    private static final String CPU_QUOTA_FILE = "cpu.cfs_quota_us";
    private static final String FREEZER_STATE_FILE = "freezer.state";
    
    // Freezer state constants
    private static final String FROZEN_STATE = "FROZEN";
    private static final String THAWED_STATE = "THAWED";
    
    private final String subsystem;
    private final String mountSrc;
    
    CgroupV1(String subsystem) {
        this.subsystem = subsystem;
        this.mountSrc = "cgroup";
    }
    
    CgroupV1(String subsystem, String mountSrc) {
        this.subsystem = subsystem;
        this.mountSrc = mountSrc;
    }
    
    @Override
    public Path getSubsystemRootPath() {
        return Paths.get(CGROUP_ROOT).resolve(subsystem);
    }
    
    @Override
    public Path getComponentMemoryLimitPath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(MEMORY_LIMIT_FILE);
    }
    
    @Override
    public Path getCgroupFreezerStateFilePath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(FREEZER_STATE_FILE);
    }
    
    // helper methods for CPU operations
    public Path getComponentCpuPeriodPath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(CPU_PERIOD_FILE);
    }
    
    public Path getComponentCpuQuotaPath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(CPU_QUOTA_FILE);
    }
    
    @Override
    public String getRootMountCommand() {
        return String.format("mount -t tmpfs cgroup %s", CGROUP_ROOT);
    }
    
    @Override
    public String getSubsystemMountCommand() {
        return String.format("mount -t cgroup -o %s %s %s", subsystem, mountSrc, getSubsystemRootPath());
    }
    
    @Override
    public void setCpuLimit(String componentName, double cpuRatio) throws IOException {
        // Read current CPU period
        byte[] content = Files.readAllBytes(getComponentCpuPeriodPath(componentName));
        String periodStr = new String(content, StandardCharsets.UTF_8).trim();
        
        int cpuPeriodUs;
        try {
            cpuPeriodUs = Integer.parseInt(periodStr);
        } catch (NumberFormatException e) {
            throw new IOException("Failed to parse CPU period for component " + componentName
                    + ": invalid value '" + periodStr + "'", e);
        }
        
        // Calculate quota based on ratio (e.g., 0.5 * 100000 = 50000)
        int cpuQuotaUs = (int) (cpuPeriodUs * cpuRatio);
        String cpuQuotaUsStr = Integer.toString(cpuQuotaUs);
        
        // Write the calculated quota
        Files.write(getComponentCpuQuotaPath(componentName), 
                   cpuQuotaUsStr.getBytes(StandardCharsets.UTF_8));
    }
    
    @Override
    public String getFrozenState() {
        return FROZEN_STATE;
    }
    
    @Override
    public void initializeCgroup(String componentName, LinuxPlatform platform) throws IOException {
        // v1 just uses the base initialization
        initializeCgroupBase(componentName, platform);
    }
    
    @Override
    public String getThawedState() {
        return THAWED_STATE;
    }
}
