/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.config.Validator;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.dependency.State;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class EvergreenServiceTest {

    public static final String STATE_TOPIC_NAME = "_State";
    private static final String EVERGREEN_SERVICE_FULL_NAME = "EvergreenServiceFullName";

    private EvergreenService evergreenService;

    @Mock
    private Topics config;

    @Mock
    private Topic stateTopic;

    @Mock
    private Topic requiresTopic;

    @Mock
    private Context context;

    @Captor
    private ArgumentCaptor<Validator> validatorArgumentCaptor;

    @BeforeEach
    void beforeEach() {
        Mockito.when(config.createLeafChild(eq("_State"))).thenReturn(stateTopic);
        Mockito.when(config.createLeafChild(eq("requires"))).thenReturn(requiresTopic);
        Mockito.when(config.getName()).thenReturn(EVERGREEN_SERVICE_FULL_NAME);
        Mockito.when(requiresTopic.dflt(Mockito.any())).thenReturn(requiresTopic);

        evergreenService = new EvergreenService(config);
        evergreenService.context = context;
    }

    @Test
    void GIVEN_a_config_WHEN_constructor_is_called_THEN_service_is_initialized() {
        // GIVEN
        // provided in the beforeEach

        // WHEN
        // provided in the beforeEach

        // THEN
        // verify config
        Assertions.assertSame(config, evergreenService.config);
        Mockito.verify(config).createLeafChild(STATE_TOPIC_NAME);

        // verify stateTopic
        Mockito.verify(stateTopic).setParentNeedsToKnow(false);
        Mockito.verify(stateTopic).setValue(State.NEW);
        Mockito.verify(stateTopic).validate(validatorArgumentCaptor.capture());
        Mockito.verifyNoMoreInteractions(stateTopic);

        // verify validator
        Validator validator = validatorArgumentCaptor.getValue();
        State returnedState = (State) validator.validate(State.NEW, null);
        Assertions.assertSame(State.NEW, returnedState);
    }

    @Test
    void GIVEN_a_new_state_WHEN_getState_THEN_return_the_new_state() {
        Mockito.when(stateTopic.getOnce()).thenReturn(State.NEW);

        Assertions.assertSame(State.NEW, evergreenService.getState());

        Mockito.verify(stateTopic).getOnce();
    }
}
