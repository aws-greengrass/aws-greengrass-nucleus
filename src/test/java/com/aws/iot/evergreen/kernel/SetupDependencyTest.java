package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Subscriber;
import com.aws.iot.evergreen.dependency.DependencyType;
import com.aws.iot.evergreen.kernel.exceptions.InputValidationException;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.testcommons.testutilities.EGServiceTestUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SetupDependencyTest extends EGServiceTestUtil {

    private EvergreenService evergreenService;
    private Kernel mockKernel;


    @BeforeEach
    void beforeEach() {
        evergreenService = new EvergreenService(initializeMockedConfig());
        evergreenService.context = context;
        mockKernel = mock(Kernel.class);
        when(context.get(Kernel.class)).thenReturn(mockKernel);
    }

    @Test
    void GIVEN_no_dependencies_added_WHEN_dependency_is_added_THEN_dependency_add_successful() throws Exception {
        // GIVEN
        EvergreenService dep1 = mock(EvergreenService.class);

        // WHEN
        evergreenService.addOrUpdateDependency(dep1, DependencyType.SOFT, false);

        // THEN
        Map<EvergreenService, DependencyType> dependencies = evergreenService.getDependencies();
        // verify dependency added
        assertEquals(1, dependencies.size());
        assertEquals(DependencyType.SOFT, dependencies.get(dep1));
    }

    @Test
    void GIVEN_dependency_exist_WHEN_dependency_is_updated_THEN_update_successful() throws Exception {
        // GIVEN
        EvergreenService dep1 = mock(EvergreenService.class);

        evergreenService.addOrUpdateDependency(dep1, DependencyType.SOFT, false);
        verify(dep1).addStateSubscriber(any(Subscriber.class));

        Map<EvergreenService, DependencyType> dependencies = evergreenService.getDependencies();
        assertEquals(1, dependencies.size());
        assertEquals(DependencyType.SOFT, dependencies.get(dep1));

        // WHEN
        evergreenService.addOrUpdateDependency(dep1, DependencyType.HARD, true);

        // THEN
        dependencies = evergreenService.getDependencies();
        assertEquals(1, dependencies.size());
        assertEquals(DependencyType.HARD, dependencies.get(dep1));
        // Remove the previous subscriber.
        verify(dep1).removeStateSubscriber(any(Subscriber.class));
    }

    @Test
    void GIVEN_dependency_list_WHEN_parse_in_case_insensitive_way_THEN_get_dependency_name_and_type()
            throws ServiceLoadException, InputValidationException {
        EvergreenService svcA = mock(EvergreenService.class);
        EvergreenService svcB = mock(EvergreenService.class);
        EvergreenService svcC = mock(EvergreenService.class);
        when(mockKernel.locate("svcA")).thenReturn(svcA);
        when(mockKernel.locate("svcB")).thenReturn(svcB);
        when(mockKernel.locate("svcC")).thenReturn(svcC);

        Map<EvergreenService, DependencyType> dependencyMap = evergreenService.getDependencyTypeMap(Arrays
                .asList("svcA", "svcB:Hard", "svcC:sOFT"));
        assertEquals(3, dependencyMap.size());
        assertEquals(DependencyType.HARD, dependencyMap.get(svcA));
        assertEquals(DependencyType.HARD, dependencyMap.get(svcB));
        assertEquals(DependencyType.SOFT, dependencyMap.get(svcC));
    }
}
