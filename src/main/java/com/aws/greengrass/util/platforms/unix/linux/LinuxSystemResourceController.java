/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix.linux;

import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.SystemResourceController;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.zeroturnaround.process.PidUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
        justification = "Cgroup Controller virtual filesystem path cannot be relative")
public class LinuxSystemResourceController implements SystemResourceController {
    private static final Logger logger = LogManager.getLogger(LinuxSystemResourceController.class);
    private static final String COMPONENT_NAME = "componentName";
    private static final String MEMORY_KEY = "memory";
    private static final String CPUS_KEY = "cpus";
    private static final Path CGROUP_V2_CONTROLLERS = Paths.get("/sys/fs/cgroup/cgroup.controllers");
    
    // Cgroup managers for different subsystems
    private CgroupManager memoryManager;
    private CgroupManager cpuManager;
    private CgroupManager freezerManager;
    private CgroupManager generalManager; // general manager for initialization
    private List<CgroupManager> resourceLimitCgroups; // managers used for resource limit mounts

    protected LinuxPlatform platform;

    /**
     * Constructor for LinuxSystemResourceController.
     *
     * @param platform the Linux platform instance
     */
    public LinuxSystemResourceController(LinuxPlatform platform) {
        this.platform = platform;
        
        // Detect cgroup version and initialize appropriate managers
        if (isCgroupV2Supported()) {
            logger.atInfo().log("Detected cgroup v2 system");
            memoryManager = CgroupV2.Memory;
            cpuManager = CgroupV2.CPU;
            freezerManager = CgroupV2.Freezer;
            generalManager = CgroupV2.General; // use General for initialization
            resourceLimitCgroups = Collections.singletonList(generalManager);
        } else {
            logger.atInfo().log("Detected cgroup v1 system");
            memoryManager = CgroupV1.Memory;
            cpuManager = CgroupV1.CPU;
            freezerManager = CgroupV1.Freezer;
            resourceLimitCgroups = Arrays.asList(memoryManager, cpuManager);
        }
    }
    
    /**
     * Determines if the system is using cgroup v2.
     * Cgroup v2 systems have the cgroup.controllers file in the root cgroup directory.
     * 
     * @return true if cgroup v2 is detected, false for cgroup v1
     */
    private boolean isCgroupV2Supported() {
        try {
            return Files.exists(CGROUP_V2_CONTROLLERS);
        } catch (SecurityException e) {
            logger.atWarn().setCause(e)
                    .log("Failed to detect cgroup version due to security restrictions, defaulting to v1");
            return false; // Default to v1 for safety
        }
    }

    @Override
    public void removeResourceController(GreengrassService component) {
        // Remove all cgroup directories for this component
        List<CgroupManager> allManagers = Arrays.asList(memoryManager, cpuManager, freezerManager);
        allManagers.forEach(cgroupManager -> {
            try {
                // Assumes processes belonging to cgroups would already be terminated/killed.
                Files.deleteIfExists(cgroupManager.getSubsystemComponentPath(component.getServiceName()));
            } catch (IOException e) {
                logger.atError().setCause(e).kv(COMPONENT_NAME, component.getServiceName())
                        .log("Failed to remove the resource controller");
            }
        });
    }

    @Override
    public void updateResourceLimits(GreengrassService component, Map<String, Object> resourceLimit) {
        try {
            if (!Files.exists(memoryManager.getSubsystemComponentPath(component.getServiceName()))) {
                logger.atInfo().log("Memory cgroup directory not found. Initializing...");
                initializeCgroup(component, memoryManager);
            }
            if (resourceLimit.containsKey(MEMORY_KEY)) {
                long memoryLimitInKB = Coerce.toLong(resourceLimit.get(MEMORY_KEY));
                if (memoryLimitInKB > 0) {
                    memoryManager.setMemoryLimit(component.getServiceName(), memoryLimitInKB);
                } else {
                    logger.atWarn().kv(COMPONENT_NAME, component.getServiceName()).kv(MEMORY_KEY, memoryLimitInKB)
                            .log("The provided memory limit is invalid");
                }
            }

            if (!Files.exists(cpuManager.getSubsystemComponentPath(component.getServiceName()))) {
                logger.atInfo().log("CPU cgroup directory not found. Initializing...");
                initializeCgroup(component, cpuManager);
            }
            if (resourceLimit.containsKey(CPUS_KEY)) {
                double cpu = Coerce.toDouble(resourceLimit.get(CPUS_KEY));
                if (cpu > 0) {
                    cpuManager.setCpuLimit(component.getServiceName(), cpu);
                } else {
                    logger.atWarn().kv(COMPONENT_NAME, component.getServiceName()).kv(CPUS_KEY, cpu)
                            .log("The provided cpu limit is invalid");
                }
            }
        } catch (IOException e) {
            logger.atError().setCause(e).kv(COMPONENT_NAME, component.getServiceName())
                    .log("Failed to apply resource limits");
        }
    }

    @Override
    public void resetResourceLimits(GreengrassService component) {
        for (CgroupManager cgroupManager : resourceLimitCgroups) {
            try {
                if (Files.exists(cgroupManager.getSubsystemComponentPath(component.getServiceName()))) {
                    Files.delete(cgroupManager.getSubsystemComponentPath(component.getServiceName()));
                    Files.createDirectory(cgroupManager.getSubsystemComponentPath(component.getServiceName()));
                }
            } catch (IOException e) {
                logger.atError().setCause(e).kv(COMPONENT_NAME, component.getServiceName())
                        .log("Failed to remove the resource controller");
            }
        }
    }

    @Override
    public void addComponentProcess(GreengrassService component, Process process) {
        // Add process to memory and CPU cgroups for resource limiting
        resourceLimitCgroups.forEach(cgroupManager -> {
            try {
                addComponentProcessToCgroup(component.getServiceName(), process, cgroupManager);

                // Child processes of a process in a cgroup are auto-added to the same cgroup by linux kernel. But in
                // case of a race condition in starting a child process and us adding pids to cgroup, neither us nor
                // the linux kernel will add it to the cgroup. To account for this, re-list all pids for the component
                // after 1 second and add to cgroup again so that all component processes are resource controlled.
                component.getContext().get(ScheduledExecutorService.class).schedule(() -> {
                    try {
                        addComponentProcessToCgroup(component.getServiceName(), process, cgroupManager);
                    } catch (IOException e) {
                        handleErrorAddingPidToCgroup(e, component.getServiceName());
                    }
                }, 1, TimeUnit.SECONDS);

            } catch (IOException e) {
               handleErrorAddingPidToCgroup(e, component.getServiceName());
            }
        });
    }

    @Override
    public void pauseComponentProcesses(GreengrassService component, List<Process> processes) throws IOException {
        initializeCgroup(component, freezerManager);

        for (Process process: processes) {
            addComponentProcessToCgroup(component.getServiceName(), process, freezerManager);
        }

        freezerManager.freezeProcesses(component.getServiceName());
    }

    @Override
    public void resumeComponentProcesses(GreengrassService component) throws IOException {
        // Use manager method to handle v1/v2 format differences
        freezerManager.thawProcesses(component.getServiceName());
    }

    private void addComponentProcessToCgroup(String component, Process process, CgroupManager cgroupManager)
            throws IOException {

        if (!Files.exists(cgroupManager.getSubsystemComponentPath(component))) {
            logger.atDebug().kv(COMPONENT_NAME, component).kv("resource-controller", cgroupManager.toString())
                    .log("Resource controller is not enabled");
            return;
        }

        if (process != null) {
            try {
                Set<Integer> childProcesses = platform.getChildPids(process);
                childProcesses.add(PidUtil.getPid(process));
                Set<Integer> pidsInCgroup = pidsInComponentCgroup(cgroupManager, component);
                if (!Utils.isEmpty(childProcesses) && Objects.nonNull(pidsInCgroup)
                        && !childProcesses.equals(pidsInCgroup)) {

                    // Writing pid to cgroup.procs file should auto add the pid to tasks file
                    // Once a process is added to a cgroup, its forked child processes inherit its (parent's) settings
                    for (Integer pid : childProcesses) {
                        if (pid == null) {
                            logger.atError().log("The process doesn't exist and is skipped");
                            continue;
                        }

                        Files.write(cgroupManager.getCgroupProcsPath(component),
                                Integer.toString(pid).getBytes(StandardCharsets.UTF_8));
                    }
                }
            } catch (InterruptedException e) {
                logger.atWarn().setCause(e)
                        .log("Interrupted while getting processes to add to system limit controller");
                Thread.currentThread().interrupt();
            }
        }
    }

    protected void initializeCgroup(GreengrassService component, CgroupManager cgroup) throws IOException {
        cgroup.initializeCgroup(component.getServiceName(), platform);
    }

    private void handleErrorAddingPidToCgroup(IOException e, String component) {
        // The process might have exited (if it's a short running process).
        // Check the exception message here to avoid the exception stacktrace failing the tests.
        if (e.getMessage() != null && e.getMessage().contains("No such process")) {
            logger.atWarn().kv(COMPONENT_NAME, component)
                    .log("Failed to add pid to the cgroup because the process doesn't exist anymore");
        } else {
            logger.atError().setCause(e).kv(COMPONENT_NAME, component)
                    .log("Failed to add pid to the cgroup");
        }
    }

    private Set<Integer> pidsInComponentCgroup(CgroupManager cgroupManager, String component) throws IOException {
        return Files.readAllLines(cgroupManager.getCgroupProcsPath(component))
                .stream().map(Integer::parseInt).collect(Collectors.toSet());
    }
}
