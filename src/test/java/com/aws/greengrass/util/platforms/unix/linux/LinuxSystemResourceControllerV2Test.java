/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix.linux;

import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.doReturn;


@ExtendWith({MockitoExtension.class, GGExtension.class})
@DisabledOnOs(OS.WINDOWS)
class LinuxSystemResourceControllerV2Test {
    @Mock
    GreengrassService component;
    @Mock
    LinuxPlatform platform;
    @InjectMocks
    @Spy
    LinuxSystemResourceController linuxSystemResourceControllerV2 = new LinuxSystemResourceController(
            platform, false);
    private static final long MEMORY_IN_KB = 2048000;
    private static final double CPU_TIME = 0.2;

    @Test
    void GIVEN_cgroupv2_WHEN_memory_limit_updated_THEN_memory_limit_file_updated_V2() throws IOException {
        assumeTrue(ifCgroupV2(), "skip this test case if v1 is enabled.");
        Map<String, Object> resourceLimit = new HashMap<>();
        resourceLimit.put("memory", String.valueOf(MEMORY_IN_KB));
        doReturn("testComponentName").when(component).getServiceName();

        linuxSystemResourceControllerV2.updateResourceLimits(component, resourceLimit);
        String memoryValue = new String(Files.readAllBytes(
                CGroupV2.Memory.getComponentMemoryLimitPath("testComponentName"))
                , StandardCharsets.UTF_8).trim();
        assertEquals(String.valueOf(MEMORY_IN_KB * 1024), memoryValue);
    }

    @Test
    void GIVEN_cgroupv2_WHEN_cpu_limit_updated_THEN_cpu_limit_file_updated_V2() throws IOException {
        assumeTrue(ifCgroupV2(), "skip this test case if v1 is enabled.");
        Map<String, Object> resourceLimit = new HashMap<>();
        resourceLimit.put("cpus", String.valueOf(CPU_TIME));
        doReturn("testComponentName").when(component).getServiceName();

        linuxSystemResourceControllerV2.updateResourceLimits(component, resourceLimit);
        String rawCpuValue = new String(Files.readAllBytes(
                CGroupV2.CPU.getComponentCpuMaxPath("testComponentName"))
                , StandardCharsets.UTF_8).trim();
        assertEquals(String.valueOf((int) (CPU_TIME * 100000)), rawCpuValue.split(" ")[0]);
    }

    @Test
    void GIVEN_cgroupv2_WHEN_pause_resume_THEN_freeze_state_1_0_V2() throws IOException {
        assumeTrue(ifCgroupV2(), "skip this test case if v1 is enabled.");
        LinuxSystemResourceController controller = new LinuxSystemResourceController(platform, false);
        doReturn("testComponentName").when(component).getServiceName();
        List<Process> processes = new ArrayList<>();
        controller.pauseComponentProcesses(component, processes);
        String freezerStateValue = new String(Files.readAllBytes(
                CGroupV2.Freezer.getCgroupFreezerStateFilePath("testComponentName"))
                , StandardCharsets.UTF_8).trim();
        assertEquals(freezerStateValue, "1");

        controller.resumeComponentProcesses(component);
        freezerStateValue = new String(Files.readAllBytes(
                CGroupV2.Freezer.getCgroupFreezerStateFilePath("testComponentName"))
                , StandardCharsets.UTF_8).trim();
        assertEquals(freezerStateValue, "0");
    }

    @Test
    void GIVEN_cgroupv1_WHEN_memory_limit_updated_THEN_memory_limit_file_updated_V1() throws IOException {
        assumeTrue(!ifCgroupV2(), "skip this test case if v2 is enabled.");
        Map<String, Object> resourceLimit = new HashMap<>();
        resourceLimit.put("memory", String.valueOf(MEMORY_IN_KB));
        doReturn("testComponentName").when(component).getServiceName();

        new LinuxSystemResourceController(platform, true).updateResourceLimits(component, resourceLimit);
        String memoryValue = new String(Files.readAllBytes(
                CGroupV1.Memory.getComponentMemoryLimitPath("testComponentName"))
                , StandardCharsets.UTF_8).trim();
        assertEquals(String.valueOf(MEMORY_IN_KB * 1024), memoryValue);
    }

    @Test
    void GIVEN_cgroupv1_WHEN_cpu_limit_updated_THEN_cpu_limit_file_updated_V1() throws IOException {
        assumeTrue(!ifCgroupV2(), "skip this test case if v2 is enabled.");
        Map<String, Object> resourceLimit = new HashMap<>();
        resourceLimit.put("cpus", String.valueOf(CPU_TIME));
        doReturn("testComponentName").when(component).getServiceName();

        new LinuxSystemResourceController(platform, true).updateResourceLimits(component, resourceLimit);
        String rawCpuValue = new String(Files.readAllBytes(
                CGroupV1.CPU.getComponentCpuQuotaPath("testComponentName"))
                , StandardCharsets.UTF_8).trim();
        assertEquals(String.valueOf((int) (CPU_TIME * 100000)), rawCpuValue);
    }

    @Test
    void GIVEN_cgroupv1_WHEN_pause_resume_THEN_freeze_state_1_0_V1() throws IOException {
        assumeTrue(!ifCgroupV2(), "skip this test case if v2 is enabled.");
        LinuxSystemResourceController controller = new LinuxSystemResourceController(platform, true);
        doReturn("testComponentName").when(component).getServiceName();
        List<Process> processes = new ArrayList<>();
        controller.pauseComponentProcesses(component, processes);
        String freezerStateValue = new String(Files.readAllBytes(
                CGroupV1.Freezer.getCgroupFreezerStateFilePath("testComponentName"))
                , StandardCharsets.UTF_8).trim();

        assertThat(freezerStateValue,
                anyOf(is(LinuxSystemResourceController.CgroupFreezerState.FROZEN.toString()),
                        is(LinuxSystemResourceController.CgroupFreezerState.FREEZING.toString())));

        controller.resumeComponentProcesses(component);
        freezerStateValue = new String(Files.readAllBytes(
                CGroupV1.Freezer.getCgroupFreezerStateFilePath("testComponentName"))
                , StandardCharsets.UTF_8).trim();
        assertEquals(freezerStateValue, LinuxSystemResourceController.CgroupFreezerState.THAWED.toString());
    }

    private boolean ifCgroupV2() {
        return Files.exists(Paths.get("/sys/fs/cgroup/cgroup.controllers"));
    }
}