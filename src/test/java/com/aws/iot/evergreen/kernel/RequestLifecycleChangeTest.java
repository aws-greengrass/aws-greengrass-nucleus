package com.aws.iot.evergreen.kernel;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.testcommons.testutilities.EGServiceTestUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class RequestLifecycleChangeTest extends EGServiceTestUtil {

    private EvergreenService evergreenService;

    private static Field desiredStateListField;
    private List<State> desiredStateList;

    @BeforeAll
    public static void setup() throws Exception {
        desiredStateListField = EvergreenService.class.getDeclaredField("desiredStateList");
        desiredStateListField.setAccessible(true);
    }

    @BeforeEach
    void beforeEach() throws Exception {
        Topics config = initializeMockedConfig();
        evergreenService = new EvergreenService(config);
        desiredStateList = (List<State>) desiredStateListField.get(evergreenService);
    }

    @Test
    public void GIVEN_evergreenService_WHEN_requestStart_called_THEN_deduplicate_correctly() {
        desiredStateList.clear();
        evergreenService.requestStart();
        assertDesiredState(State.RUNNING);

        // calling requestRetart() multiple times doesn't result in duplication
        evergreenService.requestStart();
        assertDesiredState(State.RUNNING);

        // requestRetart() overrides previous requestStop()
        desiredStateList.clear();
        evergreenService.requestStop();
        assertDesiredState(State.FINISHED);
        evergreenService.requestStart();
        assertDesiredState(State.RUNNING);

        desiredStateList.clear();
        evergreenService.requestRestart();
        assertDesiredState(State.FINISHED, State.RUNNING);
        evergreenService.requestStart();
        assertDesiredState(State.FINISHED, State.RUNNING);

        desiredStateList.clear();
        evergreenService.requestReinstall();
        assertDesiredState(State.FINISHED, State.NEW, State.RUNNING);
        evergreenService.requestStart();
        assertDesiredState(State.FINISHED, State.NEW, State.RUNNING);
    }

    @Test
    public void GIVEN_evergreenService_WHEN_requestStop_called_THEN_deduplicate_correctly() {
        desiredStateList.clear();
        evergreenService.requestStop();
        assertDesiredState(State.FINISHED);

        // calling requestRetart() multiple times doesn't result in duplication
        evergreenService.requestStop();
        assertDesiredState(State.FINISHED);

        // requestRetart() overrides previous requestStop()
        desiredStateList.clear();
        evergreenService.requestStart();
        assertDesiredState(State.RUNNING);
        evergreenService.requestStop();
        assertDesiredState(State.FINISHED);

        desiredStateList.clear();
        evergreenService.requestRestart();
        assertDesiredState(State.FINISHED, State.RUNNING);
        evergreenService.requestStop();
        assertDesiredState(State.FINISHED);

        desiredStateList.clear();
        evergreenService.requestReinstall();
        assertDesiredState(State.FINISHED, State.NEW, State.RUNNING);
        evergreenService.requestStop();
        assertDesiredState(State.FINISHED, State.NEW, State.FINISHED);
    }

    @Test
    public void GIVEN_evergreenService_WHEN_requestRestart_called_THEN_deduplicate_correctly() {
        desiredStateList.clear();
        evergreenService.requestRestart();
        assertDesiredState(State.FINISHED, State.RUNNING);

        // calling requestRetart() multiple times doesn't result in duplication
        evergreenService.requestRestart();
        assertDesiredState(State.FINISHED, State.RUNNING);

        // requestRetart() overrides previous requestStop()
        desiredStateList.clear();
        evergreenService.requestStop();
        assertDesiredState(State.FINISHED);

        evergreenService.requestRestart();
        assertDesiredState(State.FINISHED, State.RUNNING);

        // requestRetart() overrides previous requestStart()
        desiredStateList.clear();
        evergreenService.requestStart();
        assertDesiredState(State.RUNNING);

        evergreenService.requestRestart();
        assertDesiredState(State.FINISHED, State.RUNNING);

        // requestRestart() doesn't override previous requestReinstall()
        desiredStateList.clear();
        evergreenService.requestReinstall();
        assertDesiredState(State.FINISHED, State.NEW, State.RUNNING);

        evergreenService.requestRestart();
        assertDesiredState(State.FINISHED, State.NEW, State.RUNNING);

        // requestRestart() doesn't erase reinstall behavior
        setDesiredStateList(State.NEW, State.FINISHED);
        evergreenService.requestRestart();
        assertDesiredState(State.NEW, State.RUNNING);
    }


    @Test
    public void GIVEN_evergreenService_WHEN_requestReinstall_called_THEN_deduplicate_correctly() {
        evergreenService.requestReinstall();
        assertDesiredState(State.FINISHED, State.NEW, State.RUNNING);

        // calling requestReinstall() multiple times doesn't result in duplication
        evergreenService.requestReinstall();
        assertDesiredState(State.FINISHED, State.NEW, State.RUNNING);

        // calling requestRetart() doesn't override requestRe-install
        desiredStateList.clear();
        evergreenService.requestRestart();
        assertDesiredState(State.FINISHED, State.RUNNING);
        evergreenService.requestReinstall();
        assertDesiredState(State.FINISHED, State.NEW, State.RUNNING);

        // calling requestRetart() multiple times doesn't result in duplication
        desiredStateList.clear();
        evergreenService.requestStart();
        assertDesiredState(State.RUNNING);
        evergreenService.requestReinstall();
        assertDesiredState(State.FINISHED, State.NEW, State.RUNNING);
    }

    private void assertDesiredState(State... state) {
        Assertions.assertArrayEquals(state, desiredStateList.toArray(new State[] {}));
    }

    private void setDesiredStateList(State... state) {
        desiredStateList.clear();
        desiredStateList.addAll(Arrays.asList(state));
    }
}
