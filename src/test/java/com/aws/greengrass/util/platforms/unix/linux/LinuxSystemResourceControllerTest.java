/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix.linux;

import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zeroturnaround.process.PidProcess;
import org.zeroturnaround.process.PidUtil;
import org.zeroturnaround.process.Processes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith({GGExtension.class, MockitoExtension.class})
@EnabledOnOs({OS.LINUX})
class LinuxSystemResourceControllerTest {

    @Mock
    private LinuxPlatform mockPlatform;
    
    @Mock
    private GreengrassService mockService;

    @Mock
    private Context mockContext;

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

    @Test
    void GIVEN_cgroupV1_and_short_lived_pid_WHEN_addComponentProcess_THEN_remaining_pids_added_and_readd_scheduled()
            throws Exception {
        int deadPid = 100;      // e.g. sh/sudo wrapper that exits before its pid is written
        int livePid1 = 6808;
        int livePid2 = 6823;
        int rootPid = 6800;
        Path memoryProcs = CgroupV1.Memory.getCgroupProcsPath(TEST_COMPONENT);
        Path cpuProcs = CgroupV1.CPU.getCgroupProcsPath(TEST_COMPONENT);
        Map<Path, List<Integer>> written = new HashMap<>();
        Process process = mock(Process.class);
        ScheduledExecutorService ses = mock(ScheduledExecutorService.class);
        when(mockService.getContext()).thenReturn(mockContext);
        when(mockContext.get(ScheduledExecutorService.class)).thenReturn(ses);
        // Dead pid is iterated first, as in the arbitrary HashSet order seen on device
        when(mockPlatform.getChildPids(process))
                .thenAnswer(i -> new LinkedHashSet<>(Arrays.asList(deadPid, livePid1, livePid2)));

        try (MockedStatic<Files> filesMock = mockStatic(Files.class);
             MockedStatic<PidUtil> pidMock = mockStatic(PidUtil.class);
             MockedStatic<Processes> processesMock = mockStatic(Processes.class)) {
            filesMock.when(() -> Files.exists(Paths.get(CONTROLLER_PATH))).thenReturn(false);
            filesMock.when(() -> Files.exists(argThat(path -> !path.equals(Paths.get(CONTROLLER_PATH)))))
                    .thenReturn(true);
            filesMock.when(() -> Files.readAllLines(any(Path.class))).thenReturn(Collections.emptyList());
            // Dead pid's write throws ESRCH on the memory pass (localized message, e.g. de_DE); the remaining live
            // pids must still be added
            filesMock.when(() -> Files.write(any(Path.class), any(byte[].class))).thenAnswer(i -> {
                Path path = i.getArgument(0);
                int pid = pidFromBytes(i.getArgument(1));
                if (pid == deadPid && path.equals(memoryProcs)) {
                    throw new IOException("Kein passender Prozess gefunden");
                }
                written.computeIfAbsent(path, p -> new ArrayList<>()).add(pid);
                return path;
            });
            pidMock.when(() -> PidUtil.getPid(process)).thenReturn(rootPid);
            mockPidAlive(processesMock, deadPid, false);

            controller = new LinuxSystemResourceController(mockPlatform);
            controller.addComponentProcess(mockService, process);

            assertTrue(written.getOrDefault(cpuProcs, Collections.emptyList())
                    .containsAll(Arrays.asList(livePid1, livePid2, rootPid)), "cpu cgroup pids: " + written.get(cpuProcs));
            assertTrue(written.getOrDefault(memoryProcs, Collections.emptyList())
                    .containsAll(Arrays.asList(livePid1, livePid2, rootPid)),
                    "memory cgroup pids: " + written.get(memoryProcs));
            // 1s re-add must be scheduled for both memory and cpu cgroups
            ArgumentCaptor<Runnable> readd = ArgumentCaptor.forClass(Runnable.class);
            verify(ses, times(2)).schedule(readd.capture(), eq(1L), eq(TimeUnit.SECONDS));

            // Re-add picks up a child forked after the first enumeration
            int lateChild = 6830;
            when(mockPlatform.getChildPids(process))
                    .thenAnswer(i -> new LinkedHashSet<>(Arrays.asList(livePid1, livePid2, lateChild)));
            readd.getAllValues().forEach(Runnable::run);

            assertTrue(written.get(memoryProcs).contains(lateChild), "memory cgroup pids: " + written.get(memoryProcs));
            assertTrue(written.get(cpuProcs).contains(lateChild), "cpu cgroup pids: " + written.get(cpuProcs));
        }
    }

    @Test
    void GIVEN_cgroupV2_and_short_lived_pid_WHEN_addComponentProcess_THEN_remaining_pids_added_and_readd_scheduled()
            throws Exception {
        int deadPid = 100;
        int livePid1 = 6808;
        int livePid2 = 6823;
        int rootPid = 6800;
        Path generalProcs = CgroupV2.General.getCgroupProcsPath(TEST_COMPONENT);
        Map<Path, List<Integer>> written = new HashMap<>();
        Process process = mock(Process.class);
        ScheduledExecutorService ses = mock(ScheduledExecutorService.class);
        when(mockService.getContext()).thenReturn(mockContext);
        when(mockContext.get(ScheduledExecutorService.class)).thenReturn(ses);
        when(mockPlatform.getChildPids(process))
                .thenAnswer(i -> new LinkedHashSet<>(Arrays.asList(deadPid, livePid1, livePid2)));

        try (MockedStatic<Files> filesMock = mockStatic(Files.class);
             MockedStatic<PidUtil> pidMock = mockStatic(PidUtil.class);
             MockedStatic<Processes> processesMock = mockStatic(Processes.class)) {
            filesMock.when(() -> Files.exists(any(Path.class))).thenReturn(true);
            filesMock.when(() -> Files.readAllLines(any(Path.class))).thenReturn(Collections.emptyList());
            // On v2 the single General cgroup carries both limits, so a dead pid must not keep live pids out of it
            filesMock.when(() -> Files.write(any(Path.class), any(byte[].class))).thenAnswer(i -> {
                int pid = pidFromBytes(i.getArgument(1));
                if (pid == deadPid) {
                    throw new IOException("No such process");
                }
                Path path = i.getArgument(0);
                written.computeIfAbsent(path, p -> new ArrayList<>()).add(pid);
                return path;
            });
            pidMock.when(() -> PidUtil.getPid(process)).thenReturn(rootPid);
            mockPidAlive(processesMock, deadPid, false);

            controller = new LinuxSystemResourceController(mockPlatform);
            controller.addComponentProcess(mockService, process);

            assertTrue(written.getOrDefault(generalProcs, Collections.emptyList())
                    .containsAll(Arrays.asList(livePid1, livePid2, rootPid)),
                    "general cgroup pids: " + written.get(generalProcs));
            verify(ses, times(1)).schedule(any(Runnable.class), eq(1L), eq(TimeUnit.SECONDS));
        }
    }

    @Test
    void GIVEN_cgroupV1_and_write_error_for_live_pid_WHEN_addComponentProcess_THEN_remaining_pids_still_added(
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, IOException.class);
        int failingPid = 100;
        int livePid1 = 6808;
        int livePid2 = 6823;
        int rootPid = 6800;
        Path memoryProcs = CgroupV1.Memory.getCgroupProcsPath(TEST_COMPONENT);
        Path cpuProcs = CgroupV1.CPU.getCgroupProcsPath(TEST_COMPONENT);
        Map<Path, List<Integer>> written = new HashMap<>();
        Process process = mock(Process.class);
        ScheduledExecutorService ses = mock(ScheduledExecutorService.class);
        when(mockService.getContext()).thenReturn(mockContext);
        when(mockContext.get(ScheduledExecutorService.class)).thenReturn(ses);
        when(mockPlatform.getChildPids(process))
                .thenAnswer(i -> new LinkedHashSet<>(Arrays.asList(failingPid, livePid1, livePid2)));

        try (MockedStatic<Files> filesMock = mockStatic(Files.class);
             MockedStatic<PidUtil> pidMock = mockStatic(PidUtil.class);
             MockedStatic<Processes> processesMock = mockStatic(Processes.class)) {
            filesMock.when(() -> Files.exists(Paths.get(CONTROLLER_PATH))).thenReturn(false);
            filesMock.when(() -> Files.exists(argThat(path -> !path.equals(Paths.get(CONTROLLER_PATH)))))
                    .thenReturn(true);
            filesMock.when(() -> Files.readAllLines(any(Path.class))).thenReturn(Collections.emptyList());
            // A live pid's write fails on the memory pass; it must not stop the remaining pids from being added
            filesMock.when(() -> Files.write(any(Path.class), any(byte[].class))).thenAnswer(i -> {
                Path path = i.getArgument(0);
                int pid = pidFromBytes(i.getArgument(1));
                if (pid == failingPid && path.equals(memoryProcs)) {
                    throw new IOException("Permission denied");
                }
                written.computeIfAbsent(path, p -> new ArrayList<>()).add(pid);
                return path;
            });
            pidMock.when(() -> PidUtil.getPid(process)).thenReturn(rootPid);
            mockPidAlive(processesMock, failingPid, true);

            controller = new LinuxSystemResourceController(mockPlatform);
            controller.addComponentProcess(mockService, process);

            assertTrue(written.getOrDefault(memoryProcs, Collections.emptyList())
                    .containsAll(Arrays.asList(livePid1, livePid2, rootPid)),
                    "memory cgroup pids: " + written.get(memoryProcs));
            assertTrue(written.getOrDefault(cpuProcs, Collections.emptyList())
                    .containsAll(Arrays.asList(failingPid, livePid1, livePid2, rootPid)),
                    "cpu cgroup pids: " + written.get(cpuProcs));
            verify(ses, times(2)).schedule(any(Runnable.class), eq(1L), eq(TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void GIVEN_cgroupV1_and_write_error_for_live_pid_WHEN_pauseComponentProcesses_THEN_remaining_pids_added_and_throws(
            boolean livenessCheckFails, ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, IOException.class);
        int failingPid = 100;
        int livePid = 6808;
        int rootPid = 6800;
        Path freezerProcs = CgroupV1.Freezer.getCgroupProcsPath(TEST_COMPONENT);
        List<Integer> written = new ArrayList<>();
        Process process = mock(Process.class);
        when(mockPlatform.getChildPids(process))
                .thenAnswer(i -> new LinkedHashSet<>(Arrays.asList(failingPid, livePid)));

        try (MockedStatic<Files> filesMock = mockStatic(Files.class);
             MockedStatic<PidUtil> pidMock = mockStatic(PidUtil.class);
             MockedStatic<Processes> processesMock = mockStatic(Processes.class)) {
            filesMock.when(() -> Files.exists(Paths.get(CONTROLLER_PATH))).thenReturn(false);
            filesMock.when(() -> Files.exists(argThat(path -> !path.equals(Paths.get(CONTROLLER_PATH)))))
                    .thenReturn(true);
            filesMock.when(() -> Files.readAllLines(any(Path.class))).thenReturn(Collections.emptyList());
            filesMock.when(() -> Files.write(eq(freezerProcs), any(byte[].class))).thenAnswer(i -> {
                int pid = pidFromBytes(i.getArgument(1));
                if (pid == failingPid) {
                    throw new IOException("Permission denied");
                }
                written.add(pid);
                return freezerProcs;
            });
            pidMock.when(() -> PidUtil.getPid(process)).thenReturn(rootPid);
            if (livenessCheckFails) {
                // If liveness can't be checked, the pid must be treated as alive rather than skipped
                PidProcess pidProcess = mock(PidProcess.class);
                when(pidProcess.isAlive()).thenThrow(new IOException("liveness check failed"));
                processesMock.when(() -> Processes.newPidProcess(failingPid)).thenReturn(pidProcess);
            } else {
                mockPidAlive(processesMock, failingPid, true);
            }

            controller = new LinuxSystemResourceController(mockPlatform);
            IOException e = assertThrows(IOException.class,
                    () -> controller.pauseComponentProcesses(mockService, Collections.singletonList(process)));

            assertTrue(e.getMessage().contains(String.valueOf(failingPid)), e.getMessage());
            assertTrue(written.containsAll(Arrays.asList(livePid, rootPid)), "freezer cgroup pids: " + written);
            // Pause fails before freezing
            filesMock.verify(() -> Files.write(eq(CgroupV1.Freezer.getCgroupFreezerStateFilePath(TEST_COMPONENT)),
                    any(byte[].class), any()), never());
        }
    }

    @Test
    void GIVEN_cgroupV1_and_short_lived_pid_WHEN_pauseComponentProcesses_THEN_remaining_pids_added_and_frozen()
            throws Exception {
        int deadPid = 100;
        int livePid = 6808;
        int rootPid = 6800;
        Path freezerProcs = CgroupV1.Freezer.getCgroupProcsPath(TEST_COMPONENT);
        Path freezerState = CgroupV1.Freezer.getCgroupFreezerStateFilePath(TEST_COMPONENT);
        List<Integer> written = new ArrayList<>();
        Process process = mock(Process.class);
        when(mockPlatform.getChildPids(process))
                .thenAnswer(i -> new LinkedHashSet<>(Arrays.asList(deadPid, livePid)));

        try (MockedStatic<Files> filesMock = mockStatic(Files.class);
             MockedStatic<PidUtil> pidMock = mockStatic(PidUtil.class);
             MockedStatic<Processes> processesMock = mockStatic(Processes.class)) {
            filesMock.when(() -> Files.exists(Paths.get(CONTROLLER_PATH))).thenReturn(false);
            filesMock.when(() -> Files.exists(argThat(path -> !path.equals(Paths.get(CONTROLLER_PATH)))))
                    .thenReturn(true);
            filesMock.when(() -> Files.readAllLines(any(Path.class))).thenReturn(Collections.emptyList());
            filesMock.when(() -> Files.readAllLines(freezerState)).thenReturn(Collections.singletonList("THAWED"));
            filesMock.when(() -> Files.write(eq(freezerProcs), any(byte[].class))).thenAnswer(i -> {
                int pid = pidFromBytes(i.getArgument(1));
                if (pid == deadPid) {
                    throw new IOException("No such process");
                }
                written.add(pid);
                return freezerProcs;
            });
            pidMock.when(() -> PidUtil.getPid(process)).thenReturn(rootPid);
            mockPidAlive(processesMock, deadPid, false);

            controller = new LinuxSystemResourceController(mockPlatform);
            controller.pauseComponentProcesses(mockService, Collections.singletonList(process));

            assertTrue(written.containsAll(Arrays.asList(livePid, rootPid)), "freezer cgroup pids: " + written);
            filesMock.verify(() -> Files.write(eq(freezerState),
                    AdditionalMatchers.aryEq("FROZEN".getBytes(StandardCharsets.UTF_8)),
                    eq(StandardOpenOption.TRUNCATE_EXISTING)), times(1));
        }
    }

    private static void mockPidAlive(MockedStatic<Processes> processesMock, int pid, boolean alive)
            throws Exception {
        PidProcess pidProcess = mock(PidProcess.class);
        when(pidProcess.isAlive()).thenReturn(alive);
        processesMock.when(() -> Processes.newPidProcess(pid)).thenReturn(pidProcess);
    }

    // Test stub helper: written content is always Integer.toString(pid), so NFE is unreachable here.
    private static int pidFromBytes(byte[] bytes) {
        String s = new String(bytes, StandardCharsets.UTF_8);
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("unexpected cgroup.procs write: " + s, e);
        }
    }
}
