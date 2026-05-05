/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix.linux;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Cgroup v2 implementation of CgroupManager.
 * Key differences from cgroup v1:
 * - Uses unified hierarchy: all controllers share the same directory tree
 * - Single mount point at /sys/fs/cgroup (vs multiple subsystem mounts in v1)
 * - Requires explicit controller delegation via cgroup.subtree_control files
 * - Different file names: cpu.max (vs cpu.cfs_quota_us), memory.max (vs memory.limit_in_bytes)
 * - Simplified freezer: cgroup.freeze (vs freezer.state with FROZEN/THAWED values)
 * Hierarchy structure:
 * /sys/fs/cgroup/                    (root - all controllers available)
 * ├── cgroup.subtree_control        (enable controllers: "+memory +cpu +pids")
 * └── greengrass/                   (greengrass namespace)
 *     ├── cgroup.subtree_control    (delegate controllers to components)
 *     └── ComponentName/            (individual component cgroups)
 *         ├── memory.max            (memory limit)
 *         ├── cpu.max               (CPU quota and period)
 *         └── cgroup.freeze         (freeze state: 0=thawed, 1=frozen)
 */
@SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
        justification = "CGroupV2 virtual filesystem path cannot be relative")
public enum CgroupV2 implements CgroupManager {
    Memory("memory"), 
    CPU("cpu"), 
    Freezer("freezer"),
    General("general"); // for initialization

    private static volatile boolean delegationSetup = false;
    
    // v2-specific constants
    private static final Logger logger = LogManager.getLogger(CgroupV2.class);
    private static final String MEMORY_LIMIT_FILE = "memory.max";
    private static final String CPU_LIMIT_FILE = "cpu.max";
    private static final String FREEZER_STATE_FILE = "cgroup.freeze";
    private static final String SUBTREE_CONTROL_FILE = "cgroup.subtree_control";
    private static final String SUBTREE_CONTROL_FILE_CONTENT = "+cpu +io +memory +pids";
    
    // Freezer state constants
    private static final String FROZEN_STATE = "1";
    private static final String THAWED_STATE = "0";
    
    private final String subsystem;
    private final String mountSrc;
    
    CgroupV2(String subsystem) {
        this.subsystem = subsystem;
        this.mountSrc = "none"; // v2 uses "none" as mount source
    }
    
    @Override
    public Path getSubsystemRootPath() {
        // v2 uses unified hierarchy - no subsystem directories
        return getRootPath();
    }
    
    @Override
    public Path getComponentMemoryLimitPath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(MEMORY_LIMIT_FILE);
    }

    public Path getComponentCpuLimitPath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(CPU_LIMIT_FILE);
    }
    
    @Override
    public Path getCgroupFreezerStateFilePath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(FREEZER_STATE_FILE);
    }
    
    @Override
    public String getRootMountCommand() {
        return String.format("mount -t cgroup2 %s %s", mountSrc, CGROUP_ROOT);
    }
    
    @Override
    public String getSubsystemMountCommand() {
        // v2 doesn't need separate subsystem mounts
        return getRootMountCommand();
    }
    
    @Override
    public void setCpuLimit(String componentName, double cpuRatio) throws IOException {
        Path cpuMaxPath = getComponentCpuLimitPath(componentName);
        int period = 100_000; // default period (100ms)

        try {
            String current = new String(Files.readAllBytes(cpuMaxPath), StandardCharsets.UTF_8).trim();
            String[] parts = current.split("\\s+");
            if (parts.length >= 2) {
                int existingPeriod = Integer.parseInt(parts[1]);
                if (existingPeriod > 0) {
                    period = existingPeriod;
                }
            }
        } catch (IOException | NumberFormatException e) {
            // Log: fallback to default period
            String errorMessage = "Failed to read or parse existing cpu.max, using default period";
            logger.atError().setCause(e).log(errorMessage);
        }

        long quota = Math.round(period * cpuRatio);
        if (quota <= 0) {
            quota = 1; // avoid invalid 0 quota
        }

        String latestCpuMaxContent = String.format("%d %d", quota, period);
        Files.write(cpuMaxPath,
                latestCpuMaxContent.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.TRUNCATE_EXISTING);

        logger.atInfo().log("Set CPU limit for {} to {} {}", componentName, quota, period);
    }

    @Override
    public String getFrozenState() {
        return FROZEN_STATE;
    }
    
    @Override
    public void initializeCgroup(String componentName, LinuxPlatform platform) throws IOException {
        // v2 uses base initialization first (mounting)
        initializeCgroupBase(componentName, platform);
        
        // Set up delegation once after mounting
        setupDelegationOnce();
    }
    
    /**
     * Set up controller delegation for cgroup v2 once after mounting (thread-safe).
     * 
     * @throws IOException if writing to cgroup.subtree_control files fails
     */
    private static void setupDelegationOnce() throws IOException {
        if (!delegationSetup) {
            synchronized (CgroupV2.class) {
                if (!delegationSetup) {
                    // enable controllers in root cgroup for delegation
                    enableControllersInRoot();
                    
                    // enable controllers in greengrass cgroup for component delegation
                    enableControllersInGreengrass();
                    
                    delegationSetup = true;
                }
            }
        }
    }
    
    private static void enableControllersInRoot() throws IOException {
        Path subtreeControl = Paths.get(CGROUP_ROOT).resolve(SUBTREE_CONTROL_FILE);
        Files.write(subtreeControl, SUBTREE_CONTROL_FILE_CONTENT.getBytes(StandardCharsets.UTF_8));
    }
    
    private static void enableControllersInGreengrass() throws IOException {
        Path subtreeControl = Paths.get(CGROUP_ROOT).resolve(GG_NAMESPACE).resolve(SUBTREE_CONTROL_FILE);
        Files.write(subtreeControl, SUBTREE_CONTROL_FILE_CONTENT.getBytes(StandardCharsets.UTF_8));
    }
    
    @Override
    public String getThawedState() {
        return THAWED_STATE;
    }
}
