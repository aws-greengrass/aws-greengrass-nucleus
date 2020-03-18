package com.aws.iot.evergreen.kernel;

import java.lang.reflect.Field;
import java.util.Map;

import com.aws.iot.evergreen.config.Subscriber;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.testcommons.testutilities.EGServiceTestUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class SetupDependencyTest extends EGServiceTestUtil {

    private EvergreenService evergreenService;
    private static Field dependenciesField;
    private Map<EvergreenService, EvergreenService.DependencyInfo> dependencies;

    @BeforeAll
    static void setup() throws Exception {
        dependenciesField = EvergreenService.class.getDeclaredField("dependencies");
        dependenciesField.setAccessible(true);
    }
    @BeforeEach
    void beforeEach() throws Exception {
        evergreenService = new EvergreenService(initializeMockedConfig());
        evergreenService.context = context;
        Kernel mockKernel = Mockito.mock(Kernel.class);
        Mockito.when(context.get(Kernel.class)).thenReturn(mockKernel);
        dependencies = (Map<EvergreenService, EvergreenService.DependencyInfo>) dependenciesField.get(evergreenService);
    }

    @Test
    void GIVEN_no_dependencies_added_WHEN_dependency_is_added_THEN_dependency_add_successful() throws Exception {
        // GIVEN
        EvergreenService dep1 = Mockito.mock(EvergreenService.class);
        Topic depStateTopic = Mockito.mock(Topic.class);
        Mockito.when(depStateTopic.subscribe(Mockito.any(Subscriber.class))).thenReturn(depStateTopic);
        Mockito.when(dep1.getStateTopic()).thenReturn(depStateTopic);

        // WHEN
        evergreenService.addDependency(dep1, State.INSTALLED, false);

        // THEN
        // verify dependency added
        Assertions.assertEquals(1, dependencies.size());
        Assertions.assertEquals(State.INSTALLED, dependencies.get(dep1).startWhen);
        Assertions.assertEquals(false, dependencies.get(dep1).isDefaultDependency);
        Mockito.verify(depStateTopic, Mockito.times(1)).subscribe(Mockito.any(Subscriber.class));
    }

    @Test
    void GIVEN_dependency_exist_WHEN_dependency_is_updated_THEN_update_successful() throws Exception {
        // GIVEN
        EvergreenService dep1 = Mockito.mock(EvergreenService.class);
        Topic depStateTopic = Mockito.mock(Topic.class);
        Mockito.when(depStateTopic.subscribe(Mockito.any(Subscriber.class))).thenReturn(depStateTopic);
        Mockito.when(dep1.getStateTopic()).thenReturn(depStateTopic);

        evergreenService.addDependency(dep1, State.INSTALLED, false);

        Assertions.assertEquals(1, dependencies.size());
        Assertions.assertEquals(State.INSTALLED, dependencies.get(dep1).startWhen);
        Assertions.assertEquals(false, dependencies.get(dep1).isDefaultDependency);
        Mockito.verify(depStateTopic, Mockito.times(1)).subscribe(Mockito.any(Subscriber.class));

        // WHEN
        evergreenService.addDependency(dep1, State.RUNNING, true);

        // THEN
        Assertions.assertEquals(1, dependencies.size());
        Assertions.assertEquals(State.RUNNING, dependencies.get(dep1).startWhen);
        Assertions.assertEquals(true, dependencies.get(dep1).isDefaultDependency);
        // verify stateTopic listener isn't added duplicated
        Mockito.verifyNoMoreInteractions(depStateTopic);

        // WHEN
        evergreenService.addDependency(dep1, State.RUNNING, false);

        // THEN
        Assertions.assertEquals(1, dependencies.size());
        Assertions.assertEquals(State.RUNNING, dependencies.get(dep1).startWhen);
        // verify isDefault isn't overridden
        Assertions.assertEquals(true, dependencies.get(dep1).isDefaultDependency);
        // verify stateTopic listener isn't added duplicated
        Mockito.verifyNoMoreInteractions(depStateTopic);
    }


    //TODO: add test for setupDependencies
}
