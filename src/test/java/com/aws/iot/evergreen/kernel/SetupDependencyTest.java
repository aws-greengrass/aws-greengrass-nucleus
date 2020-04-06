package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Subscriber;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.testcommons.testutilities.EGServiceTestUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

public class SetupDependencyTest extends EGServiceTestUtil {

    private EvergreenService evergreenService;


    @BeforeEach
    void beforeEach() {
        evergreenService = new EvergreenService(initializeMockedConfig());
        evergreenService.context = context;
        Kernel mockKernel = Mockito.mock(Kernel.class);
        Mockito.when(context.get(Kernel.class)).thenReturn(mockKernel);
    }

    @Test
    void GIVEN_no_dependencies_added_WHEN_dependency_is_added_THEN_dependency_add_successful() throws Exception {
        // GIVEN
        EvergreenService dep1 = Mockito.mock(EvergreenService.class);
        Topic depStateTopic = Mockito.mock(Topic.class);
        Mockito.when(depStateTopic.subscribe(Mockito.any(Subscriber.class))).thenReturn(depStateTopic);
        Mockito.when(dep1.getStateTopic()).thenReturn(depStateTopic);

        // WHEN
        evergreenService.addOrUpdateDependency(dep1, State.INSTALLED, false);

        // THEN
        Map<EvergreenService, State> dependencies = evergreenService.getDependencies();
        // verify dependency added
        Assertions.assertEquals(1, dependencies.size());
        Assertions.assertEquals(State.INSTALLED, dependencies.get(dep1));
    }

    @Test
    void GIVEN_dependency_exist_WHEN_dependency_is_updated_THEN_update_successful() throws Exception {
        // GIVEN
        EvergreenService dep1 = Mockito.mock(EvergreenService.class);
        Topic depStateTopic = Mockito.mock(Topic.class);
        Mockito.when(depStateTopic.subscribe(Mockito.any(Subscriber.class))).thenReturn(depStateTopic);
        Mockito.when(dep1.getStateTopic()).thenReturn(depStateTopic);

        evergreenService.addOrUpdateDependency(dep1, State.INSTALLED, false);

        Map<EvergreenService, State> dependencies = evergreenService.getDependencies();
        Assertions.assertEquals(1, dependencies.size());
        Assertions.assertEquals(State.INSTALLED, dependencies.get(dep1));

        // WHEN
        evergreenService.addOrUpdateDependency(dep1, State.RUNNING, true);

        // THEN
        dependencies = evergreenService.getDependencies();
        Assertions.assertEquals(1, dependencies.size());
        Assertions.assertEquals(State.RUNNING, dependencies.get(dep1));
        // Remove the previous subscriber.
        Mockito.verify(depStateTopic).remove(Mockito.any(Subscriber.class));
    }
}
