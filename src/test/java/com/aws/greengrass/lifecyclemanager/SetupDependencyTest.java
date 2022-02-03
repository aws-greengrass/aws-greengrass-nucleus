/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.amazon.aws.iot.greengrass.component.common.DependencyType;
import com.aws.greengrass.config.Subscriber;
import com.aws.greengrass.lifecyclemanager.exceptions.InputValidationException;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.testcommons.testutilities.GGServiceTestUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class SetupDependencyTest extends GGServiceTestUtil {

    private GreengrassService greengrassService;
    private Kernel mockKernel;


    @BeforeEach
    void beforeEach() {
        greengrassService = new GreengrassService(initializeMockedConfig());
        greengrassService.context = context;
        mockKernel = mock(Kernel.class, withSettings().lenient());
        when(context.get(Kernel.class)).thenReturn(mockKernel);
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
        verify(dep1).addStateSubscriber(any(Subscriber.class));

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
        verify(dep1).removeStateSubscriber(any(Subscriber.class));
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
}
