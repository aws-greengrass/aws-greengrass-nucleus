/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix.linux;

import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith({GGExtension.class, MockitoExtension.class})
@EnabledOnOs({OS.LINUX})
class LinuxSystemResourceControllerTest {

    @Mock
    private LinuxPlatform mockPlatform;
    
    @Mock
    private GreengrassService mockService;

    @InjectMocks
    @Spy
    private LinuxSystemResourceController controller;
    
    // Test constants
    private static final String CGROUP_ROOT = "/sys/fs/cgroup";
    private static final String GG_NAMESPACE = "greengrass";
    private static final String MEMORY = "memory";
    private static final String CPU = "cpu,cpuacct";
    private static final String FREEZER = "freezer";
    private static final String V1_MEMORY_MAX = "memory.max";
    private static final String V1_CPU_QUOTA = "cpu.cfs_quota_us";
    private static final String V1_FREEZER = "freezer.state";
    private static final String V2_CPU_MAX = "cpu.max";
    private static final String V2_MEMORY_MAX = "memory.limit_in_bytes";
    private static final String V2_FREEZER = "cgroup.freeze";
    private static final String CONTROLLER_PATH = "/sys/fs/cgroup/cgroup.controllers";
    private static final String TEST_COMPONENT = "TestComponent";
    
    @BeforeEach
    void setUp() {
        when(mockService.getServiceName()).thenReturn(TEST_COMPONENT);
    }

    @Test
    void GIVEN_cgroupV2_system_WHEN_updateResourceLimits_with_memory_THEN_writes_correct_memory_limit() throws IOException {
        try (MockedStatic<Files> filesMock = mockStatic(Files.class)) {
            // Mock cgroup v2 detection - make controllers file exist
            filesMock.when(() -> Files.exists(Paths.get(CONTROLLER_PATH)))
                    .thenReturn(true);

            controller = new LinuxSystemResourceController(mockPlatform);

            Map<String, Object> resourceLimits = new HashMap<>();
            resourceLimits.put("memory", 2048L); // 2048 KB

            controller.updateResourceLimits(mockService, resourceLimits);

            // Capture the arguments
            ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
            ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);

            filesMock.verify(() -> Files.write(pathCaptor.capture(), contentCaptor.capture(), any()), atLeastOnce());

            // Find the memory limit write among all the calls
            Path expectedPath = CgroupV2.Memory.getComponentMemoryLimitPath(TEST_COMPONENT);
            assertEquals(Paths.get(CGROUP_ROOT).resolve(GG_NAMESPACE).resolve(TEST_COMPONENT).resolve(V1_MEMORY_MAX), expectedPath);

            List<Path> allPaths = pathCaptor.getAllValues();
            List<byte[]> allContents = contentCaptor.getAllValues();
            
            // Find the call that wrote to the memory limit file
            int memoryLimitIndex = -1;
            for (int i = 0; i < allPaths.size(); i++) {
                if (expectedPath.equals(allPaths.get(i))) {
                    memoryLimitIndex = i;
                    break;
                }
            }
            assertTrue(memoryLimitIndex >= 0, "Should have written to memory limit file");
            
            assertEquals(String.valueOf(2048 * 1024), new String(allContents.get(memoryLimitIndex)));
        }
    }

    @Test
    void GIVEN_cgroupV1_system_WHEN_updateResourceLimits_with_memory_THEN_writes_correct_memory_limit() throws IOException {
        try (MockedStatic<Files> filesMock = mockStatic(Files.class)) {
            // Mock cgroup v1 detection - make controllers file non-exist
            filesMock.when(() -> Files.exists(Paths.get(CONTROLLER_PATH)))
                    .thenReturn(false);

            controller = new LinuxSystemResourceController(mockPlatform);

            Map<String, Object> resourceLimits = new HashMap<>();
            resourceLimits.put("memory", 2048L); // 2048 KB

            controller.updateResourceLimits(mockService, resourceLimits);

            // Capture the arguments
            ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
            ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);

            filesMock.verify(() -> Files.write(pathCaptor.capture(), contentCaptor.capture(), any()));

            // Verify both path and content
            Path expectedPath = CgroupV1.Memory.getComponentMemoryLimitPath("TestComponent");
            assertEquals(Paths.get(CGROUP_ROOT).resolve(MEMORY).resolve(GG_NAMESPACE).resolve(TEST_COMPONENT).resolve(V2_MEMORY_MAX), expectedPath);

            assertEquals(expectedPath, pathCaptor.getValue());
            assertEquals(String.valueOf(2048 * 1024), new String(contentCaptor.getValue()));
        }
    }

    @Test
    void GIVEN_cgroupV2_system_WHEN_updateResourceLimits_with_cpu_THEN_writes_correct_cpu_limit() throws IOException {
        try (MockedStatic<Files> filesMock = mockStatic(Files.class)) {
            filesMock.when(() -> Files.exists(Paths.get(CONTROLLER_PATH)))
                    .thenReturn(true);
            // Mock reading existing CPU limit file (default format: "max 100000")
            filesMock.when(() -> Files.readAllBytes(any(Path.class)))
                    .thenReturn("max 100000".getBytes(StandardCharsets.UTF_8));

            controller = new LinuxSystemResourceController(mockPlatform);

            double cpuRatio = 1.5;
            Map<String, Object> resourceLimits = new HashMap<>();
            resourceLimits.put("cpus", cpuRatio);

            controller.updateResourceLimits(mockService, resourceLimits);

            // Capture the arguments
            ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
            ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
            
            filesMock.verify(() -> Files.write(pathCaptor.capture(), contentCaptor.capture(), any()), atLeastOnce());
            
            // Find the CPU limit write among all the calls
            Path expectedPath = CgroupV2.CPU.getComponentCpuLimitPath("TestComponent");
            assertEquals(Paths.get(CGROUP_ROOT).resolve(GG_NAMESPACE).resolve(TEST_COMPONENT).resolve(V2_CPU_MAX), expectedPath);

            List<Path> allPaths = pathCaptor.getAllValues();
            List<byte[]> allContents = contentCaptor.getAllValues();
            
            // Find the call that wrote to the CPU limit file
            int cpuLimitIndex = -1;
            for (int i = 0; i < allPaths.size(); i++) {
                if (expectedPath.equals(allPaths.get(i))) {
                    cpuLimitIndex = i;
                    break;
                }
            }
            assertTrue(cpuLimitIndex >= 0, "Should have written to CPU limit file");

            // cgroupV2 cpu.max format: "quota period" - verify the quota part
            String writtenContent = new String(allContents.get(cpuLimitIndex));
            int expectedQuota = (int) (100_000 * cpuRatio);
            String actualQuota = writtenContent.split(" ")[0];
            assertEquals(String.valueOf(expectedQuota), actualQuota);
        }
    }

    @Test
    void GIVEN_cgroupV1_system_WHEN_updateResourceLimits_with_cpu_THEN_writes_correct_cpu_limit() throws IOException {
        try (MockedStatic<Files> filesMock = mockStatic(Files.class)) {
            filesMock.when(() -> Files.exists(Paths.get(CONTROLLER_PATH)))
                    .thenReturn(false);

            // mock reading CPU period file (CgroupV1 reads this first)
            filesMock.when(() -> Files.readAllBytes(any(Path.class))).thenReturn("100000".getBytes());

            controller = new LinuxSystemResourceController(mockPlatform);

            double cpuRatio = 1.5;
            Map<String, Object> resourceLimits = new HashMap<>();
            resourceLimits.put("cpus", cpuRatio);

            controller.updateResourceLimits(mockService, resourceLimits);

            // capture the arguments for Files.write (quota file)
            ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
            ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
            
            filesMock.verify(() -> Files.write(pathCaptor.capture(), contentCaptor.capture()));
            
            // verify path is the CPU quota file
            Path expectedPath = CgroupV1.CPU.getSubsystemComponentPath(TEST_COMPONENT).resolve(V1_CPU_QUOTA);
            assertEquals(Paths.get(CGROUP_ROOT).resolve(CPU).resolve(GG_NAMESPACE).resolve(TEST_COMPONENT).resolve(V1_CPU_QUOTA), expectedPath);
            assertEquals(expectedPath, pathCaptor.getValue());
            
            // cgroupV1 writes calculated quota based on period * ratio
            String writtenContent = new String(contentCaptor.getValue());
            int expectedQuota = (int) (100_000 * cpuRatio); // 150000
            assertEquals(String.valueOf(expectedQuota), writtenContent.trim());
        }
    }

    @Test
    void GIVEN_cgroupV2_system_WHEN_pause_and_resume_THEN_writes_correct_freeze_states() throws IOException {
        try (MockedStatic<Files> filesMock = mockStatic(Files.class)) {
            filesMock.when(() -> Files.exists(Paths.get(CONTROLLER_PATH)))
                    .thenReturn(true);
            filesMock.when(() -> Files.exists(argThat(path -> !path.equals(Paths.get(CONTROLLER_PATH)))))
                    .thenReturn(false);
            filesMock.when(() -> Files.createDirectory(any(Path.class))).thenReturn(null);
            
            controller = new LinuxSystemResourceController(mockPlatform);
            
            // test pause (freeze) - mock to return "0" (not frozen)
            filesMock.when(() -> Files.readAllLines(any(Path.class))).thenReturn(Arrays.asList("0"));
            List<Process> processes = new ArrayList<>();
            controller.pauseComponentProcesses(mockService, processes);
            
            // test resume (thaw) - reset mock to return "1" (frozen)
            filesMock.when(() -> Files.readAllLines(any(Path.class))).thenReturn(Arrays.asList("1"));
            controller.resumeComponentProcesses(mockService);

            // capture both operations
            ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
            ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
            
            filesMock.verify(() -> Files.write(pathCaptor.capture(), contentCaptor.capture(), any()), times(4));
            
            // verify freeze and thaw operations (calls 3 and 4 should be freeze/thaw, calls 1 and 2 are for delegation)
            Path expectedPath = CgroupV2.Freezer.getCgroupFreezerStateFilePath("TestComponent");
            assertEquals(Paths.get(CGROUP_ROOT).resolve(GG_NAMESPACE).resolve(TEST_COMPONENT).resolve(V2_FREEZER), expectedPath);
            assertEquals(expectedPath, pathCaptor.getAllValues().get(2)); // freeze (3rd call)
            assertEquals(expectedPath, pathCaptor.getAllValues().get(3)); // thaw (4th call)
            
            // Verify freeze writes "1" and thaw writes "0"
            assertEquals("1", new String(contentCaptor.getAllValues().get(2))); // freeze
            assertEquals("0", new String(contentCaptor.getAllValues().get(3))); // thaw
        }
    }

    @Test
    void GIVEN_cgroupV1_system_WHEN_pause_and_resume_THEN_writes_correct_freeze_states() throws IOException {
        try (MockedStatic<Files> filesMock = mockStatic(Files.class)) {
            filesMock.when(() -> Files.exists(Paths.get(CONTROLLER_PATH)))
                    .thenReturn(false);
            filesMock.when(() -> Files.exists(argThat(path -> !path.equals(Paths.get(CONTROLLER_PATH)))))
                    .thenReturn(false);
            filesMock.when(() -> Files.createDirectory(any(Path.class))).thenReturn(null);
            
            controller = new LinuxSystemResourceController(mockPlatform);

            // test pause (freeze) - mock to return "0" (not frozen)
            filesMock.when(() -> Files.readAllLines(any(Path.class))).thenReturn(Arrays.asList("THAWED"));
            List<Process> processes = new ArrayList<>();
            controller.pauseComponentProcesses(mockService, processes);

            // test resume (thaw) - reset mock to return "1" (frozen)
            filesMock.when(() -> Files.readAllLines(any(Path.class))).thenReturn(Arrays.asList("FROZEN"));
            controller.resumeComponentProcesses(mockService);

            // capture both operations
            ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
            ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
            
            filesMock.verify(() -> Files.write(pathCaptor.capture(), contentCaptor.capture(), any()), times(2));
            
            // verify both operations wrote to the same freeze file
            Path expectedPath = CgroupV1.Freezer.getCgroupFreezerStateFilePath("TestComponent");
            assertEquals(Paths.get(CGROUP_ROOT).resolve(FREEZER).resolve(GG_NAMESPACE).resolve(TEST_COMPONENT).resolve(V1_FREEZER), expectedPath);
            assertEquals(expectedPath, pathCaptor.getAllValues().get(0)); // freeze
            assertEquals(expectedPath, pathCaptor.getAllValues().get(1)); // thaw
            
            // verify freeze writes "FROZEN" and thaw writes "THAWED" (CgroupV1 format)
            assertEquals("FROZEN", new String(contentCaptor.getAllValues().get(0))); // freeze
            assertEquals("THAWED", new String(contentCaptor.getAllValues().get(1))); // thaw
        }
    }
}
