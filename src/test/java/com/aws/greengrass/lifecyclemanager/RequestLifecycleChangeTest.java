/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.testcommons.testutilities.GGServiceTestUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

@ExtendWith(MockitoExtension.class)
class RequestLifecycleChangeTest extends GGServiceTestUtil {

    private GreengrassService greengrassService;

    private static Field desiredStateListField;
    private List<State> desiredStateList;

    @BeforeAll
    static void setup() throws Exception {
        desiredStateListField = Lifecycle.class.getDeclaredField("desiredStateList");
        desiredStateListField.setAccessible(true);
    }

    @BeforeEach
    void beforeEach() throws Exception {
        Topics config = initializeMockedConfig();
        greengrassService = new GreengrassService(config);
        Field lifecycleField = GreengrassService.class.getDeclaredField("lifecycle");
        lifecycleField.setAccessible(true);
        desiredStateList = (List<State>) desiredStateListField.get(lifecycleField.get(greengrassService));
    }

    @Test
    void GIVEN_greengrassService_WHEN_requestStart_called_THEN_deduplicate_correctly() {
        desiredStateList.clear();
        greengrassService.requestStart();
        assertDesiredState(State.RUNNING);

        // calling requestRestart() multiple times doesn't result in duplication
        greengrassService.requestStart();
        assertDesiredState(State.RUNNING);

        // requestRestart() overrides previous requestStop()
        desiredStateList.clear();
        greengrassService.requestStop();
        assertDesiredState(State.FINISHED);
        greengrassService.requestStart();
        assertDesiredState(State.RUNNING);

        desiredStateList.clear();
        greengrassService.requestRestart();
        assertDesiredState(State.INSTALLED, State.RUNNING);
        greengrassService.requestStart();
        assertDesiredState(State.INSTALLED, State.RUNNING);

        desiredStateList.clear();
        greengrassService.requestReinstall();
        assertDesiredState(State.NEW, State.RUNNING);
        greengrassService.requestStart();
        assertDesiredState(State.NEW, State.RUNNING);
    }

    @Test
    void GIVEN_greengrassService_WHEN_requestStop_called_THEN_deduplicate_correctly() {
        desiredStateList.clear();
        greengrassService.requestStop();
        assertDesiredState(State.FINISHED);

        // calling requestRestart() multiple times doesn't result in duplication
        greengrassService.requestStop();
        assertDesiredState(State.FINISHED);

        // requestRestart() overrides previous requestStop()
        desiredStateList.clear();
        greengrassService.requestStart();
        assertDesiredState(State.RUNNING);
        greengrassService.requestStop();
        assertDesiredState(State.FINISHED);

        desiredStateList.clear();
        greengrassService.requestRestart();
        assertDesiredState(State.INSTALLED, State.RUNNING);
        greengrassService.requestStop();
        assertDesiredState(State.FINISHED);

        desiredStateList.clear();
        greengrassService.requestReinstall();
        assertDesiredState(State.NEW, State.RUNNING);
        greengrassService.requestStop();
        assertDesiredState(State.NEW, State.FINISHED);
    }

    @Test
    void GIVEN_greengrassService_WHEN_requestRestart_called_THEN_deduplicate_correctly() {
        desiredStateList.clear();
        greengrassService.requestRestart();
        assertDesiredState(State.INSTALLED, State.RUNNING);

        // calling requestRestart() multiple times doesn't result in duplication
        greengrassService.requestRestart();
        assertDesiredState(State.INSTALLED, State.RUNNING);

        // requestRestart() overrides previous requestStop()
        desiredStateList.clear();
        greengrassService.requestStop();
        assertDesiredState(State.FINISHED);

        greengrassService.requestRestart();
        assertDesiredState(State.INSTALLED, State.RUNNING);

        // requestRestart() overrides previous requestStart()
        desiredStateList.clear();
        greengrassService.requestStart();
        assertDesiredState(State.RUNNING);

        greengrassService.requestRestart();
        assertDesiredState(State.INSTALLED, State.RUNNING);

        // requestRestart() doesn't override previous requestReinstall()
        desiredStateList.clear();
        greengrassService.requestReinstall();
        assertDesiredState(State.NEW, State.RUNNING);

        greengrassService.requestRestart();
        assertDesiredState(State.NEW, State.RUNNING);

        // requestRestart() doesn't erase reinstall behavior
        setDesiredStateList(State.NEW, State.FINISHED);
        greengrassService.requestRestart();
        assertDesiredState(State.NEW, State.RUNNING);
    }

    @Test
    void GIVEN_greengrassService_WHEN_requestReinstall_called_THEN_deduplicate_correctly() {
        greengrassService.requestReinstall();
        assertDesiredState(State.NEW, State.RUNNING);

        // calling requestReinstall() multiple times doesn't result in duplication
        greengrassService.requestReinstall();
        assertDesiredState(State.NEW, State.RUNNING);

        // calling requestRestart() doesn't override requestRe-install
        desiredStateList.clear();
        greengrassService.requestRestart();
        assertDesiredState(State.INSTALLED, State.RUNNING);
        greengrassService.requestReinstall();
        assertDesiredState(State.NEW, State.RUNNING);

        // calling requestRestart() multiple times doesn't result in duplication
        desiredStateList.clear();
        greengrassService.requestStart();
        assertDesiredState(State.RUNNING);
        greengrassService.requestReinstall();
        assertDesiredState(State.NEW, State.RUNNING);
    }

    private void assertDesiredState(State... state) {
        Assertions.assertArrayEquals(state, desiredStateList.toArray(new State[] {}));
    }

    private void setDesiredStateList(State... state) {
        desiredStateList.clear();
        desiredStateList.addAll(Arrays.asList(state));
    }
}
