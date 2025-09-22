/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PREV_VERSION_CONFIG_KEY;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComponentManagerCleanupTest {

    @Mock
    private Kernel kernel;
    @Mock
    private ComponentStore componentStore;

    private ComponentManager componentManager;

    @BeforeEach
    void setup() {
        componentManager = new ComponentManager(null, null, null, componentStore, kernel, null, null, null);
    }

    @Test
    void GIVEN_successful_deployment_WHEN_getVersionsToKeep_THEN_keeps_current_and_previous() {
        GreengrassService service = createServiceWithBothVersions("TestComponent", "2.0.0", "1.0.0");
        when(kernel.orderedDependencies()).thenReturn(Collections.singletonList(service));
        
        DeploymentResult result = new DeploymentResult(DeploymentResult.DeploymentStatus.SUCCESSFUL, null);
        Map<String, Set<String>> versions = componentManager.getVersionsToKeep(result);
        
        assertEquals(new HashSet<>(Arrays.asList("2.0.0", "1.0.0")), versions.get("TestComponent"));
    }

    @Test
    void GIVEN_failed_deployment_WHEN_getVersionsToKeep_THEN_keeps_three_versions() {
        GreengrassService service = createServiceWithBothVersions("TestComponent", "3.0.0", "2.0.0");
        when(kernel.orderedDependencies()).thenReturn(Collections.singletonList(service));
        
        // Mock available versions in component store
        Map<String, Set<String>> availableVersions = new HashMap<>();
        availableVersions.put("TestComponent", new HashSet<>(Arrays.asList("1.0.0", "2.0.0", "3.0.0")));
        when(componentStore.listAvailableComponentVersions()).thenReturn(availableVersions);
        
        DeploymentResult result = new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, 
                new RuntimeException());
        Map<String, Set<String>> versions = componentManager.getVersionsToKeep(result);
        
        // Should keep current (3.0.0 failed) + previous (2.0.0) + one more (1.0.0) = 3 total
        assertEquals(new HashSet<>(Arrays.asList("3.0.0", "2.0.0", "1.0.0")), versions.get("TestComponent"));
    }

    @Test
    void GIVEN_null_deployment_WHEN_getVersionsToKeep_THEN_keeps_current_and_previous() {
        GreengrassService service = createServiceWithBothVersions("TestComponent", "2.0.0", "1.0.0");
        when(kernel.orderedDependencies()).thenReturn(Collections.singletonList(service));
        
        Map<String, Set<String>> versions = componentManager.getVersionsToKeep(null);
        
        assertEquals(new HashSet<>(Arrays.asList("2.0.0", "1.0.0")), versions.get("TestComponent"));
    }

    private GreengrassService createServiceWithBothVersions(String name, String currentVersion, String previousVersion) {
        GreengrassService service = mock(GreengrassService.class);
        Topics config = mock(Topics.class);
        Topic versionTopic = mock(Topic.class);
        Topic prevVersionTopic = mock(Topic.class);
        
        when(service.getName()).thenReturn(name);
        when(service.getServiceConfig()).thenReturn(config);
        when(versionTopic.getOnce()).thenReturn(currentVersion);
        when(prevVersionTopic.getOnce()).thenReturn(previousVersion);
        when(config.find(VERSION_CONFIG_KEY)).thenReturn(versionTopic);
        when(config.find(PREV_VERSION_CONFIG_KEY)).thenReturn(prevVersionTopic);
        
        return service;
    }
}
