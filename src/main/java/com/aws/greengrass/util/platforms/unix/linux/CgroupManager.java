/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix.linux;

import com.aws.greengrass.util.Utils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.aws.greengrass.util.platforms.Platform.logger;
import static org.apache.commons.io.FileUtils.ONE_KB;

/**
 * Interface for managing cgroup operations across different cgroup versions (v1 and v2).
 * Provides abstraction for path generation, resource management, and process control.
 */
@SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
        justification = "Cgroup virtual filesystem path cannot be relative")
public interface CgroupManager {
    
    // Common constants shared by both v1 and v2
    String CGROUP_ROOT = "/sys/fs/cgroup";
    String GG_NAMESPACE = "greengrass";
    String CGROUP_PROCS = "cgroup.procs";
    String MOUNT_PATH = "/proc/self/mounts";
    
    // Common path operations with default implementations for both cgroup versions
    default Path getRootPath() {
        return Paths.get(CGROUP_ROOT);
    }
    
    default Path getCgroupProcsPath(String componentName) {
        return getSubsystemComponentPath(componentName).resolve(CGROUP_PROCS);
    }
    
    default Path getSubsystemGGPath() {
        return getSubsystemRootPath().resolve(GG_NAMESPACE);
    }
    
    default Path getSubsystemComponentPath(String componentName) {
        return getSubsystemGGPath().resolve(componentName);
    }

    /**
     * Set memory limit for a component.
     *
     * @param componentName name of the component
     * @param memoryLimitInKB memory limit in kilobytes
     * @throws IOException if file operations fail
     * @throws IllegalArgumentException if memory limit is not positive
     */
    default void setMemoryLimit(String componentName, long memoryLimitInKB) throws IOException {
        String memoryLimit = Long.toString(memoryLimitInKB * ONE_KB);
        Files.write(getComponentMemoryLimitPath(componentName),
                memoryLimit.getBytes(StandardCharsets.UTF_8));
    }
    
    // Version-specific path operations (must be implemented)
    default Path getSubsystemRootPath() {
        throw new UnsupportedOperationException(
                "getSubsystemRootPath() must be implemented by cgroup version-specific classes");
    }

    Path getComponentMemoryLimitPath(String componentName);

    Path getCgroupFreezerStateFilePath(String componentName);
    
    // Mount operations
    String getRootMountCommand();

    String getSubsystemMountCommand();

    // Resource management operations (handle version differences internally)
    void setCpuLimit(String componentName, double cpuRatio) throws IOException;
    
    /**
     * Get the frozen state value for this cgroup version.
     *
     * @return frozen state value
     */
    String getFrozenState();

    /**
     * Get the thawed state value for this cgroup version.
     *
     * @return thawed state value
     */
    String getThawedState();

    /**
     * Freeze processes in the component's cgroup.
     *
     * @param componentName name of the component
     * @throws IOException if file operations fail
     */
    default void freezeProcesses(String componentName) throws IOException {
        if (!isComponentFrozen(componentName)) {
            Files.write(getCgroupFreezerStateFilePath(componentName),
                       getFrozenState().getBytes(StandardCharsets.UTF_8),
                       StandardOpenOption.TRUNCATE_EXISTING);
        }
    }
    
    /**
     * Thaw processes in the component's cgroup.
     *
     * @param componentName name of the component
     * @throws IOException if file operations fail
     */
    default void thawProcesses(String componentName) throws IOException {
        if (isComponentFrozen(componentName)) {
            Files.write(getCgroupFreezerStateFilePath(componentName),
                       getThawedState().getBytes(StandardCharsets.UTF_8),
                       StandardOpenOption.TRUNCATE_EXISTING);
        }
    }
    
    /**
     * Initialize cgroup for a component (version-specific implementation).
     * v1 and v2 have different initialization requirements.
     *
     * @param componentName name of the component
     * @param platform Linux platform instance for running commands
     * @throws IOException if initialization fails
     */
    void initializeCgroup(String componentName, LinuxPlatform platform) throws IOException;

    /**
     * Common initialization logic shared by v1 and v2.
     * Handles mounting and directory creation.
     *
     * @param componentName name of the component
     * @param platform Linux platform instance for running commands
     * @throws IOException if initialization fails
     */
    default void initializeCgroupBase(String componentName, LinuxPlatform platform) throws IOException {
        Set<String> mounts = getMountedPaths();
        
        if (!mounts.contains(getRootPath().toString())) {
            platform.runCmd(getRootMountCommand(), o -> {}, "Failed to mount cgroup root");
            Files.createDirectory(getSubsystemRootPath());
        }

        if (!mounts.contains(getSubsystemRootPath().toString())) {
            platform.runCmd(getSubsystemMountCommand(), o -> {}, "Failed to mount cgroup subsystem");
        }
        
        if (!Files.exists(getSubsystemGGPath())) {
            Files.createDirectory(getSubsystemGGPath());
        }
        
        if (!Files.exists(getSubsystemComponentPath(componentName))) {
            Files.createDirectory(getSubsystemComponentPath(componentName));
        }
    }

    /**
     * Get mounted filesystem paths from /proc/self/mounts.
     *
     * @return set of mounted filesystem paths
     * @throws IOException if reading /proc/self/mounts fails
     */
    default Set<String> getMountedPaths() throws IOException {
        Set<String> mountedPaths = new HashSet<>();
        Path procMountsPath = Paths.get(MOUNT_PATH);
        List<String> mounts = Files.readAllLines(procMountsPath);
        
        for (String mount : mounts) {
            String[] split = mount.split(" ");
            if (split.length < 6) {
                continue;
            }
            String path = split[1].replace("\\040", " ");
            mountedPaths.add(path);
        }
        return mountedPaths;
    }

    /**
     * Check if component processes are currently frozen.
     *
     * @param componentName name of the component
     * @return true if frozen, false if thawed
     * @throws IOException if file operations fail
     */
    default boolean isComponentFrozen(String componentName) throws IOException {
        List<String> stateFileContent = Files.readAllLines(getCgroupFreezerStateFilePath(componentName));
        if (Utils.isEmpty(stateFileContent) || stateFileContent.size() != 1) {
            throw new IOException("Unexpected error reading freezer cgroup state");
        }
        String state = stateFileContent.get(0).trim();
        logger.atInfo().log("current state: {}", state);
        return getFrozenState().equals(state);
    }
}
