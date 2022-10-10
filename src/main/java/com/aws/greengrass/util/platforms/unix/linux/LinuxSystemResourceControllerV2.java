/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix.linux;

import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * https://www.kernel.org/doc/Documentation/cgroup-v2.txt
 */
public class LinuxSystemResourceControllerV2 extends LinuxSystemResourceController {
    private static final Logger logger = LogManager.getLogger(LinuxSystemResourceControllerV2.class);
    private static final String CGROUP_SUBTREE_CONTROL_CONTENT = "+cpuset +cpu +io +memory +pids";

    /**
     * Linux system resource controller V2 constrcutors.
     *
     * @param platform linux platform
     */
    public LinuxSystemResourceControllerV2(LinuxPlatform platform) {
        super();
        this.platform = platform;
        this.unifiedCgroup = new Cgroup(CgroupSubSystemV2.Unified);
        this.memoryCgroup = new Cgroup(CgroupSubSystemV2.Memory);
        this.cpuCgroup = new Cgroup(CgroupSubSystemV2.CPU);
        this.freezerCgroup = new Cgroup(CgroupSubSystemV2.Freezer);
        resourceLimitCgroups = Arrays.asList(unifiedCgroup);
    }


    @Override
    public void updateResourceLimits(GreengrassService component, Map<String, Object> resourceLimit) {
        try {
            super.updateMemoryResourceLimits(component, resourceLimit);

            if (resourceLimit.containsKey(CPUS_KEY)) {
                double cpu = Coerce.toDouble(resourceLimit.get(CPUS_KEY));
                if (cpu > 0) {
                    handleCpuLimits(component, cpu);
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
    public void pauseComponentProcesses(GreengrassService component, List<Process> processes) throws IOException {
        prePauseComponentProcesses(component, processes);

        Files.write(freezerCgroupStateFile(component.getServiceName()),
                String.valueOf(CgroupV2FreezerState.FROZEN.getIndex()).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Override
    public void resumeComponentProcesses(GreengrassService component) throws IOException {
        Files.write(freezerCgroupStateFile(component.getServiceName()),
                String.valueOf(CgroupV2FreezerState.THAWED.getIndex()).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Override
    protected void initializeCgroup(GreengrassService component, Cgroup cgroup) throws IOException {
        Set<String> mounts = getMountedPaths();

        if (!mounts.contains(cgroup.getRootPath().toString())) {
            platform.runCmd(cgroup.rootMountCmd(), o -> {
            }, "Failed to mount cgroup root");
            Files.createDirectory(cgroup.getSubsystemRootPath());
        }

        if (!Files.exists(cgroup.getSubsystemGGPath())) {
            Files.createDirectory(cgroup.getSubsystemGGPath());
        }
        if (!Files.exists(cgroup.getSubsystemComponentPath(component.getServiceName()))) {
            Files.createDirectory(cgroup.getSubsystemComponentPath(component.getServiceName()));
        }

        //Enable controllers for root group
        Files.write(cgroup.getRootSubTreeControlPath(),
                CGROUP_SUBTREE_CONTROL_CONTENT.getBytes(StandardCharsets.UTF_8));
        //Enable controllers for gg group
        Files.write(cgroup.getGGSubTreeControlPath(),
                CGROUP_SUBTREE_CONTROL_CONTENT.getBytes(StandardCharsets.UTF_8));

        usedCgroups.add(cgroup);
    }

    private void handleCpuLimits(GreengrassService component, double cpu) throws IOException {
        byte[] content = Files.readAllBytes(
                cpuCgroup.getComponentCpuMaxPath(component.getServiceName()));
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
        Files.write(cpuCgroup.getComponentCpuMaxPath(component.getServiceName()),
                latestCpuMaxContent.getBytes(StandardCharsets.UTF_8));
    }

}
