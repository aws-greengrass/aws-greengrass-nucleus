/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.bootstrap;

import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.ServiceUpdateException;
import com.aws.greengrass.lifecyclemanager.GenericExternalService;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.Lifecycle.LIFECYCLE_INSTALL_NAMESPACE_TOPIC;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyMap;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class BootstrapManagerTest {
    private static final String componentA = "componentA";
    private static final String componentB = "componentB";
    @Mock
    Kernel kernel;
    @Mock
    Context context;
    @Mock
    DeviceConfiguration deviceConfiguration;
    @Mock
    Path filePath;

    @Test
    void GIVEN_new_config_without_service_change_WHEN_check_isBootstrapRequired_THEN_return_false() throws Exception {
        BootstrapManager bootstrapManager = new BootstrapManager(kernel);
        assertFalse(bootstrapManager.isBootstrapRequired(Collections.emptyMap()));
    }

    @Test
    void GIVEN_new_config_without_service_bootstraps_WHEN_check_isBootstrapRequired_THEN_return_false() throws Exception {
        when(kernel.getContext()).thenReturn(context);
        when(context.get(DeviceConfiguration.class)).thenReturn(deviceConfiguration);
        BootstrapManager bootstrapManager = spy(new BootstrapManager(kernel));
        doReturn(false).when(bootstrapManager).serviceBootstrapRequired(any(), any());
        assertFalse(bootstrapManager.isBootstrapRequired(new HashMap<String, Object>() {{
            put(SERVICES_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put(componentA, Collections.emptyMap());
                put(componentB, Collections.emptyMap());
            }});
        }}));
    }

    @Test
    void GIVEN_new_config_with_service_bootstraps_WHEN_check_isBootstrapRequired_THEN_return_true() throws Exception {
        when(kernel.getContext()).thenReturn(context);
        when(context.get(DeviceConfiguration.class)).thenReturn(deviceConfiguration);
        BootstrapManager bootstrapManager = spy(new BootstrapManager(kernel));
        doReturn(true).when(bootstrapManager).serviceBootstrapRequired(any(), any());
        assertTrue(bootstrapManager.isBootstrapRequired(new HashMap<String, Object>() {{
            put(SERVICES_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put(componentA, new HashMap<String, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Arrays.asList("componentB", "componentC:hard"));
                }});
                put(componentB, new HashMap<String, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Arrays.asList("componentC", "componentD:soft"));
                }});
            }});
        }}));
        assertThat(bootstrapManager.getBootstrapTaskStatusList(), contains(
                new BootstrapTaskStatus(componentB),
                new BootstrapTaskStatus(componentA)));
    }

    @Test
    void GIVEN_components_without_changes_in_bootstrap_WHEN_check_serviceBootstrapRequired_THEN_return_service_decision() throws Exception {
        GenericExternalService serviceA = mock(GenericExternalService.class);
        doReturn(true).when(serviceA).isBootstrapRequired(anyMap());
        doReturn(serviceA).when(kernel).locate(eq(componentA));

        BootstrapManager bootstrapManager = new BootstrapManager(kernel);
        assertTrue(bootstrapManager.serviceBootstrapRequired(componentA, Collections.emptyMap()));
    }

    @Test
    void GIVEN_new_component_with_bootstrap_WHEN_check_serviceBootstrapRequired_THEN_return_true() throws Exception {
        when(kernel.locate(componentA)).thenThrow(new ServiceLoadException("mock error"));
        BootstrapManager bootstrapManager = new BootstrapManager(kernel);

        assertTrue(bootstrapManager.serviceBootstrapRequired(componentA, new HashMap<String, Object>() {{
            put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put(LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC, "echo done");
            }});
        }}));
    }

    @Test
    void GIVEN_new_component_without_bootstrap_WHEN_check_serviceBootstrapRequired_THEN_return_false() throws Exception {
        when(kernel.locate(componentA)).thenThrow(new ServiceLoadException("mock error"));
        BootstrapManager bootstrapManager = new BootstrapManager(kernel);

        assertFalse(bootstrapManager.serviceBootstrapRequired(componentA, new HashMap<String, Object>() {{
            put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put(LIFECYCLE_INSTALL_NAMESPACE_TOPIC, "echo done");
            }});
        }}));

        assertFalse(bootstrapManager.serviceBootstrapRequired(componentA, new HashMap<String, Object>() {{
            put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put(LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC, null);
            }});
        }}));
    }

    @Test
    void GIVEN_bootstrap_task_requires_reboot_WHEN_executeAllBootstrapTasksSequentially_THEN_request_reboot() throws Exception {
        List<BootstrapTaskStatus> pendingTasks = Arrays.asList(
                new BootstrapTaskStatus(componentA),
                new BootstrapTaskStatus(componentB));
        BootstrapManager bootstrapManager = spy(new BootstrapManager(kernel));
        doReturn(0).when(bootstrapManager).executeOneBootstrapTask(eq(pendingTasks.get(0)));
        doReturn(101).when(bootstrapManager).executeOneBootstrapTask(eq(pendingTasks.get(1)));
        doNothing().when(bootstrapManager).persistBootstrapTaskList(any());

        bootstrapManager.setBootstrapTaskStatusList(pendingTasks);
        assertEquals(101, bootstrapManager.executeAllBootstrapTasksSequentially(filePath));
        verify(bootstrapManager, times(2)).persistBootstrapTaskList(eq(filePath));
    }

    @Test
    void GIVEN_bootstrap_task_errors_WHEN_executeAllBootstrapTasksSequentially_THEN_throws_error() throws Exception {
        List<BootstrapTaskStatus> pendingTasks = Arrays.asList(
                new BootstrapTaskStatus(componentA),
                new BootstrapTaskStatus(componentB));
        BootstrapManager bootstrapManager = spy(new BootstrapManager(kernel));
        doReturn(99).when(bootstrapManager).executeOneBootstrapTask(eq(pendingTasks.get(0)));

        bootstrapManager.setBootstrapTaskStatusList(pendingTasks);
        ServiceUpdateException exception = assertThrows(ServiceUpdateException.class,
                () -> bootstrapManager.executeAllBootstrapTasksSequentially(filePath));
        assertThat(exception.getMessage(), stringContainsInOrder(componentA));
        verify(bootstrapManager, times(0)).persistBootstrapTaskList(eq(filePath));
    }

    @Test
    void GIVEN_bootstrap_task_list_WHEN_executeAllBootstrapTasksSequentially_THEN_completes_with_restart_request() throws Exception {
        List<BootstrapTaskStatus> pendingTasks = Arrays.asList(
                new BootstrapTaskStatus(componentA),
                new BootstrapTaskStatus(componentB));
        BootstrapManager bootstrapManager = spy(new BootstrapManager(kernel));
        doReturn(0).when(bootstrapManager).executeOneBootstrapTask(eq(pendingTasks.get(0)));
        doReturn(0).when(bootstrapManager).executeOneBootstrapTask(eq(pendingTasks.get(1)));
        doNothing().when(bootstrapManager).persistBootstrapTaskList(any());

        bootstrapManager.setBootstrapTaskStatusList(pendingTasks);
        assertEquals(0, bootstrapManager.executeAllBootstrapTasksSequentially(filePath));
        verify(bootstrapManager, times(2)).persistBootstrapTaskList(eq(filePath));
    }

    @Test
    void GIVEN_bootstrap_task_WHEN_executeOneBootstrapTask_THEN_completes_with_exit_code() throws Exception {
        GreengrassService mockService = mock(GreengrassService.class);
        doReturn(1).when(mockService).bootstrap();
        String componentName = "mockComponent";
        doReturn(mockService).when(kernel).locate(eq("mockComponent"));
        BootstrapManager bootstrapManager = new BootstrapManager(kernel);
        assertEquals(1, bootstrapManager.executeOneBootstrapTask(new BootstrapTaskStatus(componentName)));
    }

    @Test
    void GIVEN_bootstrap_task_WHEN_executeOneBootstrapTask_timed_out_THEN_throws_error() throws Exception {
        GreengrassService mockService = mock(GreengrassService.class);
        doThrow(new TimeoutException("mockError")).when(mockService).bootstrap();
        String componentName = "mockComponent";
        doReturn(mockService).when(kernel).locate(eq("mockComponent"));
        BootstrapManager bootstrapManager = new BootstrapManager(kernel);
        assertThrows(ServiceUpdateException.class,
                () -> bootstrapManager.executeOneBootstrapTask(new BootstrapTaskStatus(componentName)));
    }

    @Test
    void GIVEN_file_path_WHEN_persist_and_load_bootstrap_tasks_THEN_restore_bootstrap_tasks(@TempDir Path tempDir) throws Exception{
        BootstrapTaskStatus taskA = new BootstrapTaskStatus(componentA);
        BootstrapTaskStatus taskB = new BootstrapTaskStatus(componentB);
        List<BootstrapTaskStatus> pendingTasks = new ArrayList<>(Arrays.asList(taskA, taskB));
        BootstrapManager bootstrapManager = new BootstrapManager(kernel);
        bootstrapManager.setBootstrapTaskStatusList(pendingTasks);

        Path filePath = tempDir.resolve("testFile.ser");
        bootstrapManager.persistBootstrapTaskList(filePath);
        bootstrapManager.loadBootstrapTaskList(filePath);
        assertThat(bootstrapManager.getBootstrapTaskStatusList(), contains(taskA, taskB));
    }

    @Test
    void GIVEN_pending_bootstrap_tasks_WHEN_check_hasNext_THEN_return_true() {
        BootstrapManager bootstrapManager = new BootstrapManager(kernel);
        bootstrapManager.setBootstrapTaskStatusList(Arrays.asList(new BootstrapTaskStatus(componentA,
                        BootstrapTaskStatus.ExecutionStatus.DONE, 0),
                new BootstrapTaskStatus(componentB,
                        BootstrapTaskStatus.ExecutionStatus.PENDING, 0)
                ));
        assertTrue(bootstrapManager.hasNext());
    }

    @Test
    void GIVEN_all_bootstrap_tasks_done_WHEN_check_hasNext_THEN_return_false() {
        BootstrapManager bootstrapManager = new BootstrapManager(kernel);
        bootstrapManager.setBootstrapTaskStatusList(Arrays.asList(new BootstrapTaskStatus(componentA,
                        BootstrapTaskStatus.ExecutionStatus.DONE, 0),
                new BootstrapTaskStatus(componentB,
                        BootstrapTaskStatus.ExecutionStatus.DONE, 100)
        ));
        assertFalse(bootstrapManager.hasNext());
    }
}
