/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.amazon.aws.iot.greengrass.component.common.DependencyType;
import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.lifecyclemanager.exceptions.InputValidationException;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.testcommons.testutilities.GGServiceTestUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;

import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SetupDependencyTest extends GGServiceTestUtil {

    private GreengrassService greengrassService;
    private Kernel mockKernel;


    @BeforeEach
    void beforeEach() {
        greengrassService = new GreengrassService(initializeMockedConfig());
        greengrassService.context = context;
        mockKernel = mock(Kernel.class);
        lenient().when(context.get(Kernel.class)).thenReturn(mockKernel);
    }

    @Test
    void GIVEN_no_dependencies_added_WHEN_dependency_is_added_THEN_dependency_add_successful() throws Exception {
        // GIVEN
        GreengrassService dep1 = mock(GreengrassService.class);

        // WHEN
        greengrassService.addOrUpdateDependency(dep1, DependencyType.SOFT, false);

        // THEN
        Map<GreengrassService, DependencyType> dependencies = greengrassService.getDependencies();
        // verify dependency added
        assertEquals(1, dependencies.size());
        assertEquals(DependencyType.SOFT, dependencies.get(dep1));
    }

    @Test
    void GIVEN_dependency_exist_WHEN_dependency_is_updated_THEN_update_successful() throws Exception {
        // GIVEN
        GreengrassService dep1 = mock(GreengrassService.class);

        greengrassService.addOrUpdateDependency(dep1, DependencyType.SOFT, false);
        verify(context).addGlobalStateChangeListener(any(GlobalStateChangeListener.class));

        Map<GreengrassService, DependencyType> dependencies = greengrassService.getDependencies();
        assertEquals(1, dependencies.size());
        assertEquals(DependencyType.SOFT, dependencies.get(dep1));

        // WHEN
        greengrassService.addOrUpdateDependency(dep1, DependencyType.HARD, true);

        // THEN
        dependencies = greengrassService.getDependencies();
        assertEquals(1, dependencies.size());
        assertEquals(DependencyType.HARD, dependencies.get(dep1));
        // Remove the previous subscriber.
        verify(context).removeGlobalStateChangeListener(any(GlobalStateChangeListener.class));
    }

    @Test
    void GIVEN_dependency_list_WHEN_parse_in_case_insensitive_way_THEN_get_dependency_name_and_type()
            throws ServiceLoadException, InputValidationException {
        GreengrassService svcA = mock(GreengrassService.class);
        GreengrassService svcB = mock(GreengrassService.class);
        GreengrassService svcC = mock(GreengrassService.class);
        when(mockKernel.locateIgnoreError("svcA")).thenReturn(svcA);
        when(mockKernel.locateIgnoreError("svcB")).thenReturn(svcB);
        when(mockKernel.locateIgnoreError("svcC")).thenReturn(svcC);

        Map<GreengrassService, DependencyType> dependencyMap = greengrassService.getDependencyTypeMap(Arrays
                .asList("svcA", "svcB:Hard", "svcC:sOFT"));
        assertEquals(3, dependencyMap.size());
        assertEquals(DependencyType.HARD, dependencyMap.get(svcA));
        assertEquals(DependencyType.HARD, dependencyMap.get(svcB));
        assertEquals(DependencyType.SOFT, dependencyMap.get(svcC));
    }

    @Test
    void GIVEN_default_dependency_WHEN_dependency_topic_lists_then_omits_it_THEN_default_dependency_is_retained()
            throws Exception {
        try (Context realContext = new Context()) {
            Configuration realConfig = new Configuration(realContext);
            Kernel kernel = mock(Kernel.class);
            realContext.put(Kernel.class, kernel);

            GreengrassService main = new GreengrassService(
                    realConfig.lookupTopics(SERVICES_NAMESPACE_TOPIC, "main"));
            GreengrassService builtin = new GreengrassService(
                    realConfig.lookupTopics(SERVICES_NAMESPACE_TOPIC, "builtinService"));
            GreengrassService explicitDep = new GreengrassService(
                    realConfig.lookupTopics(SERVICES_NAMESPACE_TOPIC, "explicitService"));
            when(kernel.locateIgnoreError("builtinService")).thenReturn(builtin);
            when(kernel.locateIgnoreError("explicitService")).thenReturn(explicitDep);

            // Builtin autostart services are injected as default dependencies at launch
            // (KernelLifecycle.launch); they exist only in the in-memory dependency map,
            // not in the dependencies topic.
            main.addOrUpdateDependency(builtin, DependencyType.HARD, true);

            // A deployment writes the dependencies topic including the builtin. This must not
            // downgrade the builtin to a non-default dependency.
            Topic dependenciesTopic = realConfig.lookup(SERVICES_NAMESPACE_TOPIC, "main",
                    SERVICE_DEPENDENCIES_NAMESPACE_TOPIC);
            dependenciesTopic.withValue(
                    new ArrayList<>(Arrays.asList("builtinService:HARD", "explicitService")));
            realContext.waitForPublishQueueToClear();
            assertEquals(new HashSet<>(Arrays.asList(builtin, explicitDep)),
                    main.getDependencies().keySet());

            // A later topic update omitting both (e.g. a deployment rollback to a snapshot taken
            // before the device's first deployment, whose topic value never listed the builtins)
            // must retain the default dependency while removing the explicitly declared one.
            // Removing the builtin here would exclude it from deployment merge protection and
            // cause its config to be deleted by the next deployment while it is still running.
            dependenciesTopic.withValue(new ArrayList<>());
            realContext.waitForPublishQueueToClear();
            assertEquals(new HashSet<>(Collections.singletonList(builtin)),
                    main.getDependencies().keySet());
        }
    }

    @Test
    void GIVEN_evicted_autostart_builtin_WHEN_main_readds_it_from_topic_THEN_it_gets_default_protection()
            throws Exception {
        // Pick a statically known autostart builtin name
        String builtinName = KernelLifecycle.AUTOSTART_BUILTIN_SERVICE_NAMES.iterator().next();

        try (Context realContext = new Context()) {
            Configuration realConfig = new Configuration(realContext);
            Kernel kernel = mock(Kernel.class);
            realContext.put(Kernel.class, kernel);

            GreengrassService main = new GreengrassService(
                    realConfig.lookupTopics(SERVICES_NAMESPACE_TOPIC, "main"));
            GreengrassService builtin = new GreengrassService(
                    realConfig.lookupTopics(SERVICES_NAMESPACE_TOPIC, builtinName));
            when(kernel.locateIgnoreError(builtinName)).thenReturn(builtin);

            // The builtin was previously evicted: main's dependency map does not contain it. A deployment
            // resolution repairs the dependencies topic by listing the builtin again.
            Topic mainDependenciesTopic = realConfig.lookup(SERVICES_NAMESPACE_TOPIC, "main",
                    SERVICE_DEPENDENCIES_NAMESPACE_TOPIC);
            mainDependenciesTopic.withValue(new ArrayList<>(Collections.singletonList(builtinName + ":HARD")));
            realContext.waitForPublishQueueToClear();
            assertEquals(new HashSet<>(Collections.singletonList(builtin)), main.getDependencies().keySet());

            // The re-added builtin must have launch-equivalent default protection: a later topic update
            // omitting it (e.g. another rollback) must not evict it again.
            mainDependenciesTopic.withValue(new ArrayList<>());
            realContext.waitForPublishQueueToClear();
            assertEquals(new HashSet<>(Collections.singletonList(builtin)), main.getDependencies().keySet());

            // A service other than main declaring the same builtin name keeps the existing behavior:
            // the dependency is removable when the topic no longer lists it.
            GreengrassService otherService = new GreengrassService(
                    realConfig.lookupTopics(SERVICES_NAMESPACE_TOPIC, "otherService"));
            Topic otherDependenciesTopic = realConfig.lookup(SERVICES_NAMESPACE_TOPIC, "otherService",
                    SERVICE_DEPENDENCIES_NAMESPACE_TOPIC);
            otherDependenciesTopic.withValue(new ArrayList<>(Collections.singletonList(builtinName + ":HARD")));
            realContext.waitForPublishQueueToClear();
            assertEquals(new HashSet<>(Collections.singletonList(builtin)),
                    otherService.getDependencies().keySet());

            otherDependenciesTopic.withValue(new ArrayList<>());
            realContext.waitForPublishQueueToClear();
            assertEquals(Collections.emptySet(), otherService.getDependencies().keySet());
        }
    }
}
