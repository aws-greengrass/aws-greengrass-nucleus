/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment.bootstrap;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.deployment.exceptions.ServiceUpdateException;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.Lifecycle.LIFECYCLE_INSTALL_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class BootstrapManagerTest {
    private static final String componentA = "componentA";
    private static final String componentB = "componentB";
    @Mock
    Kernel kernel;
    @Mock
    Context context;

    @Test
    void GIVEN_new_config_without_service_change_WHEN_check_isBootstrapRequired_THEN_return_false() throws Exception {
        BootstrapManager bootstrapManager = new BootstrapManager(kernel);
        assertFalse(bootstrapManager.isBootstrapRequired(Collections.emptyMap()));
    }

    @Test
    void GIVEN_new_config_without_service_bootstraps_WHEN_check_isBootstrapRequired_THEN_return_false() throws Exception {
        BootstrapManager bootstrapManager = spy(new BootstrapManager(kernel));
        doReturn(false).when(bootstrapManager).serviceBootstrapRequired(any(), any());
        assertFalse(bootstrapManager.isBootstrapRequired(new HashMap<Object, Object>() {{
            put(SERVICES_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put(componentA, Collections.emptyMap());
                put(componentB, Collections.emptyMap());
            }});
        }}));
    }

    @Test
    void GIVEN_new_config_with_service_bootstraps_WHEN_check_isBootstrapRequired_THEN_return_true() throws Exception {
        BootstrapManager bootstrapManager = spy(new BootstrapManager(kernel));
        doReturn(true).when(bootstrapManager).serviceBootstrapRequired(any(), any());
        assertTrue(bootstrapManager.isBootstrapRequired(new HashMap<Object, Object>() {{
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
    void GIVEN_components_without_changes_in_bootstrap_WHEN_check_serviceBootstrapRequired_THEN_return_false() throws Exception {
        BootstrapManager bootstrapManager = new BootstrapManager(kernel);
        assertFalse(bootstrapManager.serviceBootstrapRequired(componentA, Collections.emptyMap()));
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
    void GIVEN_component_version_changes_with_bootstrap_WHEN_check_serviceBootstrapRequired_THEN_return_true() throws Exception {
        Topic serviceAVersion = Topic.of(context, VERSION_CONFIG_KEY, "1.0.0");
        Topics serviceAConfig = mock(Topics.class);
        when(serviceAConfig.find(VERSION_CONFIG_KEY)).thenReturn(serviceAVersion);
        EvergreenService serviceA = mock(EvergreenService.class);
        when(serviceA.getConfig()).thenReturn(serviceAConfig);
        when(kernel.locate(componentA)).thenReturn(serviceA);
        BootstrapManager bootstrapManager = new BootstrapManager(kernel);

        assertTrue(bootstrapManager.serviceBootstrapRequired(componentA, new HashMap<String, Object>() {{
            put(VERSION_CONFIG_KEY, "1.0.1");
            put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put(LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC, "echo done");
            }});
        }}));
    }

    @Test
    void GIVEN_component_bootstrap_step_changes_WHEN_check_serviceBootstrapRequired_THEN_return_true() throws Exception {
        Topic serviceAVersion = Topic.of(context, VERSION_CONFIG_KEY, "1.0.0");
        Topic serviceABootstrap = Topic.of(context, LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC, "echo complete");

        Topics serviceAConfig = mock(Topics.class);
        when(serviceAConfig.find(VERSION_CONFIG_KEY)).thenReturn(serviceAVersion);
        when(serviceAConfig.find(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC))
                .thenReturn(serviceABootstrap);

        EvergreenService serviceA = mock(EvergreenService.class);
        when(serviceA.getConfig()).thenReturn(serviceAConfig);
        when(kernel.locate(componentA)).thenReturn(serviceA);
        BootstrapManager bootstrapManager = new BootstrapManager(kernel);

        assertTrue(bootstrapManager.serviceBootstrapRequired(componentA, new HashMap<String, Object>() {{
            put(VERSION_CONFIG_KEY, "1.0.0");
            put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put(LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC, "echo done");
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
        doNothing().when(bootstrapManager).persistBootstrapTaskList();

        bootstrapManager.setBootstrapTaskStatusList(pendingTasks);
        assertEquals(101, bootstrapManager.executeAllBootstrapTasksSequentially());
        verify(bootstrapManager, times(2)).persistBootstrapTaskList();
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
                () -> bootstrapManager.executeAllBootstrapTasksSequentially());
        assertThat(exception.getMessage(), stringContainsInOrder(componentA));
        verify(bootstrapManager, times(0)).persistBootstrapTaskList();
    }

    @Test
    void GIVEN_bootstrap_task_list_WHEN_executeAllBootstrapTasksSequentially_THEN_completes_with_restart_request() throws Exception {
        List<BootstrapTaskStatus> pendingTasks = Arrays.asList(
                new BootstrapTaskStatus(componentA),
                new BootstrapTaskStatus(componentB));
        BootstrapManager bootstrapManager = spy(new BootstrapManager(kernel));
        doReturn(0).when(bootstrapManager).executeOneBootstrapTask(eq(pendingTasks.get(0)));
        doReturn(0).when(bootstrapManager).executeOneBootstrapTask(eq(pendingTasks.get(1)));

        bootstrapManager.setBootstrapTaskStatusList(pendingTasks);
        assertEquals(0, bootstrapManager.executeAllBootstrapTasksSequentially());
        verify(bootstrapManager, times(2)).persistBootstrapTaskList();
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
