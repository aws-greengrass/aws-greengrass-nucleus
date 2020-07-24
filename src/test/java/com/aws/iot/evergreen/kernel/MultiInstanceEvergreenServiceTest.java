/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.testcommons.testutilities.EGServiceTestUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MultiInstanceEvergreenServiceTest extends EGServiceTestUtil {
    private MultiInstanceEvergreenService service0;

    @BeforeEach
    void beforeEach() {
        initializeMockedConfig();
        service0 = new MultiInstanceEvergreenService(config, 0);
    }

    @Test
    void GIVEN_multiService_WHEN_getName_THEN_name_has_instance() throws ServiceLoadException {
        assertEquals("EvergreenServiceFullName", service0.getName());
        MultiInstanceEvergreenService service1 = service0.createNewInstance();
        assertEquals("EvergreenServiceFullName-1", service1.getName());
    }

    @Test
    void GIVEN_multiService_WHEN_putDependencies_THEN_adds_self_and_other_instances() throws ServiceLoadException {
        MultiInstanceEvergreenService service1 = service0.createNewInstance();
        service0.createNewInstance();

        ServiceLoadException ex = assertThrows(ServiceLoadException.class, service1::createNewInstance);
        assertEquals("New instances may only be created from the base instance", ex.getMessage());

        Set<EvergreenService> deps = new LinkedHashSet<>();
        service0.putDependenciesIntoSet(deps);
        assertThat(deps.stream().map(EvergreenService::getName).collect(Collectors.toList()),
                contains("EvergreenServiceFullName-1", "EvergreenServiceFullName-2", "EvergreenServiceFullName"));
        assertThat(deps, hasSize(3));
    }

    @Test
    void GIVEN_multiService_WHEN_getInstanceId_THEN_gets_the_id() throws ServiceLoadException {
        assertEquals(0, service0.getInstanceId());
        assertEquals(1, service0.createNewInstance().getInstanceId());
        assertEquals(2, service0.createNewInstance().getInstanceId());
    }

    @Test
    void GIVEN_multiService_WHEN_getInstance_THEN_gets_the_instance_by_id() throws ServiceLoadException {
        MultiInstanceEvergreenService service1 = service0.createNewInstance();
        assertEquals(service1, service0.getInstance(1));

        assertNull(service1.getInstance(1));
    }

    @Test
    void GIVEN_multiService_WHEN_removeInstance_THEN_forgets_service_instance() throws ServiceLoadException {
        MultiInstanceEvergreenService service1 = service0.createNewInstance();
        assertEquals(service1, service0.removeInstance(1));
        assertNull(service0.getInstance(1));

        assertNull(service1.removeInstance(0));
    }
}
