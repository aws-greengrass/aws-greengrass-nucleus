package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Subscriber;
import com.aws.iot.evergreen.dependency.Type;
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

        // WHEN
        evergreenService.addOrUpdateDependency(dep1, Type.SOFT, false);

        // THEN
        Map<EvergreenService, Type> dependencies = evergreenService.getDependencies();
        // verify dependency added
        Assertions.assertEquals(1, dependencies.size());
        Assertions.assertEquals(Type.SOFT, dependencies.get(dep1));
    }

    @Test
    void GIVEN_dependency_exist_WHEN_dependency_is_updated_THEN_update_successful() throws Exception {
        // GIVEN
        EvergreenService dep1 = Mockito.mock(EvergreenService.class);

        evergreenService.addOrUpdateDependency(dep1, Type.SOFT, false);
        Mockito.verify(dep1).addStateSubscriber(Mockito.any(Subscriber.class));

        Map<EvergreenService, Type> dependencies = evergreenService.getDependencies();
        Assertions.assertEquals(1, dependencies.size());
        Assertions.assertEquals(Type.SOFT, dependencies.get(dep1));

        // WHEN
        evergreenService.addOrUpdateDependency(dep1, Type.HARD, true);

        // THEN
        dependencies = evergreenService.getDependencies();
        Assertions.assertEquals(1, dependencies.size());
        Assertions.assertEquals(Type.HARD, dependencies.get(dep1));
        // Remove the previous subscriber.
        Mockito.verify(dep1).removeStateSubscriber(Mockito.any(Subscriber.class));
    }
}
