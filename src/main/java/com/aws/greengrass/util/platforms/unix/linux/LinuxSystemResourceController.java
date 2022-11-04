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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.apache.commons.io.FileUtils.ONE_KB;

@SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
        justification = "Cgroup Controller virtual filesystem path cannot be relative")
public class LinuxSystemResourceController implements SystemResourceController {
    private static final Logger logger = LogManager.getLogger(LinuxSystemResourceController.class);
    private static final String COMPONENT_NAME = "componentName";
    private static final String MEMORY_KEY = "memory";
    private static final String CPUS_KEY = "cpus";
    private CGroupSubSystemPaths memoryCgroup;
    private CGroupSubSystemPaths cpuCgroup;
    private CGroupSubSystemPaths freezerCgroup;
    private CGroupSubSystemPaths unifiedCgroup;
    private List<CGroupSubSystemPaths> resourceLimitCgroups;
    protected CopyOnWriteArrayList<CGroupSubSystemPaths> usedCgroups = new CopyOnWriteArrayList<>();

    protected LinuxPlatform platform;

    /**
     * LinuxSystemResourceController Constructor.
     *
     * @param platform platform
     * @param isV1Used if you use v1
     */
    public LinuxSystemResourceController(LinuxPlatform platform, boolean isV1Used) {
        this.platform = platform;

        if (isV1Used) {
            this.memoryCgroup = CGroupV1.Memory;
            this.cpuCgroup = CGroupV1.CPU;
            this.freezerCgroup = CGroupV1.Freezer;
            resourceLimitCgroups = Arrays.asList(
                    memoryCgroup, cpuCgroup);
        } else {
            this.unifiedCgroup = CGroupV2.Unified;
            this.memoryCgroup = CGroupV2.Memory;
            this.cpuCgroup = CGroupV2.CPU;
            this.freezerCgroup = CGroupV2.Freezer;
            resourceLimitCgroups = Arrays.asList(unifiedCgroup);
        }
    }




    @Override
    public void removeResourceController(GreengrassService component) {
        usedCgroups.forEach(cg -> {
            try {
                // Assumes processes belonging to cgroups would already be terminated/killed.
                Files.deleteIfExists(cg.getSubsystemComponentPath(component.getServiceName()));
            } catch (IOException e) {
                logger.atError().setCause(e).kv(COMPONENT_NAME, component.getServiceName())
                        .log("Failed to remove the resource controller");
            }
        });
    }

    @Override
    public void updateResourceLimits(GreengrassService component, Map<String, Object> resourceLimit) {
        try {
            updateMemoryResourceLimits(component, resourceLimit);

            if (!Files.exists(cpuCgroup.getSubsystemComponentPath(component.getServiceName()))) {
                initializeCgroup(component, cpuCgroup);
            }
            if (resourceLimit.containsKey(CPUS_KEY)) {
                double cpu = Coerce.toDouble(resourceLimit.get(CPUS_KEY));
                if (cpu > 0) {
                    cpuCgroup.handleCpuLimits(component, cpu);

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

    protected void updateMemoryResourceLimits(GreengrassService component,
                                              Map<String, Object> resourceLimit) throws IOException {
        if (!Files.exists(memoryCgroup.getSubsystemComponentPath(component.getServiceName()))) {
            initializeCgroup(component, memoryCgroup);
        }
        if (resourceLimit.containsKey(MEMORY_KEY)) {
            long memoryLimitInKB = Coerce.toLong(resourceLimit.get(MEMORY_KEY));
            if (memoryLimitInKB > 0) {
                String memoryLimit = Long.toString(memoryLimitInKB * ONE_KB);
                Files.write(memoryCgroup.getComponentMemoryLimitPath(component.getServiceName()),
                        memoryLimit.getBytes(StandardCharsets.UTF_8));
            } else {
                logger.atWarn().kv(COMPONENT_NAME, component.getServiceName()).kv(MEMORY_KEY, memoryLimitInKB)
                        .log("The provided memory limit is invalid");
            }
        }
    }

    @Override
    public void resetResourceLimits(GreengrassService component) {
        for (CGroupSubSystemPaths cg : resourceLimitCgroups) {
            try {
                if (Files.exists(cg.getSubsystemComponentPath(component.getServiceName()))) {
                    Files.delete(cg.getSubsystemComponentPath(component.getServiceName()));
                    Files.createDirectory(cg.getSubsystemComponentPath(component.getServiceName()));
                }
            } catch (IOException e) {
                logger.atError().setCause(e).kv(COMPONENT_NAME, component.getServiceName())
                        .log("Failed to remove the resource controller");
            }
        }
    }

    @Override
    public void addComponentProcess(GreengrassService component, Process process) {
        resourceLimitCgroups.forEach(cg -> {
            try {
                addComponentProcessToCgroup(component.getServiceName(), process, cg);

                // Child processes of a process in a cgroup are auto-added to the same cgroup by linux kernel. But in
                // case of a race condition in starting a child process and us adding pids to cgroup, neither us nor
                // the linux kernel will add it to the cgroup. To account for this, re-list all pids for the component
                // after 1 second and add to cgroup again so that all component processes are resource controlled.
                component.getContext().get(ScheduledExecutorService.class).schedule(() -> {
                    try {
                        addComponentProcessToCgroup(component.getServiceName(), process, cg);
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
        prePauseComponentProcesses(component, processes);
        freezerCgroup.pauseComponentProcessesCore(component);
    }


    @Override
    public void resumeComponentProcesses(GreengrassService component) throws IOException {
        freezerCgroup.resumeComponentProcesses(component);
    }

    protected void addComponentProcessToCgroup(String component, Process process, CGroupSubSystemPaths cg)
            throws IOException {

        if (!Files.exists(cg.getSubsystemComponentPath(component))) {
            logger.atDebug().kv(COMPONENT_NAME, component).kv("resource-controller", cg.toString())
                    .log("Resource controller is not enabled");
            return;
        }

        if (process != null) {
            try {
                Set<Integer> childProcesses = platform.getChildPids(process);
                childProcesses.add(PidUtil.getPid(process));
                Set<Integer> pidsInCgroup = pidsInComponentCgroup(cg, component);
                if (!Utils.isEmpty(childProcesses) && Objects.nonNull(pidsInCgroup)
                        && !childProcesses.equals(pidsInCgroup)) {

                    // Writing pid to cgroup.procs file should auto add the pid to tasks file
                    // Once a process is added to a cgroup, its forked child processes inherit its (parent's) settings
                    for (Integer pid : childProcesses) {
                        if (pid == null) {
                            logger.atError().log("The process doesn't exist and is skipped");
                            continue;
                        }

                        Files.write(cg.getCgroupProcsPath(component),
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

    protected void initializeCgroup(GreengrassService component, CGroupSubSystemPaths cgroup) throws IOException {
        cgroup.initializeCgroup(component, platform);
        usedCgroups.add(cgroup);
    }

    private Set<Integer> pidsInComponentCgroup(CGroupSubSystemPaths cgroup, String component) throws IOException {
        return Files.readAllLines(cgroup.getCgroupProcsPath(component))
                .stream().map(Integer::parseInt).collect(Collectors.toSet());
    }


    protected void prePauseComponentProcesses(GreengrassService component, List<Process> processes) throws IOException {
        initializeCgroup(component, freezerCgroup);

        for (Process process : processes) {
            addComponentProcessToCgroup(component.getServiceName(), process, freezerCgroup);
        }
    }

    public enum CgroupFreezerState {
        THAWED, FREEZING, FROZEN
    }
}
