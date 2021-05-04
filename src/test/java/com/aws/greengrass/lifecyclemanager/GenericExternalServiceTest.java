/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceException;
import com.aws.greengrass.testcommons.testutilities.GGServiceTestUtil;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.StubResourceController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mock;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.LogManagerHelper.SERVICE_CONFIG_LOGGING_TOPICS;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class GenericExternalServiceTest extends GGServiceTestUtil {
    private GenericExternalService ges;

    @Mock
    private Platform platform;

    @Mock
    private StubResourceController resourceController;

    @BeforeEach
    void beforeEach() {
        lenient().doReturn(Topics.of(context, SERVICE_CONFIG_LOGGING_TOPICS, null))
                .when(config).lookupTopics(eq(SERVICE_CONFIG_LOGGING_TOPICS));
        lenient().doReturn(Topic.of(context, VERSION_CONFIG_KEY, "1.0.0")).when(config).find(eq(VERSION_CONFIG_KEY));
        lenient().when(platform.getSystemResourceController()).thenReturn(resourceController);
        ges = spy(new GenericExternalService(initializeMockedConfig(), platform));
        ges.deviceConfiguration = mock(DeviceConfiguration.class);
    }

    @Test
    void GIVEN_new_config_without_bootstrap_WHEN_isBootstrapRequired_THEN_return_false() {
        assertFalse(ges.isBootstrapRequired(Collections.emptyMap()));
        assertFalse(ges.isBootstrapRequired(new HashMap<String, Object>() {{
            put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put(Lifecycle.LIFECYCLE_INSTALL_NAMESPACE_TOPIC, "echo done");
            }});
        }}));
        assertFalse(ges.isBootstrapRequired(new HashMap<String, Object>() {{
            put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put(Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC, null);
            }});
        }}));
        assertFalse(ges.isBootstrapRequired(new HashMap<String, Object>() {{
            put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put("Bootstrap", null);
            }});
        }}));
    }

    @Test
    void GIVEN_new_config_with_new_version_WHEN_isBootstrapRequired_THEN_return_true() {
        assertTrue(ges.isBootstrapRequired(new HashMap<String, Object>() {{
            put(VERSION_CONFIG_KEY, "1.0.1");
            put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put(Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC, "echo done");
            }});
        }}));

        assertTrue(ges.isBootstrapRequired(new HashMap<String, Object>() {{
            put(VERSION_CONFIG_KEY, "1.0.1");
            put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put("Bootstrap", "echo done");
            }});
        }}));
    }

    @Test
    void GIVEN_new_config_with_new_bootstrap_definition_WHEN_isBootstrapRequired_THEN_return_true() {
        doReturn(Topic.of(context, Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC, "echo complete")).when(config)
                .findNode(eq(SERVICE_LIFECYCLE_NAMESPACE_TOPIC), eq(Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC));

        assertTrue(ges.isBootstrapRequired(new HashMap<String, Object>() {{
            put(VERSION_CONFIG_KEY, "1.0.0");
            put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put(Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC, "echo done");
            }});
        }}));
        assertTrue(ges.isBootstrapRequired(new HashMap<String, Object>() {{
            put(VERSION_CONFIG_KEY, "1.0.0");
            put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put("Bootstrap", "echo done");
            }});
        }}));
    }

    @Test
    void GIVEN_nested_bootstrap_definition_WHEN_isBootstrapRequired_THEN_return_false() {
        Topics bootstrap = Topics.of(context, Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC, null);
        bootstrap.createLeafChild("script").withValue("\necho complete\n");
        doReturn(bootstrap).when(config)
                .findNode(eq(SERVICE_LIFECYCLE_NAMESPACE_TOPIC), eq(Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC));

        assertFalse(ges.isBootstrapRequired(new HashMap<String, Object>() {{
            put(VERSION_CONFIG_KEY, "1.0.0");
            put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put(Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                    put("script", "\necho complete\n");
                }});
            }});
        }}));
        assertFalse(ges.isBootstrapRequired(new HashMap<String, Object>() {{
            put(VERSION_CONFIG_KEY, "1.0.0");
            put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put("Bootstrap", new HashMap<String, Object>() {{
                    put("script", "\necho complete\n");
                }});
            }});
        }}));
    }

    @Test
    void GIVEN_bootstrap_definition_WHEN_isBootstrapRequired_THEN_key_case_insensitive_value_type_insensitive() {
        Topics bootstrap = Topics.of(context, Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC, null);
        bootstrap.createLeafChild("script").withValue("\necho complete\n");
        bootstrap.createLeafChild("RequiresPrivilege").withValue("true");
        bootstrap.createLeafChild("Timeout").withValue("120");
        doReturn(bootstrap).when(config)
                .findNode(eq(SERVICE_LIFECYCLE_NAMESPACE_TOPIC), eq(Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC));

        assertFalse(ges.isBootstrapRequired(new HashMap<String, Object>() {{
            put(VERSION_CONFIG_KEY, "1.0.0");
            put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put(Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                    put("script", "\necho complete\n");
                    put("requiresPrivilege", true);
                    put("Timeout", 120);
                }});
            }});
        }}));
        assertFalse(ges.isBootstrapRequired(new HashMap<String, Object>() {{
            put(VERSION_CONFIG_KEY, "1.0.0");
            put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put("Bootstrap", new HashMap<String, Object>() {{
                    put("script", "\necho complete\n");
                    put("RequiresPrivilege", true);
                    put("timeout", 120);
                }});
            }});
        }}));
        assertTrue(ges.isBootstrapRequired(new HashMap<String, Object>() {{
            put(VERSION_CONFIG_KEY, "1.0.0");
            put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put("Bootstrap", new HashMap<String, Object>() {{
                    put("script", "\necho complete\n");
                    put("RequiresPrivilege", true);
                    put("timeout", 100);
                }});
            }});
        }}));
        assertTrue(ges.isBootstrapRequired(new HashMap<String, Object>() {{
            put(VERSION_CONFIG_KEY, "1.0.0");
            put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put("Bootstrap", new HashMap<String, Object>() {{
                    put("script", "\necho complete\n");
                    put("RequiresPrivilege", true);
                    put("timeout", 120);
                    put("Setenv", "");
                }});
            }});
        }}));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void GIVEN_runwith_info_WHEN_exec_add_group_THEN_use_runwith() throws Exception {
        ges.runWith = RunWith.builder().user("foo").group("bar").build();

        try (Exec exec = ges.addUserGroup(new Exec().withExec("echo", "hello"))) {
            assertThat(exec.getCommand(), arrayContaining("sudo", "-n", "-E", "-H", "-u", "foo", "-g", "bar",
                    "--", "echo", "hello"));
        }
    }

    @Test
    void GIVEN_service_running_WHEN_pause_requested_THEN_pause() throws Exception {
        ges.install();
        ges.startup();
        ges.pause();
        verify(resourceController).pauseComponentProcesses(any(), any());
        assertTrue(ges.isPaused());

        ges.shutdown();
        // On shutdown, expect resume and remove to be invoked.
        verify(resourceController).resumeComponentProcesses(ges);
        verify(resourceController).removeResourceController(ges);
    }

    @Test
    void GIVEN_service_paused_WHEN_pause_fails_THEN_service_exception_thrown() throws Exception {
        doThrow(new IOException("Could not pause")).when(resourceController).pauseComponentProcesses(any(), any());
        ges.install();
        ges.startup();

        assertThrows(ServiceException.class, () -> ges.pause());
        verify(resourceController).pauseComponentProcesses(any(), any());
        assertFalse(ges.isPaused());

        ges.shutdown();

        // On shutdown, expect remove to be invoked. As the component could not be paused, resume should not be invoked
        verify(resourceController, never()).resumeComponentProcesses(ges);
        verify(resourceController).removeResourceController(ges);
    }

    @Test
    void GIVEN_service_paused_WHEN_resume_requested_THEN_resume() throws Exception {
        ges.install();
        ges.startup();
        ges.pause();
        verify(resourceController).pauseComponentProcesses(any(), any());
        assertTrue(ges.isPaused());

        ges.resume();
        verify(resourceController).resumeComponentProcesses(ges);
        assertFalse(ges.isPaused());

        ges.shutdown();
        // On shutdown, expect remove to be invoked. As the component was already resumed, resume should
        // not be invoked again
        verify(resourceController).resumeComponentProcesses(ges);
        verify(resourceController).removeResourceController(ges);
    }

    @Test
    void GIVEN_service_paused_WHEN_resume_fails_THEN_retry_and_succeed() throws Exception {
        doThrow(new IOException("Could not resume")).doThrow(new IOException("Could not resume")).doNothing()
                .when(resourceController).resumeComponentProcesses(ges);
        ges.install();
        ges.startup();
        ges.pause();
        verify(resourceController).pauseComponentProcesses(any(), any());
        assertTrue(ges.isPaused());

        ges.resume();
        // Should be retried 3 times
        verify(resourceController, times(3)).resumeComponentProcesses(ges);
        assertFalse(ges.isPaused());

        ges.shutdown();
        // On shutdown, expect remove to be invoked. As the component was already resumed paused, resume should
        // not be invoked again
        verify(resourceController, times(3)).resumeComponentProcesses(ges);
        verify(resourceController).removeResourceController(ges);
    }

    @Test
    void GIVEN_service_paused_WHEN_resume_fails_all_attempts_THEN_service_exception_thrown_service_restarted()
            throws Exception {
        doThrow(new IOException("Could not resume")).doThrow(new IOException("Could not resume"))
                .doThrow(new IOException("Could not resume")).when(resourceController).resumeComponentProcesses(ges);
        ges.install();
        ges.startup();
        ges.pause();
        verify(resourceController).pauseComponentProcesses(any(), any());
        assertTrue(ges.isPaused());

        assertThrows(ServiceException.class, () -> ges.resume());
        // Should be retried 3 times
        verify(resourceController, times(3)).resumeComponentProcesses(ges);
        verify(ges).requestRestart();
        // Tracking flag should still be reset
        assertFalse(ges.isPaused());
    }

    @Test
    void GIVEN_service_paused_WHEN_shutdown_requested_and_resume_fails_THEN_service_force_stopped(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, ServiceException.class);
        doThrow(new IOException("Could not resume")).when(resourceController).resumeComponentProcesses(ges);

        ges.install();
        ges.startup();
        ges.pause();
        verify(resourceController).pauseComponentProcesses(any(), any());
        assertTrue(ges.isPaused());

        ges.shutdown();
        // On shutdown, expect resume (without retry) and remove to be invoked.
        verify(resourceController).resumeComponentProcesses(ges);
        verify(resourceController).removeResourceController(ges);
        // Tracking flag should still be reset
        assertFalse(ges.isPaused());
    }
}
