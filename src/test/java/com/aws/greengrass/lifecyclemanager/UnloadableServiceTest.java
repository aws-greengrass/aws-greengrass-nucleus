/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.GGServiceTestUtil;
import com.aws.greengrass.util.NucleusPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.lifecyclemanager.Kernel.SERVICE_TYPE_TOPIC_KEY;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;

@ExtendWith({
        GGExtension.class, MockitoExtension.class
})
public class UnloadableServiceTest extends GGServiceTestUtil {

    private UnloadableService unloadableService;

    @TempDir
    Path tempDir;

    @Mock
    NucleusPaths nucleusPaths;

    @BeforeEach
    void beforeEach() throws Exception {
        unloadableService = new UnloadableService(initializeMockedConfig(), new ServiceLoadException("mock error"));
        lenient().doReturn(nucleusPaths).when(context).get(NucleusPaths.class);
        lenient().doReturn(tempDir).when(nucleusPaths).artifactPath(any());
    }

    @Test
    void GIVEN_new_config_missing_information_WHEN_isBootstrapRequired_THEN_return_false() {
        doReturn(Topic.of(context, VERSION_CONFIG_KEY, "1.0.0")).when(config).find(eq(VERSION_CONFIG_KEY));

        assertFalse(unloadableService.isBootstrapRequired(Collections.emptyMap()));

        assertFalse(unloadableService.isBootstrapRequired(new HashMap<String, Object>() {
            {
                put(SERVICE_TYPE_TOPIC_KEY, "PLUGIN");
            }
        }));

        // Plugin jar not found
        assertFalse(unloadableService.isBootstrapRequired(new HashMap<String, Object>() {
            {
                put(SERVICE_TYPE_TOPIC_KEY, "PLUGIN");
                put(VERSION_CONFIG_KEY, "1.0.0");
            }
        }));
    }

    @Test
    void GIVEN_new_config_with_plugin_version_change_WHEN_isBootstrapRequired_THEN_return_true() {
        doReturn(Topic.of(context, VERSION_CONFIG_KEY, "1.0.0")).when(config).find(eq(VERSION_CONFIG_KEY));

        assertTrue(unloadableService.isBootstrapRequired(new HashMap<String, Object>() {
            {
                put(VERSION_CONFIG_KEY, "1.0.1");
                put(SERVICE_TYPE_TOPIC_KEY, "PLUGIN");
            }
        }));
    }

    @Test
    void GIVEN_new_config_with_plugin_jar_change_WHEN_isBootstrapRequired_THEN_return_true() throws Exception {
        doReturn(Topic.of(context, VERSION_CONFIG_KEY, "1.0.0")).when(config).find(eq(VERSION_CONFIG_KEY));

        // Sleep 1 second here before modifying file, because last modified timestamp gives 1 second precision
        Thread.sleep(1_000L);
        Files.createFile(tempDir.resolve("GreengrassServiceFullName.jar"));
        assertTrue(unloadableService.isBootstrapRequired(new HashMap<String, Object>() {
            {
                put(SERVICE_TYPE_TOPIC_KEY, "PLUGIN");
                put(VERSION_CONFIG_KEY, "1.0.0");
            }
        }));
    }
}
