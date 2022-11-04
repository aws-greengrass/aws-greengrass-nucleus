/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix.linux;

import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Utils;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;


@ExtendWith({MockitoExtension.class, GGExtension.class})
@DisabledOnOs(OS.WINDOWS)
class LinuxSystemResourceControllerV2Test {
    @Mock
    GreengrassService component;
    @Mock
    LinuxPlatform platform;
    @Spy
    CGroupSubSystemPaths cpuCgroup = CGroupV2.CPU;
    @Spy
    CGroupSubSystemPaths memoryCgroup = CGroupV2.Memory;
    @InjectMocks
    @Spy
    LinuxSystemResourceController linuxSystemResourceControllerV2 = new LinuxSystemResourceController(
            platform, false);

    private static final String FILE_PATH = "/cgroupv2test";
    private static final String CGROUP_MEMORY_LIMIT_FILE_NAME = "memory.txt";
    private static final String CGROUP_CPU_LIMIT_FILE_NAME = "cpu.txt";
    private static final long MEMORY_IN_KB = 2048000;
    private static final double CPU_TIME = 0.5;
    private static final String COMPONENT_NAME = "testComponentName";

    @Test
    void GIVEN_cgroupv2_WHEN_memory_limit_updated_THEN_memory_limit_file_updated() throws IOException {
        Map<String, Object> resourceLimit = new HashMap<>();
        resourceLimit.put("memory", String.valueOf(MEMORY_IN_KB));
        doReturn("testComponentName").when(component).getServiceName();

        Path path = Paths.get(FILE_PATH + "/" + CGROUP_MEMORY_LIMIT_FILE_NAME);

        Path componentNameFolderPath = Paths.get(FILE_PATH);
        Utils.createPaths(componentNameFolderPath);
        File file = new File(FILE_PATH + "/" + CGROUP_MEMORY_LIMIT_FILE_NAME);
        if (!Files.exists(path)) {
            file.createNewFile();
        }

        when(memoryCgroup.getComponentMemoryLimitPath(COMPONENT_NAME)).thenReturn(path);
        lenient().when(memoryCgroup.getSubsystemComponentPath(COMPONENT_NAME)).thenReturn(componentNameFolderPath);
        when(cpuCgroup.getSubsystemComponentPath(COMPONENT_NAME)).thenReturn(path);

        linuxSystemResourceControllerV2.updateResourceLimits(component, resourceLimit);

        List<String> mounts = Files.readAllLines(path);
        assertEquals(String.valueOf(MEMORY_IN_KB * 1024), mounts.get(0));
        FileUtils.deleteDirectory(componentNameFolderPath.toFile());
    }

    @Test
    void GIVEN_cgroupv2_WHEN_cpu_limit_updated_THEN_cpu_limit_file_updated() throws IOException {
        Map<String, Object> resourceLimit = new HashMap<>();
        resourceLimit.put("cpus", String.valueOf(CPU_TIME));
        doReturn("testComponentName").when(component).getServiceName();

        Path path = Paths.get(FILE_PATH + "/" + CGROUP_CPU_LIMIT_FILE_NAME);

        Path componentNameFolderPath = Paths.get(FILE_PATH);
        Utils.createPaths(componentNameFolderPath);
        File file = new File(FILE_PATH + "/" + CGROUP_CPU_LIMIT_FILE_NAME);
        if (!Files.exists(path)) {
            file.createNewFile();
        }

        Files.write(path, "max 100000".getBytes(StandardCharsets.UTF_8));

        CGroupSubSystemPaths cpuSystemV2 = spy(CGroupV2.CPU);
        lenient().when(cpuSystemV2.getComponentCpuMaxPath(COMPONENT_NAME)).thenReturn(path);

        cpuSystemV2.handleCpuLimits(component, 0.5);

        List<String> mounts = Files.readAllLines(path);
        assertEquals((int) (CPU_TIME * 100000) + " 100000", mounts.get(0));

        Files.deleteIfExists(path);
        Files.deleteIfExists(componentNameFolderPath);
    }
}