/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.testcommons.testutilities.GGServiceTestUtil;
import com.aws.greengrass.util.Exec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.Collections;
import java.util.HashMap;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.POSIX_GROUP_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.POSIX_USER_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.RUN_WITH_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class GenericExternalServiceTest extends GGServiceTestUtil {
    private GenericExternalService ges;

    @BeforeEach
    void beforeEach() {
        lenient().doReturn(Topic.of(context, VERSION_CONFIG_KEY, "1.0.0")).when(config).find(eq(VERSION_CONFIG_KEY));
        ges = new GenericExternalService(initializeMockedConfig());
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
    }

    @Test
    void GIVEN_new_config_with_new_version_WHEN_isBootstrapRequired_THEN_return_true() {
        assertTrue(ges.isBootstrapRequired(new HashMap<String, Object>() {{
            put(VERSION_CONFIG_KEY, "1.0.1");
            put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put(Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC, "echo done");
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
    }

    @EnabledOnOs({ OS.LINUX, OS.MAC })
    @Test
    void GIVEN_posix_runwith_info_WHEN_exec_add_group_THEN_use_runwith() throws Exception {
        doReturn("foo").when(config).findOrDefault(nullable(String.class),
                eq(RUN_WITH_NAMESPACE_TOPIC),
                eq(POSIX_USER_KEY));
        doReturn("bar").when(config).findOrDefault(nullable(String.class),
                eq(RUN_WITH_NAMESPACE_TOPIC),
                eq(POSIX_GROUP_KEY));

        ges.storeInitialRunWithConfiguration();

        try (Exec exec = ges.addUserGroup(new Exec().withExec("echo", "hello"))) {
            assertThat(exec.getCommand(), arrayContaining("sudo", "-E", "-u", "foo", "-g", "bar", "--", "echo", "hello"));
        }
    }

    @Test
    void GIVEN_no_runwith_info_WHEN_exec_add_group_THEN_do_not_decorate_command_with_user() throws Exception {
        ges.storeInitialRunWithConfiguration();

        try (Exec exec = ges.addUserGroup(new Exec().withExec("echo", "hello"))) {
            assertThat(exec.getCommand(), arrayContaining("echo", "hello"));
        }
    }

}
